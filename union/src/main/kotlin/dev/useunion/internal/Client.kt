package dev.useunion.internal

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.useunion.Environment
import dev.useunion.LogLevel
import dev.useunion.Options
import dev.useunion.PrivacyMode
import dev.useunion.internal.crash.AnrExit
import dev.useunion.internal.crash.AnrSource
import dev.useunion.internal.crash.AndroidDeviceStateSampler
import dev.useunion.internal.crash.CrashReporter
import dev.useunion.internal.crash.CrashStore
import dev.useunion.internal.crash.InAppMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Seams for tests: a fake transport and a controllable clock. Production passes none. */
internal class ClientOverrides(val transport: Transport? = null, val crashTransport: Transport? = null, val clock: Clock? = null)

/** Wires real dependencies together. One instance per `configure`. */
internal class Client(
    context: Context,
    writeKey: String,
    val privacyMode: PrivacyMode,
    options: Options,
    wrapper: String?,
    private val overrides: ClientOverrides? = null,
) {
    private val appContext: Context = context.applicationContext ?: context
    val logger = SdkLogger(options.logLevel, options.logHandler)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One serial thread for all pipeline work: ordered enqueue, and disk and identity IO off the main thread. */
    private val executor = ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "union-pipeline").also { it.isDaemon = true }
    }.also { it.allowCoreThreadTimeOut(true) }

    val identity: IdentityCoordinator
    val pipeline: EventPipeline
    val crash: CrashReporter?
    private val kv: KeyValueStore
    @Volatile private var lifecycleObserver: AppLifecycleObserver? = null
    private var screenTracking: ScreenTracking? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        val device = DeviceContextProvider.current(appContext, wrapper)
        val environment = options.environment ?: EnvironmentDetector.detect(appContext)
        val keyHash = stableHash(writeKey)
        val dir = File(appContext.noBackupFilesDir, "Union/$keyHash")
        val store: EventStore = runCatching { FileEventStore(dir) }.getOrElse { InMemoryEventStore() }
        kv = FileKeyValueStore(File(dir, "state.json"))
        val strict = privacyMode == PrivacyMode.StrictAnonymous
        val identityStore: IdentityStore = if (strict) NoopIdentityStore else FileIdentityStore(File(dir, "identity.json"))
        identity = IdentityCoordinator(identityStore, strict)
        val userAgent = SdkInfo.userAgent(wrapper)
        pipeline = EventPipeline(
            config = PipelineConfig(writeKey, environment, privacyMode, options.flushAt, (options.flushIntervalSeconds * 1000).toLong(), options.maxQueuedEvents),
            store = store,
            transport = overrides?.transport ?: HttpTransport(options.endpoint, userAgent, gzip = false),
            identityStore = identity,
            kv = kv,
            clock = overrides?.clock ?: SystemClock,
            logger = logger,
            device = device,
            scope = scope,
        )

        val reporter = if (options.crashReporting) {
            makeCrashReporter(writeKey, dir, environment, options, device, userAgent, identityStore)
        } else {
            null
        }
        crash = reporter
        logger.log(LogLevel.Info) {
            "configured; env=${environment.wire} privacy=${privacyMode.wire} sdk=${SdkInfo.version}${wrapper?.let { " wrapper=$it" } ?: ""} crashes=${if (options.crashReporting) "on" else "off"}"
        }

        // An opted-out user stays opted out across launches: no handler, no watchdog, no uploads.
        val optedOut = kv.string("opt_out") == "1"
        if (reporter != null && !optedOut) reporter.start(sessionId = null)
        // Off the main thread: both read the queue and the identity from disk.
        post {
            it.pipeline.attach(reporter)
            it.pipeline.start()
        }

        // Everything that touches ProcessLifecycleOwner or the Application runs on the main thread.
        val auto = options.automaticScreenTracking
        val application = appContext as? Application
        mainHandler.post {
            runCatching {
                val observer = AppLifecycleObserver(this, reporter)
                lifecycleObserver = observer
                observer.register()
            }.onFailure { logger.log(LogLevel.Warning) { "lifecycle not observed: ${it.message}" } }
            if (auto && application != null) {
                val tracking = ScreenTracking(this)
                screenTracking = tracking
                application.registerActivityLifecycleCallbacks(tracking)
            }
        }
    }

    private fun makeCrashReporter(writeKey: String, dir: File, environment: Environment, options: Options, device: DeviceContext, userAgent: String, identityStore: IdentityStore): CrashReporter? {
        val store = runCatching { CrashStore(File(dir, "crashes")) }.getOrNull() ?: run {
            logger.log(LogLevel.Warning) { "crash: could not create the crash directory; crash reporting is off" }
            return null
        }
        val endpoint = options.crashEndpoint ?: crashEndpointFrom(options.endpoint)
        val holder = arrayOfNulls<CrashReporter>(1)
        val reporter = CrashReporter(
            config = CrashReporter.Config(writeKey, environment, privacyMode, (options.hangThresholdSeconds * 1000).toLong(), options.hangDetection, options.maxStoredCrashReports),
            store = store,
            transport = overrides?.crashTransport ?: HttpTransport(endpoint, userAgent, gzip = true),
            identityStore = identityStore,
            kv = kv,
            device = device,
            matcher = InAppMatcher(appContext.packageName, options.inAppPackages),
            stateSampler = AndroidDeviceStateSampler(appContext) { holder[0]?.isForeground ?: false },
            logger = logger,
            clock = overrides?.clock ?: SystemClock,
            scope = scope,
            buildId = BuildId.read(appContext),
            anrSource = if (Build.VERSION.SDK_INT >= 30) ExitInfoAnrSource(appContext) else null,
            mainThread = { Looper.getMainLooper().thread },
            postToMain = { mainHandler.post(it) },
        )
        holder[0] = reporter
        return reporter
    }

    // region Ordered work

    /** Runs [block] on the pipeline thread, after everything posted before it. Never throws into the caller. */
    fun post(block: (Client) -> Unit) {
        try {
            executor.execute {
                try {
                    block(this)
                } catch (t: Throwable) {
                    logger.log(LogLevel.Error) { "internal error: ${t.javaClass.simpleName}: ${t.message}" }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Shut down by a second configure; the call belongs to a client nobody holds any more.
        }
    }

    fun launch(block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.log(LogLevel.Error) { "internal error: ${t.javaClass.simpleName}: ${t.message}" }
        }
    }

    /** Resumes once every call posted so far has run: `flush()` after `track()` must see the event. */
    suspend fun barrier() {
        suspendCancellableCoroutine { cont ->
            try {
                executor.execute { cont.resume(Unit) }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                cont.resume(Unit)
            }
        }
    }

    // endregion

    fun optIn() {
        post {
            it.pipeline.optIn()
            // optOut stopped the crash handler; coming back in re-arms it (a no-op if it never stopped).
            crash?.start(sessionId = pipeline.currentSessionId)
        }
    }

    fun shutdown() {
        crash?.stop()
        pipeline.shutdown()
        val tracking = screenTracking
        val observer = lifecycleObserver
        mainHandler.post {
            runCatching { observer?.unregister() }
            val app = appContext as? Application
            if (tracking != null) runCatching { app?.unregisterActivityLifecycleCallbacks(tracking) }
        }
        scope.cancel()
        executor.shutdown()
    }

    companion object {
        /** FNV-1a 64-bit, base 36. Stable across launches; only used to name the on-disk directory. */
        fun stableHash(text: String): String {
            var hash = 0xcbf29ce484222325UL.toLong()
            for (b in text.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (b.toLong() and 0xFF)
                hash *= 0x100000001b3L
            }
            return hash.toULong().toString(36)
        }
    }
}

/** `assets/union-build-id.txt`, written by the Gradle plugin; absent for a build that never ran it. */
internal object BuildId {
    private val pattern = Regex("^[0-9a-f-]{7,64}$")

    fun read(context: Context): String? = runCatching {
        context.assets.open("union-build-id.txt").use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()?.let(::parse)

    /** Trimmed, and only if it is what the contract accepts: a malformed id would fail the whole report. */
    fun parse(text: String): String? = text.trim().takeIf { pattern.matches(it) }
}

/** `REASON_ANR` entries of `ApplicationExitInfo` (API 30+). */
internal class ExitInfoAnrSource(private val context: Context) : AnrSource {
    override fun exits(): List<AnrExit> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 16)
        return infos.filter { it.reason == android.app.ApplicationExitInfo.REASON_ANR }.map { info ->
            val trace = runCatching { info.traceInputStream?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            AnrExit(info.timestamp, info.description, trace)
        }
    }
}
