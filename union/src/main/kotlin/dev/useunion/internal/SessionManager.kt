package dev.useunion.internal

internal data class SessionSnapshot(val sessionId: String, val startedAt: Long, val lastActivityAt: Long)

/**
 * Session rules mirror the server (`SESSION_TIMEOUT_MS`): a session ends after 30 minutes without
 * activity. A snapshot is persisted so a kill-and-relaunch inside the window continues the session;
 * the server's alarm is authoritative if `$session_end` never arrives.
 */
internal class SessionManager(private val clock: Clock, private val store: KeyValueStore) {
    var current: SessionSnapshot? = null
        private set

    init {
        store.string(KEY)?.let { raw ->
            Json.parseObjectOrNull(raw)?.let { m ->
                val id = m["session_id"] as? String
                val started = (m["started_at"] as? Number)?.toLong()
                val last = (m["last_activity_at"] as? Number)?.toLong()
                if (id != null && started != null && last != null) current = SessionSnapshot(id, started, last)
            }
        }
    }

    sealed interface Transition {
        data class Continued(val sessionId: String) : Transition

        /** [ended] is null on a cold start with no prior snapshot. */
        data class Rotated(val ended: SessionSnapshot?, val started: SessionSnapshot) : Transition
    }

    /** Ensures a live session for activity happening now; rotates when the timeout elapsed. */
    fun touch(nowMs: Long = clock.nowMs()): Transition {
        val cur = current
        if (cur != null && nowMs - cur.lastActivityAt < TIMEOUT_MS) {
            current = cur.copy(lastActivityAt = nowMs)
            persist()
            return Transition.Continued(cur.sessionId)
        }
        return rotateFrom(cur, nowMs)
    }

    /** Forced rotation (reset()). */
    fun rotate(nowMs: Long = clock.nowMs()): Transition = rotateFrom(current, nowMs)

    private fun rotateFrom(ended: SessionSnapshot?, nowMs: Long): Transition.Rotated {
        val started = SessionSnapshot(UUIDv7.generate(nowMs), nowMs, nowMs)
        current = started
        persist()
        return Transition.Rotated(ended, started)
    }

    fun clear() {
        current = null
        store.set(KEY, null)
    }

    private fun persist() {
        val cur = current ?: return
        store.set(KEY, Json.write(linkedMapOf("session_id" to cur.sessionId, "started_at" to cur.startedAt, "last_activity_at" to cur.lastActivityAt)))
    }

    companion object {
        const val TIMEOUT_MS: Long = 30 * 60 * 1000
        private const val KEY = "session"
    }
}
