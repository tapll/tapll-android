package com.tapll.internal.crash

/**
 * Notices when the main thread stops answering.
 *
 * A hang is not a crash and is measured differently: nothing dies, so there is no uncaught exception
 * and no next launch to wait for. The watchdog lives on its own daemon thread, posts a tick to the
 * main looper, and notices when a tick goes unanswered for longer than the threshold.
 *
 * Two rules keep it from lying. It reports **once per episode**, because a main thread blocked for
 * twelve seconds is one hang and not forty-eight; and it reports the duration it **measured** — the
 * time from the unanswered tick to the moment the main thread answers again — never the threshold.
 * The stack is captured at the moment the threshold is crossed (the main thread has usually moved on
 * by the time it answers), and the report is written when the episode ends. An episode that never
 * ends is an ANR and is reported as one, on the next launch, by `ApplicationExitInfo`.
 *
 * Ticks only start while the app is in the foreground: a cached process that the system froze and
 * thawed would otherwise report the freeze as a hang of however long it slept.
 *
 * Everything the watchdog touches is injected, so tests drive it with a plain thread standing in for
 * the main thread.
 */
internal class HangWatchdog(
    private val thresholdMs: Long,
    private val tickMs: Long = 250,
    private val postToMain: (Runnable) -> Unit,
    private val mainStack: () -> Array<StackTraceElement>?,
    private val isForeground: () -> Boolean,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onHang: (durationMs: Long, stack: Array<StackTraceElement>) -> Unit,
) {
    private val lock = Any()
    private var thread: Thread? = null

    @Volatile private var running = false
    @Volatile private var answered = true

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        val t = Thread({ loop() }, "tapll-hang-watchdog")
        t.isDaemon = true
        // Below normal: a watchdog that competes for CPU with the work it is timing would report hangs it helped cause.
        t.priority = Thread.MIN_PRIORITY
        synchronized(lock) { thread = t }
        t.start()
    }

    fun stop() {
        synchronized(lock) { running = false }
        thread?.interrupt()
    }

    private fun loop() {
        var sentAt = 0L
        var detectedStack: Array<StackTraceElement>? = null
        var detected = false
        try {
            while (running) {
                Thread.sleep(tickMs)
                if (!running) return
                if (answered) {
                    if (detected) {
                        // The episode just ended: report what was measured, with the stack from the moment it was crossed.
                        val duration = (nanoTime() - sentAt) / 1_000_000
                        val stack = detectedStack
                        detected = false
                        detectedStack = null
                        if (stack != null) runCatching { onHang(duration, stack) }
                    }
                    if (!isForeground()) continue
                    answered = false
                    sentAt = nanoTime()
                    postToMain(Runnable { answered = true })
                    continue
                }
                val waitingMs = (nanoTime() - sentAt) / 1_000_000
                if (waitingMs >= thresholdMs && !detected) {
                    detected = true
                    detectedStack = runCatching { mainStack() }.getOrNull()
                }
            }
        } catch (_: InterruptedException) {
            // stop()
        }
    }
}
