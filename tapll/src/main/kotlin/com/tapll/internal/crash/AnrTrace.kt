package com.tapll.internal.crash

/**
 * Reads the main thread out of the text trace Android attaches to an ANR
 * (`ApplicationExitInfo.getTraceInputStream`).
 *
 * The format is not a contract, so this is deliberately forgiving: it looks for the `"main"` thread
 * header, takes the Java `at pkg.Class.method(File.java:12)` lines under it until the block ends, and
 * ignores native (`native: #00 pc ...`) and kernel lines. A trace it cannot read gives no frames, and
 * the report still goes out — an ANR with no stack is a fact; a guessed stack is not.
 */
internal object AnrTrace {
    private val frameLine = Regex("""^\s+at\s+(\S+)\((.*)\)\s*$""")

    fun mainThreadFrames(trace: String, matcher: InAppMatcher): Pair<List<StackFrameData>, Boolean> {
        val lines = trace.lineSequence().iterator()
        var found = false
        while (lines.hasNext()) {
            if (lines.next().startsWith("\"main\"")) { found = true; break }
        }
        if (!found) return emptyList<StackFrameData>() to false
        val frames = ArrayList<StackFrameData>()
        var total = 0
        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isBlank()) break
            val m = frameLine.matchEntire(line) ?: continue
            total++
            if (frames.size >= CrashLimits.MAX_FRAMES_PER_THREAD) continue
            val qualified = m.groupValues[1]
            val loc = m.groupValues[2]
            val cut = qualified.lastIndexOf('.')
            val cls = if (cut > 0) qualified.substring(0, cut) else ""
            var file: String? = null
            var lineNo: Int? = null
            // `(Pay.kt:88)`, `(Native Method)`, `(Unknown Source:5)`, `(Unknown Source)`.
            val colon = loc.lastIndexOf(':')
            val name = if (colon > 0) loc.substring(0, colon) else loc
            if (colon > 0) lineNo = loc.substring(colon + 1).toIntOrNull()
            if (name != "Native Method" && name != "Unknown Source") file = name
            frames.add(StackFrameData(function = qualified, file = file, line = lineNo?.takeIf { it > 0 }, inApp = matcher.isInApp(cls)))
        }
        return frames to (total > CrashLimits.MAX_FRAMES_PER_THREAD)
    }
}
