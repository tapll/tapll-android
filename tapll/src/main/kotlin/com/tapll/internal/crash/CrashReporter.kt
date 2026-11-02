package com.tapll.internal.crash

import com.tapll.Environment
import com.tapll.LogLevel
import com.tapll.ManagedFrame
import com.tapll.PrivacyMode
import com.tapll.internal.BreadcrumbKind
import com.tapll.internal.Clock
import com.tapll.internal.CrashSink
import com.tapll.internal.DeviceContext
import com.tapll.internal.Identity
import com.tapll.internal.IdentityStore
import com.tapll.internal.Json
import com.tapll.internal.KeyValueStore
import com.tapll.internal.SdkLogger
import com.tapll.internal.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** An `ApplicationExitInfo` of reason `REASON_ANR`, reduced to what a report needs. */
internal class AnrExit(val timestampMs: Long, val description: String?, val traceText: String?)

internal fun interface AnrSource {
    fun exits(): List<AnrExit>
}

/**
 * Crash reporting, as the SDK sees it. The whole design follows from one fact, the same as on iOS:
 * **the process that died does not send the report.** The uncaught-exception handler writes the
 * report to disk, synchronously, and the SDK uploads whatever is on disk on the *next* launch. Everything
 * else (non-fatals, hangs, ANRs) is built in a healthy process and written the same way, and an
 * in-process upload attempt follows for those, so a caught error does not wait for a restart.
 *
 * Three things it must not do, each with a branch below: send a report it cannot attribute, delete a
 * report the server has not accepted (only 2xx and a permanent rejection clear the file), or hold a
 * device state and call it the state at the instant of death — the handler samples *at* the crash.
 */
internal class CrashReporter(
    private val config: Config,
    private val store: CrashStore,
    private val transport: Transport,
    private val identityStore: IdentityStore,
    private val kv: KeyValueStore,
    private val device: DeviceContext,
    private val matcher: InAppMatcher,
    private val stateSampler: DeviceStateSampler,
    private val logger: SdkLogger,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val buildId: String? = null,
    private val anrSource: AnrSource? = null,
    private val mainThread: () -> Thread? = { null },
    private val postToMain: ((Runnable) -> Unit)? = null,
) : CrashSink {
    class Config(
        val writeKey: String,
        var environment: Environment,
        val privacyMode: PrivacyMode,
        val hangThresholdMs: Long,
        val detectHangs: Boolean,
        val maxStored: Int,
    )

    private val lock = Any()
    private var sessionId: String? = null
    private val customKeys = LinkedHashMap<String, String>()
    private val crumbs = ArrayDeque<Crumb>()

    @Volatile private var foreground = false
    @Volatile private var installed = false
    private var handler: Handler? = null
    private var watchdog: HangWatchdog? = null
    private val uploadMutex = Mutex()

    /**
     * Where non-fatal reports are written, and why it is not the caller's thread: `recordError` is
     * documented as callable from anywhere and apps call it from the main thread, so the stack and
     * the device state are taken on the caller (the only part that has to happen there) and the
     * file write goes here. Serial, so reports reach disk in the order they were made.
     */
    private val writeQueue = ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "tapll-crash-writer").also { it.isDaemon = true }
    }.also { it.allowCoreThreadTimeOut(true) }
    private val inHandler = ThreadLocal<Boolean>()

    // region Lifecycle

    /** Installs the handlers, then sends what previous launches left behind. */
    fun start(sessionId: String?) {
        synchronized(lock) {
            if (installed) return
            installed = true
            this.sessionId = sessionId
        }
        // The previous launch's context, read before this launch overwrites it: an ANR belongs to the
        // process that died, and the app may have been updated since.
        val previousContext = kv.string(LAST_CONTEXT_KEY)?.let { Json.parseObjectOrNull(it) }?.let(DeviceContext::fromJson)
        kv.set(LAST_CONTEXT_KEY, Json.write(device.toJson()))

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val h = Handler(previous)
        synchronized(lock) { handler = h }
        Thread.setDefaultUncaughtExceptionHandler(h)
        if (config.detectHangs && postToMain != null) startWatchdog()
        logger.log(LogLevel.Info) { "crash: handler installed" }

        store.trim(config.maxStored)
        scope.launch {
            runCatching { collectAnrs(previousContext) }.onFailure { logger.log(LogLevel.Debug) { "crash: anr scan failed: ${it.message}" } }
            flushPending()
        }
    }

    /** Opt-out: stop collecting and take what is already on disk. */
    override fun wipe() {
        stop()
        // Queued behind any write already in flight, so a report cannot land after the wipe.
        writeQueue.execute { store.wipe() }
    }

    /** Test hook: waits for the writes this type schedules. Nothing in the SDK calls it. */
    fun waitForPendingWrites() {
        writeQueue.submit {}.get()
    }

    fun stop() {
        val h: Handler?
        synchronized(lock) {
            h = handler
            handler = null
            watchdog?.stop()
            watchdog = null
            installed = false
        }
        // Only restore if nobody chained over us since: replacing their handler would drop theirs.
        if (h != null && Thread.getDefaultUncaughtExceptionHandler() === h) {
            Thread.setDefaultUncaughtExceptionHandler(h.previous)
        } else {
            h?.disable()
        }
    }

    // endregion

    // region Live context

    override fun breadcrumb(kind: BreadcrumbKind, name: String) {
        val crumb = Crumb(clock.nowMs(), kind.wire, name.take(CrashLimits.BREADCRUMB_NAME_MAX))
        synchronized(lock) {
            crumbs.addLast(crumb)
            while (crumbs.size > CrashLimits.MAX_BREADCRUMBS) crumbs.removeFirst()
        }
    }

    override fun setSessionId(id: String?) {
        synchronized(lock) { sessionId = id }
    }

    fun setForeground(value: Boolean) {
        foreground = value
    }

    val isForeground: Boolean get() = foreground

    /**
     * The app's own label on a crash. Refused in `strictAnonymous`, exactly as `identify` is: an app
     * that may not send a `user_id` must not be able to send `{"email": ...}` under a crash key
     * instead. The contract rejects such a batch too, so this is the first of two locks on one door.
     */
    fun setCustomKey(key: String, value: String?) {
        if (config.privacyMode == PrivacyMode.StrictAnonymous) {
            logger.log(LogLevel.Warning) { "crash: custom keys are ignored in strict_anonymous" }
            return
        }
        val k = key.take(CrashLimits.CUSTOM_KEY_MAX)
        if (k.isEmpty()) return
        synchronized(lock) {
            if (value == null) {
                customKeys.remove(k)
            } else {
                if (!customKeys.containsKey(k) && customKeys.size >= CrashLimits.MAX_CUSTOM_KEYS) {
                    logger.log(LogLevel.Warning) { "crash: at most ${CrashLimits.MAX_CUSTOM_KEYS} custom keys; $k ignored" }
                    return
                }
                customKeys[k] = value.take(CrashLimits.CUSTOM_VALUE_MAX)
            }
        }
    }

    // endregion

    // region Building reports

    private fun snapshot(): Triple<String?, Map<String, String>, List<Crumb>> = synchronized(lock) {
        Triple(sessionId, customKeys.toMap(), crumbs.toList())
    }

    private fun state(): Map<String, Any> = runCatching { stateSampler.sample() }.getOrDefault(emptyMap())

    private fun extraMainThread(current: Thread?): List<ThreadDump> {
        val main = mainThread() ?: return emptyList()
        if (main === current) return emptyList()
        val trace = runCatching { main.stackTrace }.getOrNull() ?: return emptyList()
        return listOf(JvmStacks.thread(main.name, false, trace, matcher))
    }

    private fun inputsFor(kind: CrashKind, type: String, reason: String?, threads: List<ThreadDump>, hangMs: Long? = null, crashId: String = UUID.randomUUID().toString(), at: Long = clock.nowMs()): ReportInputs {
        val (sid, keys, crumbList) = snapshot()
        return ReportInputs(
            crashId = crashId, kind = kind, crashedAt = at, sessionId = sid, context = device, state = state(),
            exceptionType = type, exceptionReason = reason, hangDurationMs = hangMs, threads = threads,
            breadcrumbs = crumbList, customKeys = keys, buildId = buildId,
        )
    }

    private fun persist(inputs: ReportInputs) {
        val report = CrashReportBuilder.build(inputs)
        store.write(inputs.crashId, report)
        store.trim(config.maxStored)
    }

    // endregion

    // region Fatal

    private inner class Handler(val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        @Volatile private var enabled = true

        fun disable() {
            enabled = false
        }

        override fun uncaughtException(t: Thread, e: Throwable) {
            // A crash while reporting a crash must not recurse, and must never mask the original.
            if (enabled && inHandler.get() != true) {
                inHandler.set(true)
                try {
                    recordFatal(t, e)
                } catch (_: Throwable) {
                    // Nothing sensible left to do; the previous handler below is what matters now.
                } finally {
                    inHandler.set(false)
                }
            }
            if (previous != null) previous.uncaughtException(t, e) else t.threadGroup?.uncaughtException(t, e)
        }
    }

    /** Written **synchronously**: the process is about to die, so nothing here may be queued. */
    private fun recordFatal(t: Thread, e: Throwable) {
        val threads = JvmStacks.threadsOf(e, t.name, matcher, extraMainThread(t))
        persist(inputsFor(CrashKind.Fatal, e.javaClass.name, e.message, threads))
        logger.log(LogLevel.Info) { "crash: fatal ${e.javaClass.name} written to disk" }
    }

    // endregion

    // region Non-fatals

    /** An error the app caught and wants counted, with the throwable's own stack. */
    fun recordError(error: Throwable) {
        val thread = Thread.currentThread()
        val threads = JvmStacks.threadsOf(error, thread.name, matcher)
        recordNonFatal(CrashKind.NonFatal, error.javaClass.name, error.message, threads)
    }

    /** A named error with the stack of its caller, taken here, minus the SDK's own frames. */
    fun recordError(type: String, reason: String?) {
        val thread = Thread.currentThread()
        val dump = JvmStacks.thread(thread.name, true, Throwable().stackTrace, matcher, skipWhile = JvmStacks::isSdkFrame)
        recordNonFatal(CrashKind.NonFatal, type, reason, listOf(dump))
    }

    private fun recordNonFatal(kind: CrashKind, type: String, reason: String?, threads: List<ThreadDump>, hangMs: Long? = null, at: Long = clock.nowMs()) {
        if (!installed) return
        val inputs = inputsFor(kind, type, reason, threads, hangMs, at = at)
        writeQueue.execute {
            try {
                persist(inputs)
            } catch (e: Exception) {
                logger.log(LogLevel.Warning) { "crash: could not store a ${kind.wire} report (${e.message})" }
                return@execute
            }
            // Unlike iOS, which waits for the next launch, a caught error is also sent now.
            scope.launch { flushPending() }
        }
    }

    /**
     * A Dart or JavaScript error from a wrapper, with its parsed stack. `fatal = false` is a `nonfatal`,
     * the same path as [recordError]. `fatal = true` is written **synchronously** (the managed runtime
     * is about to take the process down) and uploaded on the next launch. Managed reports carry no images.
     */
    fun recordManagedError(type: String, reason: String?, frames: List<ManagedFrame>, fatal: Boolean, threadName: String) {
        val capped = frames.take(CrashLimits.MAX_FRAMES_PER_THREAD).map {
            StackFrameData(function = it.function, module = it.module, file = it.file, line = it.line, column = it.column, inApp = it.inApp)
        }
        val thread = ThreadDump(threadName.take(CrashLimits.THREAD_NAME_MAX), true, capped, truncated = frames.size > CrashLimits.MAX_FRAMES_PER_THREAD)
        if (!installed) return
        if (fatal) {
            try {
                persist(inputsFor(CrashKind.Fatal, type, reason, listOf(thread)))
            } catch (e: Exception) {
                logger.log(LogLevel.Warning) { "crash: could not store a fatal managed report (${e.message})" }
            }
        } else {
            recordNonFatal(CrashKind.NonFatal, type, reason, listOf(thread))
        }
    }

    // endregion

    // region Hangs and ANRs

    private fun startWatchdog() {
        val post = postToMain ?: return
        val dog = HangWatchdog(
            thresholdMs = config.hangThresholdMs,
            postToMain = post,
            mainStack = { mainThread()?.stackTrace },
            isForeground = { foreground },
            onHang = { durationMs, stack -> reportHang(durationMs, stack) },
        )
        synchronized(lock) { watchdog = dog }
        dog.start()
    }

    /** The stack was captured off the **main** thread when the threshold was crossed, not off the watchdog. */
    internal fun reportHang(durationMs: Long, stack: Array<StackTraceElement>) {
        val name = mainThread()?.name ?: "main"
        val dump = JvmStacks.thread(name, true, stack, matcher)
        recordNonFatal(CrashKind.Hang, "MainThreadHang", null, listOf(dump), hangMs = durationMs)
        logger.log(LogLevel.Info) { "crash: main thread blocked for ${durationMs}ms" }
    }

    /**
     * `REASON_ANR` exits since the last launch that looked. The first launch ever only sets the
     * watermark: ANRs from before the SDK was installed carry a context we do not have.
     */
    internal fun collectAnrs(previousContext: DeviceContext?) {
        val source = anrSource ?: return
        val watermark = kv.string(LAST_ANR_KEY)?.toLongOrNull()
        val exits = source.exits()
        val newest = exits.maxOfOrNull { it.timestampMs }
        if (watermark == null) {
            kv.set(LAST_ANR_KEY, (newest ?: clock.nowMs()).toString())
            return
        }
        val fresh = exits.filter { it.timestampMs > watermark }.sortedBy { it.timestampMs }
        for (exit in fresh) {
            val (frames, truncated) = exit.traceText?.let { AnrTrace.mainThreadFrames(it, matcher) } ?: (emptyList<StackFrameData>() to false)
            val keys = snapshot().second
            // Context of the launch that died. Session and breadcrumbs are this launch's, so they are left
            // out; state is absent too: nothing sampled it at the time.
            val inputs = ReportInputs(
                crashId = UUID.randomUUID().toString(), kind = CrashKind.Anr, crashedAt = exit.timestampMs, sessionId = null,
                context = previousContext ?: device, state = null, exceptionType = "ApplicationNotResponding",
                exceptionReason = exit.description, threads = listOf(ThreadDump("main", true, frames, truncated)),
                customKeys = keys, buildId = buildId,
            )
            runCatching { persist(inputs) }.onFailure { logger.log(LogLevel.Warning) { "crash: could not store an anr report (${it.message})" } }
        }
        if (newest != null && newest > watermark) kv.set(LAST_ANR_KEY, newest.toString())
    }

    // endregion

    // region Upload

    /** Everything on disk, oldest first. One at a time: a second caller waits for the first and finds the directory empty. */
    suspend fun flushPending() {
        uploadMutex.withLock {
            for (file in store.pending()) {
                val report = store.read(file)
                if (report == null) {
                    logger.log(LogLevel.Warning) { "crash: ${file.name} is not a report this SDK can read; discarded" }
                    store.discard(file)
                    continue
                }
                when (send(report)) {
                    is CrashUploadOutcome.Sent, is CrashUploadOutcome.Rejected -> store.discard(file)
                    CrashUploadOutcome.RetryLater -> continue
                    CrashUploadOutcome.Stop -> return
                }
            }
        }
    }

    private suspend fun send(report: Map<String, Any?>): CrashUploadOutcome {
        val strict = config.privacyMode == PrivacyMode.StrictAnonymous
        val identity = if (strict) Identity.ANONYMOUS else identityStore.load()
        // Defence in depth: a report written under another privacy mode must not smuggle keys through.
        val safe = if (strict && report.containsKey("custom_keys")) report - "custom_keys" else report
        val batch = CrashReportBuilder.batch(UUID.randomUUID().toString(), config.environment, config.privacyMode, clock.nowMs(), identity, listOf(safe))
        return try {
            val response = transport.send(Json.writeBytes(batch), config.writeKey)
            val outcome = CrashUploadOutcome.of(response.status)
            when (outcome) {
                CrashUploadOutcome.Sent -> logger.log(LogLevel.Debug) { "crash: sent 1 report" }
                is CrashUploadOutcome.Rejected -> logger.log(LogLevel.Warning) { "crash: report rejected (${outcome.why}); discarded" }
                CrashUploadOutcome.Stop -> logger.log(LogLevel.Warning) { "crash: upload refused (auth); stopping for this launch" }
                CrashUploadOutcome.RetryLater -> logger.log(LogLevel.Debug) { "crash: upload failed, keeping the report for the next launch" }
            }
            outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A network error keeps the file: a crash dropped on a flaky connection is a crash the developer never learns about.
            logger.log(LogLevel.Debug) { "crash: upload error (${e.message}); keeping the report" }
            CrashUploadOutcome.RetryLater
        }
    }

    // endregion

    companion object {
        const val LAST_CONTEXT_KEY = "last_launch_context"
        const val LAST_ANR_KEY = "last_anr_timestamp"
    }
}
