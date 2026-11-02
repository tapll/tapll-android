package com.tapll.internal

import com.tapll.Environment
import com.tapll.FeatureRole
import com.tapll.LogLevel
import com.tapll.PrivacyMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class PipelineConfig(
    val writeKey: String,
    var environment: Environment,
    val privacyMode: PrivacyMode,
    val flushAt: Int,
    val flushIntervalMs: Long,
    val maxQueuedEvents: Int,
)

/** What the pipeline tells the crash reporter. Absent when `Options.crashReporting` is off. */
internal interface CrashSink {
    fun setSessionId(id: String?)
    fun breadcrumb(kind: BreadcrumbKind, name: String)
    fun wipe()
}

internal enum class BreadcrumbKind(val wire: String) { Event("event"), Screen("screen"), Log("log"), State("state") }

/**
 * Owns the queue, session state, identity and network. Every public SDK call ends up here.
 *
 * State is guarded by one lock and never held across a network call, so the facade can post work from
 * any thread; ordering comes from the single-thread executor in `Client`. [flush] is the only
 * suspending entry point and keeps the iOS guarantee: one drain in flight, and a second `flush()`
 * waits for it and then drains what is left.
 */
internal class EventPipeline(
    private val config: PipelineConfig,
    private val store: EventStore,
    private val transport: Transport,
    private val identityStore: IdentityCoordinator,
    private val kv: KeyValueStore,
    private val clock: Clock,
    private val logger: SdkLogger,
    private val device: DeviceContext,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var queue = ArrayList<Event>()
    private val session = SessionManager(clock, kv)
    private var identity: Identity = Identity.ANONYMOUS
    private var prepared = false
    private var stopped = false
    private var pausedUntil = 0L
    private var attempt = 0
    private var inFlight: CompletableDeferred<Unit>? = null
    private var timer: Job? = null
    private var optedOut: Boolean = kv.string("opt_out") == "1"
    private var analyticsEnabled = true
    private var crash: CrashSink? = null
    private var crashSessionId: String? = null

    /** Nothing here touches the disk until an entry point runs, and that is on the pipeline thread. */
    private fun ensurePrepared() {
        if (prepared) return
        prepared = true
        identity = identityStore.ensure(clock.nowMs())
        queue = ArrayList(runCatching { store.load() }.getOrDefault(emptyList()))
    }

    fun attach(crash: CrashSink?) = synchronized(lock) {
        ensurePrepared()
        this.crash = crash
        val id = session.current?.sessionId
        crashSessionId = id
        crash?.setSessionId(id)
    }

    fun shutdown() = synchronized(lock) {
        stopped = true
        timer?.cancel()
        timer = null
        crash = null
        crashSessionId = null
    }

    // region Lifecycle entry points

    /** Cold start: install/update detection, then session start. */
    fun start() = synchronized(lock) {
        ensurePrepared()
        if (optedOut || !analyticsEnabled) return@synchronized
        val outcome = InstallState.evaluate(kv, device)
        val transition = session.touch()
        val pre = ArrayList<Event>()
        val sid: String
        when (transition) {
            is SessionManager.Transition.Continued -> sid = transition.sessionId
            is SessionManager.Transition.Rotated -> {
                sid = transition.started.sessionId
                transition.ended?.let { pre.add(system(AutoEvent.SessionEnd, it.sessionId, it.lastActivityAt)) }
            }
        }
        when (outcome) {
            // Android never sends `reinstall`: nothing survives uninstall, so a reinstall is a new install.
            InstallState.Outcome.FirstOpen -> {
                pre.add(system(AutoEvent.FirstOpen, sid))
                pre.add(system(AutoEvent.AppInstall, sid))
            }
            is InstallState.Outcome.Updated -> pre.add(
                system(AutoEvent.AppUpdate, sid, properties = mapOf("previous_version" to outcome.previousVersion, "previous_build" to outcome.previousBuild)),
            )
            InstallState.Outcome.Unchanged -> Unit
        }
        if (transition is SessionManager.Transition.Rotated) pre.add(system(AutoEvent.SessionStart, transition.started.sessionId, transition.started.startedAt))
        pre.forEach(::push)
        startTimer()
        scheduleFlushIfNeeded()
    }

    suspend fun didEnterBackground() {
        synchronized(lock) {
            ensurePrepared()
            if (optedOut || !analyticsEnabled) return
            val t = session.touch()
            if (t is SessionManager.Transition.Continued) push(system(AutoEvent.Background, t.sessionId))
            timer?.cancel()
            timer = null
        }
        flush()
    }

    fun willEnterForeground() = synchronized(lock) {
        ensurePrepared()
        if (optedOut || !analyticsEnabled) return@synchronized
        when (val t = session.touch()) {
            is SessionManager.Transition.Continued -> push(system(AutoEvent.Foreground, t.sessionId))
            is SessionManager.Transition.Rotated -> {
                t.ended?.let { push(system(AutoEvent.SessionEnd, it.sessionId, it.lastActivityAt)) }
                push(system(AutoEvent.SessionStart, t.started.sessionId, t.started.startedAt))
            }
        }
        startTimer()
        scheduleFlushIfNeeded()
    }

    // endregion

    // region Public operations

    /**
     * An invalid feature key drops the whole event rather than sending it without the key: an event
     * stripped of the feature it was written for would read on the server as "part of no feature".
     */
    fun track(name: String, properties: Map<String, Any>, feature: String? = null, role: FeatureRole? = null, screen: String? = null) = synchronized(lock) {
        ensurePrepared()
        if (optedOut || !analyticsEnabled || stopped) return@synchronized
        try {
            Validation.customName(name)
            Validation.properties(properties)
            Validation.screen(screen)
            Validation.featureKey(feature)
        } catch (e: ValidationException) {
            logger.log(LogLevel.Warning) { "dropped event \"$name\": ${e.message}" }
            return@synchronized
        }
        val sid = liveSessionId()
        push(Event(UUIDv7.generate(clock.nowMs()), sid, name, clock.nowMs(), screen, properties.ifEmpty { null }, feature, role))
        scheduleFlushIfNeeded()
    }

    /** [feature] makes this screen view the feature's discovery step. */
    fun screen(name: String, properties: Map<String, Any>, feature: String? = null) = synchronized(lock) {
        ensurePrepared()
        if (optedOut || !analyticsEnabled || stopped) return@synchronized
        try {
            Validation.screen(name)
            if (name.isEmpty()) throw ValidationException("screen name is empty")
            Validation.properties(properties)
            Validation.featureKey(feature)
        } catch (e: ValidationException) {
            logger.log(LogLevel.Warning) { "dropped screen \"$name\": ${e.message}" }
            return@synchronized
        }
        var event = system(AutoEvent.ScreenView, liveSessionId(), screen = name, properties = properties.ifEmpty { null })
        if (feature != null) event = event.copy(feature = feature, role = FeatureRole.Discovery)
        push(event)
        scheduleFlushIfNeeded()
    }

    /** `$deep_link { url_scheme, host, path }`. The query and fragment never get this far: the caller passes parts. */
    fun deepLink(scheme: String?, host: String?, path: String?) = synchronized(lock) {
        ensurePrepared()
        if (optedOut || !analyticsEnabled || stopped) return@synchronized
        val props = LinkedHashMap<String, Any>()
        scheme?.let { props["url_scheme"] = it.take(Limits.PROPERTY_STRING_MAX) }
        host?.let { props["host"] = it.take(Limits.PROPERTY_STRING_MAX) }
        props["path"] = (path ?: "").take(Limits.PROPERTY_STRING_MAX)
        push(system(AutoEvent.DeepLink, liveSessionId(), properties = props))
        scheduleFlushIfNeeded()
    }

    fun identify(userId: String, traits: Map<String, String> = emptyMap()) = synchronized(lock) {
        ensurePrepared()
        if (optedOut || !analyticsEnabled) return@synchronized
        if (config.privacyMode != PrivacyMode.ProductAnalytics) {
            logger.log(LogLevel.Warning) { "identify() ignored: project is strict_anonymous" }
            return@synchronized
        }
        try {
            Validation.userId(userId)
            Validation.traits(traits)
        } catch (e: ValidationException) {
            logger.log(LogLevel.Warning) { "identify() ignored: ${e.message}" }
            return@synchronized
        }
        // Merged, not replaced: an app that identifies on launch with an email and later adds a plan
        // must not drop the email. The server merges the same way, key by key.
        val merged = if (traits.isEmpty()) identity.traits else (identity.traits ?: emptyMap()) + traits
        identity = identity.copy(userId = userId, traits = merged)
        identityStore.save(identity)
    }

    /**
     * Logout: forget user id and traits and rotate the session; the install id stays. With no user id
     * there is nobody to forget, so this is a no-op (a cold launch that resets before auth restores
     * keeps one session). In strict_anonymous there is never a user id and the session *is* the
     * identity, so it always rotates.
     */
    fun reset() = synchronized(lock) {
        ensurePrepared()
        if (identity.userId == null && config.privacyMode != PrivacyMode.StrictAnonymous) return@synchronized
        identity = identity.copy(userId = null, traits = null)
        identityStore.save(identity)
        val t = session.rotate()
        if (t is SessionManager.Transition.Rotated) {
            t.ended?.let { push(system(AutoEvent.SessionEnd, it.sessionId)) }
            push(system(AutoEvent.SessionStart, t.started.sessionId, t.started.startedAt))
        }
    }

    /**
     * Pauses or resumes product analytics without touching crash reporting. Disabling is destructive
     * for analytics state: queued events, the identity and the session are removed so an opt-out cannot
     * leak on the next enable.
     */
    fun setAnalyticsCollectionEnabled(enabled: Boolean) = synchronized(lock) {
        ensurePrepared()
        if (analyticsEnabled == enabled) return@synchronized
        analyticsEnabled = enabled
        if (!enabled) {
            queue.clear()
            runCatching { store.replaceAll(emptyList()) }
            identityStore.wipe()
            identity = Identity.ANONYMOUS
            session.clear()
            timer?.cancel()
            timer = null
            crashSessionId = null
            crash?.setSessionId(null)
            logger.log(LogLevel.Info) { "product analytics disabled; crash reporting remains active" }
            return@synchronized
        }
        if (optedOut) return@synchronized
        identityStore.forget()
        identity = identityStore.ensure(clock.nowMs())
        start()
    }

    /** Stops collection, wipes queue, identity, session and crash files, persists the flag. */
    fun optOut() = synchronized(lock) {
        ensurePrepared()
        optedOut = true
        kv.set("opt_out", "1")
        queue.clear()
        runCatching { store.replaceAll(emptyList()) }
        identityStore.wipe()
        identity = Identity.ANONYMOUS
        session.clear()
        timer?.cancel()
        timer = null
        // Crash reports are local data like any other: "wiped" would be true of the event queue and
        // false of the crash directory otherwise.
        crash?.wipe()
        crashSessionId = null
        logger.log(LogLevel.Info) { "opted out: collection stopped, local data wiped" }
    }

    fun optIn() = synchronized(lock) {
        ensurePrepared()
        if (!optedOut) return@synchronized
        optedOut = false
        kv.set("opt_out", null)
        // `optOut` wiped the identity, so the cache holds the empty one: mint again rather than read back.
        identityStore.forget()
        identity = identityStore.ensure(clock.nowMs())
        start()
    }

    // endregion

    // region Flush

    /**
     * Sends everything queued when it was called, unless the server or the network refuses. A call
     * made while another drain is in flight **waits for it and then drains what is left**.
     */
    suspend fun flush() {
        val gate = CompletableDeferred<Unit>()
        while (true) {
            val running = synchronized(lock) {
                ensurePrepared()
                if (inFlight == null) { inFlight = gate; null } else inFlight
            }
            if (running == null) break
            running.await()
        }
        try {
            val go = synchronized(lock) {
                !stopped && !optedOut && analyticsEnabled && queue.isNotEmpty() && pausedUntil <= clock.nowMs()
            }
            if (go) drain()
        } finally {
            synchronized(lock) { inFlight = null }
            gate.complete(Unit)
        }
    }

    private suspend fun drain() {
        var maxEvents = Limits.BATCH_MAX_EVENTS
        while (true) {
            val snapshot = synchronized(lock) {
                if (queue.isEmpty() || stopped || optedOut || !analyticsEnabled) return
                // Re-checked every loop: a 429 inside this drain must end it, not just the next flush.
                if (pausedUntil > clock.nowMs()) return
                Triple(Batcher.nextBatch(queue, maxEvents), identity, config.environment)
            }
            val (batch, ident, environment) = snapshot
            val envelope = EventBatch(UUIDv7.generate(clock.nowMs()), environment, config.privacyMode, clock.nowMs(), device, ident, batch)
            val body = runCatching { envelope.encode() }.getOrNull() ?: return
            val response = try {
                transport.send(body, config.writeKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(LogLevel.Debug) { "network error: ${e.message}" }
                backoff()
                return
            }
            when (val d = Disposition.from(response)) {
                is Disposition.Accepted -> {
                    synchronized(lock) { attempt = 0 }
                    maxEvents = Limits.BATCH_MAX_EVENTS
                    if (d.rejected > 0) logger.log(LogLevel.Info) { "${d.rejected} event(s) filtered server-side (kill switch)" }
                    logger.log(LogLevel.Info) { "sent ${batch.size} event(s): ${batch.joinToString { it.name }}" }
                    removeFromQueue(batch)
                }
                is Disposition.DropEvents -> {
                    val bad = d.indices.mapNotNull { batch.getOrNull(it) }
                    logger.log(LogLevel.Error) { "server rejected ${bad.size} event(s): ${bad.joinToString { it.name }}" }
                    removeFromQueue(bad)
                }
                is Disposition.DropBatch -> {
                    logger.log(LogLevel.Error) { "batch rejected (${d.reason}); dropping ${batch.size} event(s). Check write key/environment/privacy mode." }
                    removeFromQueue(batch)
                }
                Disposition.Split -> {
                    if (maxEvents <= 1) {
                        removeFromQueue(batch)
                        logger.log(LogLevel.Error) { "event too large; dropped" }
                    } else {
                        maxEvents = maxOf(1, maxEvents / 2)
                    }
                }
                is Disposition.Pause -> {
                    synchronized(lock) { pausedUntil = clock.nowMs() + (d.seconds * 1000).toLong() }
                    logger.log(LogLevel.Warning) { "paused ${d.seconds.toInt()}s: ${d.reason}" }
                    return
                }
                is Disposition.Stop -> {
                    synchronized(lock) { stopped = true }
                    logger.log(LogLevel.Error) { "stopped: ${d.reason}" }
                    return
                }
                Disposition.RetryLater -> {
                    backoff()
                    return
                }
            }
        }
    }

    // endregion

    // region Internals

    private fun liveSessionId(): String = when (val t = session.touch()) {
        is SessionManager.Transition.Continued -> t.sessionId
        is SessionManager.Transition.Rotated -> {
            t.ended?.let { push(system(AutoEvent.SessionEnd, it.sessionId, it.lastActivityAt)) }
            push(system(AutoEvent.SessionStart, t.started.sessionId, t.started.startedAt))
            t.started.sessionId
        }
    }

    private fun system(kind: AutoEvent, sessionId: String, at: Long? = null, screen: String? = null, properties: Map<String, Any>? = null): Event =
        Event(UUIDv7.generate(clock.nowMs()), sessionId, kind.wire, at ?: clock.nowMs(), screen, properties)

    private fun push(e: Event) {
        // The one funnel every event passes through, which is why the breadcrumb trail is written
        // here: a trail that misses `$background` because somebody added an event path and forgot a
        // call is a trail that lies. Names only: breadcrumbs bypass the remote kill switch, so a value
        // here would carry event properties out of screens the app believed were excluded.
        crash?.let { c ->
            if (e.sessionId != crashSessionId) {
                crashSessionId = e.sessionId
                c.setSessionId(e.sessionId)
            }
            if (e.name == AutoEvent.ScreenView.wire && e.screen != null) c.breadcrumb(BreadcrumbKind.Screen, e.screen) else c.breadcrumb(BreadcrumbKind.Event, e.name)
        }
        queue.add(e)
        if (queue.size > config.maxQueuedEvents) {
            val drop = queue.size - config.maxQueuedEvents
            queue = ArrayList(queue.drop(drop))
            runCatching { store.replaceAll(queue) }
            logger.log(LogLevel.Warning) { "queue full: evicted $drop oldest event(s)" }
        } else {
            runCatching { store.append(e) }.onFailure { logger.log(LogLevel.Debug) { "persist failed: ${it.message}" } }
        }
    }

    private fun removeFromQueue(events: List<Event>) = synchronized(lock) {
        val ids = events.mapTo(HashSet()) { it.eventId }
        queue.removeAll { it.eventId in ids }
        runCatching { store.replaceAll(queue) }
    }

    private fun backoff() = synchronized(lock) {
        attempt += 1
        pausedUntil = clock.nowMs() + (Backoff.delaySeconds(attempt) * 1000).toLong()
    }

    private fun scheduleFlushIfNeeded() {
        if (queue.size < config.flushAt) return
        scope.launch { flush() }
    }

    private fun startTimer() {
        timer?.cancel()
        val interval = config.flushIntervalMs
        // `delay` throws on cancellation and nothing here catches it, so cancelling the timer (collection
        // turned off, going to background, a second `configure`) never *fires* it once on the way out.
        timer = scope.launch {
            while (isActive) {
                delay(interval)
                flush()
            }
        }
    }

    // endregion

    // Test hooks
    val queuedEvents: List<Event> get() = synchronized(lock) { queue.toList() }
    val currentSessionId: String? get() = synchronized(lock) { session.current?.sessionId }
    val currentCrashSessionId: String? get() = synchronized(lock) { crashSessionId }
    val currentIdentity: Identity get() = synchronized(lock) { identity }
    val isStopped: Boolean get() = synchronized(lock) { stopped }
    val isPaused: Boolean get() = synchronized(lock) { pausedUntil > clock.nowMs() }
    val isOptedOut: Boolean get() = synchronized(lock) { optedOut }
    val isAnalyticsEnabled: Boolean get() = synchronized(lock) { analyticsEnabled }
    val hasTimer: Boolean get() = synchronized(lock) { timer?.isActive == true }
}
