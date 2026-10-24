package dev.useunion.internal.crash

/** Which classes count as the app's own code (`in_app`). */
internal class InAppMatcher(packageName: String, extra: List<String>) {
    private val prefixes: List<String> = (listOf(packageName) + extra).map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }.distinct()

    /** `com.example` matches `com.example.Foo` and `com.example.Foo$Bar`, but not `com.examplefoo.Foo`. */
    fun isInApp(className: String): Boolean = prefixes.any { className == it || className.startsWith("$it.") }
}

/**
 * JVM stacks to managed frames and threads.
 *
 * **Cause chains** (`Caused by:`) are not flattened into one frame list. The crashed thread carries
 * the thrown (outermost) exception's own stack — what the runtime printed as `FATAL EXCEPTION` and
 * what `exception.type` names — and each cause becomes an extra, non-crashed thread named
 * `caused by <type>`, innermost last, so the root cause's stack is in the report without a wrapper
 * hiding the site that wrapped it and without the contract growing a field. Capped by `MAX_THREADS`.
 */
internal object JvmStacks {
    fun frame(e: StackTraceElement, matcher: InAppMatcher): StackFrameData {
        val cls = e.className ?: ""
        val method = e.methodName ?: ""
        return StackFrameData(
            function = if (cls.isEmpty()) method else "$cls.$method",
            file = e.fileName,
            line = e.lineNumber.takeIf { it > 0 },
            inApp = matcher.isInApp(cls),
        )
    }

    fun frames(trace: Array<StackTraceElement>, matcher: InAppMatcher, skipWhile: (StackTraceElement) -> Boolean = { false }): Pair<List<StackFrameData>, Boolean> {
        val start = trace.indexOfFirst { !skipWhile(it) }.let { if (it < 0) trace.size else it }
        val all = trace.size - start
        val capped = trace.asSequence().drop(start).take(CrashLimits.MAX_FRAMES_PER_THREAD).map { frame(it, matcher) }.toList()
        return capped to (all > CrashLimits.MAX_FRAMES_PER_THREAD)
    }

    fun thread(name: String?, crashed: Boolean, trace: Array<StackTraceElement>, matcher: InAppMatcher, skipWhile: (StackTraceElement) -> Boolean = { false }): ThreadDump {
        val (frames, truncated) = frames(trace, matcher, skipWhile)
        return ThreadDump(name, crashed, frames, truncated)
    }

    /** The throwable's own stack as the crashed thread, then its causes as extra threads. */
    fun threadsOf(throwable: Throwable, crashedThreadName: String?, matcher: InAppMatcher, extra: List<ThreadDump> = emptyList()): List<ThreadDump> {
        val out = ArrayList<ThreadDump>()
        out.add(thread(crashedThreadName, true, throwable.stackTrace, matcher))
        val seen = HashSet<Throwable>().also { it.add(throwable) }
        var cause = throwable.cause
        while (cause != null && seen.add(cause)) {
            out.add(thread("caused by ${cause.javaClass.name}", false, cause.stackTrace, matcher))
            cause = cause.cause
        }
        out.addAll(extra)
        return out.take(CrashLimits.MAX_THREADS)
    }

    /** The SDK's own frames are noise at the top of a stack taken inside `recordError`. */
    fun isSdkFrame(e: StackTraceElement): Boolean =
        e.className == "dev.useunion.Union" || e.className.startsWith("dev.useunion.Union$") || e.className.startsWith("dev.useunion.internal.")
}
