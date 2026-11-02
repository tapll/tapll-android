package com.tapll.internal.crash

import com.tapll.Environment
import com.tapll.PrivacyMode
import com.tapll.internal.DeviceContext
import com.tapll.internal.Identity
import com.tapll.internal.SdkInfo

/*
 * The wire shape of a crash report, mirroring `packages/contract/src/crash-batch.ts` (crash contract
 * v2). Reports are plain maps: they are written to disk as JSON the moment they are built and read
 * back verbatim on the next launch, so there is no second representation to drift from the first.
 * `src/test/resources/crash-batch.v2.json` is the schema the server validates against, and the tests
 * validate every report and batch this code produces against it.
 */

internal object CrashLimits {
    const val MAX_REPORTS_PER_BATCH = 8
    const val MAX_THREADS = 48
    const val MAX_FRAMES_PER_THREAD = 128
    const val MAX_BREADCRUMBS = 64
    const val BREADCRUMB_NAME_MAX = 128
    const val REASON_MAX = 1024
    const val TYPE_MAX = 128
    const val THREAD_NAME_MAX = 128
    const val MAX_CUSTOM_KEYS = 8
    const val CUSTOM_KEY_MAX = 40
    const val CUSTOM_VALUE_MAX = 256
    const val FUNCTION_MAX = 256
    const val FILE_MAX = 256
}

/** What died, and how badly. Never summed into one "crashes" figure: four severities, four numbers. */
internal enum class CrashKind(val wire: String) { Fatal("fatal"), NonFatal("nonfatal"), Hang("hang"), Anr("anr") }

/** One frame of managed (JVM) code. `n` is assigned from list order when the thread is encoded. */
internal data class StackFrameData(
    val function: String,
    val module: String? = null,
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val inApp: Boolean,
)

internal data class ThreadDump(
    val name: String?,
    val crashed: Boolean,
    val frames: List<StackFrameData>,
    /** True when frames past the cap were dropped, so a cut dump never reads as a short stack. */
    val truncated: Boolean = false,
)

internal data class Crumb(val ts: Long, val kind: String, val name: String)

internal data class ReportInputs(
    val crashId: String,
    val kind: CrashKind,
    val crashedAt: Long,
    val sessionId: String?,
    val context: DeviceContext,
    val state: Map<String, Any>?,
    val exceptionType: String,
    val exceptionReason: String?,
    val hangDurationMs: Long? = null,
    val threads: List<ThreadDump>,
    val breadcrumbs: List<Crumb> = emptyList(),
    val customKeys: Map<String, String> = emptyMap(),
    val buildId: String? = null,
)

internal object CrashReportBuilder {
    const val PLATFORM = "android"

    fun frameJson(n: Int, f: StackFrameData): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["n"] = n
        // The contract wants a non-empty function; an unnamed frame is `<anonymous>`, never blank.
        m["function"] = bounded(f.function, CrashLimits.FUNCTION_MAX) ?: "<anonymous>"
        f.module?.let { bounded(it, 256) }?.let { m["module"] = it }
        f.file?.let { bounded(it, CrashLimits.FILE_MAX) }?.let { m["file"] = it }
        // Positive on the wire; a 0, -1 (unknown) or -2 (native) from a parser is "unknown", i.e. absent.
        f.line?.takeIf { it > 0 }?.let { m["line"] = it }
        f.column?.takeIf { it > 0 }?.let { m["column"] = it }
        m["in_app"] = f.inApp
        return m
    }

    private fun threadJson(index: Int, t: ThreadDump): Map<String, Any?> {
        val capped = t.frames.take(CrashLimits.MAX_FRAMES_PER_THREAD)
        val m = LinkedHashMap<String, Any?>()
        m["index"] = index
        t.name?.takeIf { it.isNotEmpty() }?.let { m["name"] = it.take(CrashLimits.THREAD_NAME_MAX) }
        m["crashed"] = t.crashed
        m["frames"] = capped.mapIndexed { n, f -> frameJson(n, f) }
        m["frames_truncated"] = t.truncated || t.frames.size > CrashLimits.MAX_FRAMES_PER_THREAD
        return m
    }

    fun build(i: ReportInputs): Map<String, Any?> {
        // A managed report is grouped by its thrown type alone, so it must never be blank.
        val type = i.exceptionType.trim().ifEmpty { "Error" }.take(CrashLimits.TYPE_MAX)
        val exception = LinkedHashMap<String, Any?>()
        exception["type"] = type
        i.exceptionReason?.let { exception["reason"] = it.take(CrashLimits.REASON_MAX) }

        val m = LinkedHashMap<String, Any?>()
        m["crash_id"] = i.crashId
        m["kind"] = i.kind.wire
        m["platform"] = PLATFORM
        i.buildId?.let { m["build_id"] = it }
        m["is_fatal"] = i.kind == CrashKind.Fatal
        m["crashed_at"] = i.crashedAt
        i.sessionId?.let { m["session_id"] = it }
        m["context"] = i.context.toJson()
        i.state?.takeIf { it.isNotEmpty() }?.let { m["state"] = it }
        m["exception"] = exception
        i.hangDurationMs?.let { m["hang_duration_ms"] = it }
        // Managed code has no binary images; the array is required by the contract and stays empty.
        m["images"] = emptyList<Any>()
        m["threads"] = i.threads.take(CrashLimits.MAX_THREADS).mapIndexed { idx, t -> threadJson(idx, t) }
        if (i.breadcrumbs.isNotEmpty()) {
            m["breadcrumbs"] = i.breadcrumbs.takeLast(CrashLimits.MAX_BREADCRUMBS).map {
                linkedMapOf("ts" to it.ts, "kind" to it.kind, "name" to it.name.take(CrashLimits.BREADCRUMB_NAME_MAX))
            }
        }
        if (i.customKeys.isNotEmpty()) m["custom_keys"] = i.customKeys
        return m
    }

    fun batch(batchId: String, environment: Environment, privacy: PrivacyMode, sentAt: Long, identity: Identity, reports: List<Map<String, Any?>>): Map<String, Any?> =
        linkedMapOf(
            "contract_version" to SdkInfo.CRASH_CONTRACT_VERSION,
            "batch_id" to batchId,
            "environment" to environment.wire,
            "privacy_mode" to privacy.wire,
            "sent_at" to sentAt,
            "identity" to identity.toJson(),
            "reports" to reports.take(CrashLimits.MAX_REPORTS_PER_BATCH),
        )

    private fun bounded(text: String, max: Int): String? = if (text.isEmpty()) null else text.take(max)
}

/** What to do with a report after an attempt to send it. Only two outcomes may delete one. */
internal sealed interface CrashUploadOutcome {
    data object Sent : CrashUploadOutcome
    data class Rejected(val why: String) : CrashUploadOutcome
    data object RetryLater : CrashUploadOutcome
    data object Stop : CrashUploadOutcome

    companion object {
        fun of(status: Int): CrashUploadOutcome = when (status) {
            in 200..299 -> Sent
            // A 400 is a report this SDK version can never make acceptable: keeping it would retry the
            // same rejection on every launch for the life of the install.
            400, 413, 422 -> Rejected("http $status")
            // The key is wrong or revoked: stop for this launch rather than walking the whole directory.
            401, 403 -> Stop
            // Including 503 ("could not stash this report, retry") and every 5xx: the file stays.
            else -> RetryLater
        }
    }
}
