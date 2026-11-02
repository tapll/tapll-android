package com.tapll.internal

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** What the pipeline should do after a response. */
internal sealed interface Disposition {
    /** Batch acknowledged (the server may have filtered [rejected] events via the kill switch). */
    data class Accepted(val rejected: Int) : Disposition

    /** Drop these events (by index within the sent batch); keep and immediately retry the rest. */
    data class DropEvents(val indices: Set<Int>) : Disposition

    /** Drop the entire batch (config bug: env mismatch, privacy violation, malformed envelope). */
    data class DropBatch(val reason: String) : Disposition

    /** Batch too large: send half. */
    data object Split : Disposition

    /** Keep the batch, wait, then retry. */
    data class Pause(val seconds: Double, val reason: String) : Disposition

    /** Invalid write key: stop for the process lifetime. */
    data class Stop(val reason: String) : Disposition

    /** Transient: exponential backoff. */
    data object RetryLater : Disposition

    companion object {
        fun from(r: TransportResponse): Disposition = when (r.status) {
            202 -> Accepted(((Json.parseObjectOrNull(r.bodyText)?.get("rejected")) as? Number)?.toInt() ?: 0)
            400 -> fromBadRequest(r)
            401 -> Stop("invalid write key")
            403 -> Pause(3600.0, "project disabled")
            413 -> Split
            429 -> Pause(r.retryAfterSeconds ?: 300.0, "rate limited / quota exceeded")
            else -> RetryLater
        }

        @Suppress("UNCHECKED_CAST")
        private fun fromBadRequest(r: TransportResponse): Disposition {
            val err = Json.parseObjectOrNull(r.bodyText) ?: return DropBatch("400")
            val code = err["error"] as? String ?: ""
            val message = err["message"] as? String ?: ""
            if (code == "privacy_violation") return DropBatch(message)
            val details = (err["details"] as? List<Any?>).orEmpty().mapNotNull { it as? Map<String, Any?> }
            val paths = details.mapNotNull { it["path"] as? String }
            // Zod paths look like `events.3.properties.foo`.
            val indices = paths.mapNotNull { p ->
                val parts = p.split('.')
                if (parts.size >= 2 && parts[0] == "events") parts[1].toIntOrNull() else null
            }.toSet()
            // Details that are not about a specific event (envelope/identity/environment) mean the whole batch is wrong.
            val envelopeProblem = paths.any { !it.startsWith("events.") }
            if (indices.isNotEmpty() && !envelopeProblem) return DropEvents(indices)
            return DropBatch("$code: $message")
        }
    }
}

/** Exponential backoff with jitter: 2s, 4s, 8s ... capped at 5 min. [attempt] is 1 for the first failure. */
internal object Backoff {
    fun baseSeconds(attempt: Int): Double = min(300.0, 2.0 * 2.0.pow(maxOf(0, attempt - 1)))

    fun delaySeconds(attempt: Int, random: Random = Random.Default): Double =
        (0.5 + random.nextDouble() * 0.5) * baseSeconds(attempt)
}

internal object Batcher {
    /**
     * The longest prefix of [queue] that fits the contract: <= 100 events and <= 256 KB serialized
     * (envelope reserve ~1 KB). Always returns at least one event when the queue is non-empty, so a
     * single oversized event is sent alone and rejected by the server rather than blocking the queue.
     */
    fun nextBatch(queue: List<Event>, maxEvents: Int = Limits.BATCH_MAX_EVENTS, maxBytes: Int = Limits.BATCH_MAX_BYTES): List<Event> {
        val out = ArrayList<Event>()
        var bytes = 1024
        for (e in queue.take(maxEvents)) {
            val size = Json.writeBytes(e.toJson()).size
            if (out.isNotEmpty() && bytes + size + 1 > maxBytes) break
            out.add(e)
            bytes += size + 1
        }
        return out
    }
}
