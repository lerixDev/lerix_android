package com.lerix.sdk.analytics

/** Persisted session state. [foregroundAt] is set while the app is in the foreground. */
internal data class LerixSessionState(
    val sessionId: String? = null,
    val startedAt: Long = 0,
    val foregroundAt: Long? = null,
    val activeMs: Long = 0,
    val lastActiveAt: Long = 0,
    val backgroundAt: Long? = null,
)

internal data class LerixSessionEvent(
    val name: String,
    val sessionId: String,
    val ts: Long,
    val props: Map<String, Any?>,
)

/**
 * Session rules (contract §3.2), pure so they can be unit tested:
 * - A session starts on a cold start, or on a foreground after >= 30 min in
 *   the background. A cold start within 30 min of the last background resumes
 *   the persisted session (the id survives the process within the window).
 * - `$session_end {duration}` (foreground seconds) is emitted on every
 *   background. A resumed session that goes to the background again sends a
 *   later `$session_end` with the cumulative duration.
 * - A session still in the foreground at a cold start belonged to a killed
 *   process: its `$session_end` is emitted then, timed at its last activity.
 */
internal class LerixSessionTracker(
    private val newId: () -> String,
    private val timeoutMs: Long = SESSION_TIMEOUT_MS,
) {
    fun onForeground(
        state: LerixSessionState,
        now: Long,
        coldStart: Boolean,
    ): Pair<LerixSessionState, List<LerixSessionEvent>> {
        val events = mutableListOf<LerixSessionEvent>()
        val id = state.sessionId
        if (id != null && state.foregroundAt != null) {
            if (!coldStart) return state.copy(lastActiveAt = now) to events
            // Killed while in the foreground (crash, force stop).
            val end = maxOf(state.lastActiveAt, state.foregroundAt)
            val duration = state.activeMs + (end - state.foregroundAt).coerceAtLeast(0)
            events += LerixSessionEvent(SESSION_END, id, end, mapOf("duration" to duration / 1000))
        } else if (id != null && state.backgroundAt != null && now - state.backgroundAt < timeoutMs) {
            return state.copy(foregroundAt = now, backgroundAt = null, lastActiveAt = now) to events
        }
        val newSession = newId()
        events += LerixSessionEvent(SESSION_START, newSession, now, mapOf("coldStart" to coldStart))
        return LerixSessionState(
            sessionId = newSession,
            startedAt = now,
            foregroundAt = now,
            activeMs = 0,
            lastActiveAt = now,
            backgroundAt = null,
        ) to events
    }

    fun onBackground(state: LerixSessionState, now: Long): Pair<LerixSessionState, List<LerixSessionEvent>> {
        val id = state.sessionId ?: return state to emptyList()
        val foregroundAt = state.foregroundAt ?: return state to emptyList()
        val activeMs = state.activeMs + (now - foregroundAt).coerceAtLeast(0)
        val next = state.copy(foregroundAt = null, activeMs = activeMs, lastActiveAt = now, backgroundAt = now)
        return next to listOf(LerixSessionEvent(SESSION_END, id, now, mapOf("duration" to activeMs / 1000)))
    }

    /** The id to stamp on an event at [now], or null when there is no live session. */
    fun currentId(state: LerixSessionState, now: Long): String? {
        val id = state.sessionId ?: return null
        if (state.foregroundAt != null) return id
        val bg = state.backgroundAt ?: return null
        return if (now - bg < timeoutMs) id else null
    }

    companion object {
        const val SESSION_TIMEOUT_MS = 30 * 60_000L
        const val SESSION_START = "\$session_start"
        const val SESSION_END = "\$session_end"

        /**
         * Fixed eventId for a session's `$session_end` (same as iOS): re-sent with
         * a longer duration when a resumed session backgrounds again, and the
         * backend keeps the latest copy.
         */
        fun endEventId(sessionId: String): String = "end_$sessionId"
    }
}
