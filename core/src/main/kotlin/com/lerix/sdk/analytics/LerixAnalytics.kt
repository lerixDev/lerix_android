package com.lerix.sdk.analytics

import com.lerix.sdk.LerixRoute
import com.lerix.sdk.core.LerixBackoff
import com.lerix.sdk.core.LerixFeature
import com.lerix.sdk.core.LerixFileQueue
import com.lerix.sdk.core.LerixIds
import com.lerix.sdk.core.LerixJson
import com.lerix.sdk.core.LerixLaunch
import com.lerix.sdk.core.LerixLog
import com.lerix.sdk.core.LerixRetryDecision
import com.lerix.sdk.core.LerixRuntime
import com.lerix.sdk.core.LerixSender
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Analytics options for [com.lerix.sdk.Lerix.initialize] (contract §7). */
data class LerixAnalyticsOptions(
    /** Set to false to record nothing (same as `Lerix.analytics.setEnabled(false)`, but not persisted). */
    val enabled: Boolean = true,
    /** `$session_start` / `$session_end` from the app lifecycle. */
    val autoSessions: Boolean = true,
    /** `$screen_view` for every resumed Activity (its class simple name). */
    val autoScreens: Boolean = true,
    /** Also emit `$screen_view` for resumed AndroidX Fragments (needs androidx.fragment). Off by default. */
    val trackFragments: Boolean = false,
)

/** Product analytics: `Lerix.analytics`. Nothing here throws to the caller. */
object LerixAnalytics {
    private const val ENABLED_KEY = "analytics_enabled"
    private const val MAX_BATCH = 100
    private const val MAX_BATCH_BYTES = 500 * 1024 // 512 KB request limit, minus the context
    private const val FLUSH_AT = 50
    private const val FIRST_FLUSH_MS = 5_000L
    private const val FLUSH_INTERVAL_MS = 30_000L
    private const val MAX_QUEUE_BYTES = 1024L * 1024
    private const val MAX_AGE_MS = 7 * 86_400_000L

    @Volatile private var options = LerixAnalyticsOptions()
    private val queue: LerixFileQueue by lazy { LerixFileQueue(LerixRuntime.outboxDir("events")) }
    private val tracker = LerixSessionTracker(LerixIds::newId)
    private val sessionLock = Any()
    private val flushMutex = Mutex()
    private val backoff = LerixBackoff()
    @Volatile private var pending = 0
    @Volatile private var flushJob: Job? = null
    @Volatile private var configured = false
    @Volatile private var currentScreen: String? = null

    /** Sends pending events now (ignores the retry backoff). */
    fun flush() {
        if (!configured) return
        LerixRuntime.scope.launch { flushInternal(force = true) }
    }

    /** Opt-out / consent switch. `false` stops recording and clears the local queue. Persisted. */
    fun setEnabled(enabled: Boolean) {
        if (!LerixRuntime.initialized) return
        LerixRuntime.store.putBoolean(ENABLED_KEY, enabled)
        if (!enabled) {
            flushJob?.cancel()
            LerixRuntime.scope.launch(LerixRuntime.serial) {
                queue.clear()
                pending = 0
            }
        }
    }

    fun isEnabled(): Boolean =
        configured && options.enabled && (LerixRuntime.store.getBoolean(ENABLED_KEY) ?: true)

    /** The current analytics session id, also sent on error reports. */
    fun currentSessionId(): String? {
        if (!configured) return null
        return synchronized(sessionLock) { tracker.currentId(loadSession(), System.currentTimeMillis()) }
    }

    // ---- internal -------------------------------------------------------------------------

    internal fun configure(options: LerixAnalyticsOptions) {
        this.options = options
        if (configured) return
        configured = true
        LerixRuntime.scope.launch(LerixRuntime.serial) {
            pending = queue.count()
        }
    }

    internal val trackFragments: Boolean get() = options.trackFragments
    internal val autoScreens: Boolean get() = options.autoScreens

    /** Public `Lerix.track`: reserved `$` names are rejected. */
    internal fun track(name: String, props: Map<String, Any?>?) {
        try {
            record(name, props, screen = currentScreen, allowReserved = false)
        } catch (t: Throwable) {
            LerixLog.debug("track failed: $t")
        }
    }

    /** The SDK's own `$` events (Release Control); skipped when analytics is disabled. */
    internal fun trackReserved(name: String, props: Map<String, Any?>?) {
        try {
            record(name, props, screen = currentScreen, allowReserved = true)
        } catch (t: Throwable) {
            LerixLog.debug("track failed: $t")
        }
    }

    internal fun screen(name: String, props: Map<String, Any?>?, automatic: Boolean) {
        try {
            val trimmed = name.trim().take(256)
            if (trimmed.isEmpty()) return
            val previous = currentScreen
            if (automatic && previous == trimmed) return
            currentScreen = trimmed
            LerixRuntime.breadcrumbs.add(
                if (previous != null) "$previous → $trimmed" else trimmed,
                "navigation",
                null,
            )
            val merged = LinkedHashMap<String, Any?>()
            merged["screen"] = trimmed
            if (previous != null) merged["previous"] = previous
            props?.forEach { (k, v) -> if (k != "screen" && k != "previous") merged[k] = v }
            record("\$screen_view", merged, screen = trimmed, allowReserved = true)
        } catch (t: Throwable) {
            LerixLog.debug("screen failed: $t")
        }
    }

    internal fun onForeground(coldStart: Boolean) {
        if (!configured) return
        val now = System.currentTimeMillis()
        val events = synchronized(sessionLock) {
            val (state, events) = tracker.onForeground(loadSession(), now, coldStart)
            saveSession(state)
            events
        }
        if (options.autoSessions) {
            events.forEach { record(it.name, it.props, null, true, it.ts, it.sessionId) }
        }
        if (coldStart) {
            // Counted here, not in initialize(): a background launch (FCM data message,
            // a service) is not an app start.
            LerixLaunch.record(
                LerixRuntime.store,
                now,
                LerixRuntime.app().version ?: "0.0.0",
                existingInstall = LerixRuntime.legacyInstall,
            )
        }
        emitPendingLaunchEvents()
        scheduleFlush(if (coldStart) FIRST_FLUSH_MS else FLUSH_INTERVAL_MS)
    }

    internal fun onBackground() {
        if (!configured) return
        val now = System.currentTimeMillis()
        val events = synchronized(sessionLock) {
            val (state, events) = tracker.onBackground(loadSession(), now)
            saveSession(state)
            events
        }
        if (options.autoSessions) {
            events.forEach { record(it.name, it.props, currentScreen, true, it.ts, it.sessionId) }
        }
        // Sent with the flush on background; after the queued writes on the serial dispatcher.
        LerixRuntime.scope.launch(LerixRuntime.serial) {
            LerixRuntime.scope.launch { flushInternal(force = false) }
        }
    }

    private fun emitPendingLaunchEvents() {
        val store = LerixRuntime.store
        if (store.getBoolean(LerixLaunch.PENDING_FIRST_OPEN) == true) {
            store.remove(LerixLaunch.PENDING_FIRST_OPEN)
            record("\$first_open", null, null, allowReserved = true)
        }
        val from = store.getString(LerixLaunch.PENDING_UPDATE_FROM)
        val to = store.getString(LerixLaunch.PENDING_UPDATE_TO)
        if (from != null && to != null) {
            store.remove(LerixLaunch.PENDING_UPDATE_FROM)
            store.remove(LerixLaunch.PENDING_UPDATE_TO)
            record("\$app_update", mapOf("from" to from, "to" to to), null, allowReserved = true)
        }
    }

    private fun record(
        name: String,
        props: Map<String, Any?>?,
        screen: String?,
        allowReserved: Boolean,
        ts: Long = System.currentTimeMillis(),
        sessionId: String? = null,
    ) {
        if (!isEnabled()) return
        if (!LerixEventValidator.isValidName(name, allowReserved)) {
            LerixLog.debug("event \"$name\" dropped: invalid name")
            return
        }
        val clean = LerixEventValidator.sanitizeProps(props) { LerixLog.debug("event \"$name\": $it") }
        val sid = sessionId ?: synchronized(sessionLock) {
            val state = loadSession()
            if (state.foregroundAt != null) saveSession(state.copy(lastActiveAt = ts))
            tracker.currentId(state, ts)
        }
        val eventId = if (name == LerixSessionTracker.SESSION_END && sid != null) {
            LerixSessionTracker.endEventId(sid) // the queue file is replaced, so only the latest is kept locally too
        } else {
            LerixIds.newId()
        }
        val json = JSONObject().apply {
            put("eventId", eventId)
            put("name", name)
            put("ts", ts)
            if (sid != null) put("sessionId", sid)
            put("props", LerixJson.objectOf(clean))
            if (screen != null) put("screen", screen)
        }
        LerixRuntime.scope.launch(LerixRuntime.serial) {
            try {
                queue.put(eventId, json.toString(), ts)
                pending += 1
                if (pending >= FLUSH_AT) {
                    LerixRuntime.scope.launch { flushInternal(force = false) }
                } else {
                    scheduleFlush(FLUSH_INTERVAL_MS)
                }
            } catch (e: Exception) {
                LerixLog.debug("event write failed: $e")
            }
        }
    }

    private fun scheduleFlush(delayMs: Long) {
        val job = flushJob
        if (job != null && job.isActive) return
        flushJob = LerixRuntime.scope.launch {
            delay(delayMs)
            flushInternal(force = false)
        }
    }

    private suspend fun flushInternal(force: Boolean) {
        if (!configured) return
        touchSession()
        flushMutex.withLock {
            var rounds = 0
            while (rounds++ < 20) {
                if (!isEnabled()) return
                if (LerixRuntime.hints.isDisabled(LerixFeature.ANALYTICS)) return
                if (!force && !backoff.canAttempt()) {
                    rescheduleAfterBackoff()
                    return
                }
                val now = System.currentTimeMillis()
                val dropped = queue.trim(MAX_QUEUE_BYTES, MAX_AGE_MS, now)
                if (dropped > 0) LerixLog.debug("dropped $dropped old events (local cap: 1 MB / 7 days)")
                val entries = queue.list()
                pending = entries.size
                if (entries.isEmpty()) return

                val userId = LerixRuntime.ensureUser()
                if (userId == null) {
                    backoff.onFailure()
                    rescheduleAfterBackoff()
                    return
                }

                val batchIds = mutableListOf<String>()
                val events = JSONArray()
                var bytes = 0
                for (entry in entries) {
                    if (batchIds.size >= MAX_BATCH) break
                    val text = queue.read(entry)
                    val event = text?.let { runCatching { JSONObject(it) }.getOrNull() }
                    if (event == null) {
                        queue.delete(entry.id)
                        continue
                    }
                    val size = text.toByteArray(Charsets.UTF_8).size
                    if (batchIds.isNotEmpty() && bytes + size > MAX_BATCH_BYTES) break
                    bytes += size
                    batchIds += entry.id
                    events.put(event)
                }
                if (batchIds.isEmpty()) continue

                val body = JSONObject().apply {
                    put("context", LerixRuntime.analyticsContext())
                    put("events", events)
                }
                val result = LerixSender.send(
                    LerixFeature.ANALYTICS,
                    LerixRoute.EVENTS,
                    userId,
                    body.toString().toByteArray(Charsets.UTF_8),
                )
                when (result.decision) {
                    LerixRetryDecision.SUCCESS -> {
                        // Rejected events are dropped, never retried (contract §3.1).
                        result.response?.json?.optJSONArray("rejected")?.let { rejected ->
                            for (i in 0 until rejected.length()) {
                                val r = rejected.optJSONObject(i) ?: continue
                                LerixLog.debug("event #${r.optInt("index")} rejected: ${r.optString("reason")}")
                            }
                        }
                        batchIds.forEach { queue.delete(it) }
                        backoff.onSuccess()
                    }
                    LerixRetryDecision.DROP -> {
                        batchIds.forEach { queue.delete(it) }
                        backoff.onSuccess()
                    }
                    LerixRetryDecision.RETRY, LerixRetryDecision.REREGISTER -> {
                        backoff.onFailure()
                        rescheduleAfterBackoff()
                        return
                    }
                }
            }
        }
    }

    private fun rescheduleAfterBackoff() {
        // Not cancelling the old job: this may run inside it. An extra flush is harmless.
        flushJob = LerixRuntime.scope.launch {
            delay(backoff.delayMs().coerceAtLeast(FIRST_FLUSH_MS))
            flushInternal(force = false)
        }
    }

    // ---- session persistence ----

    /** Keeps `lastActiveAt` fresh so a killed session gets a sensible duration. */
    private fun touchSession() {
        synchronized(sessionLock) {
            val state = loadSession()
            if (state.foregroundAt != null) saveSession(state.copy(lastActiveAt = System.currentTimeMillis()))
        }
    }

    private fun loadSession(): LerixSessionState {
        val s = LerixRuntime.store
        return LerixSessionState(
            sessionId = s.getString("session_id"),
            startedAt = s.getLong("session_started_at") ?: 0,
            foregroundAt = s.getLong("session_foreground_at"),
            activeMs = s.getLong("session_active_ms") ?: 0,
            lastActiveAt = s.getLong("session_last_active_at") ?: 0,
            backgroundAt = s.getLong("session_background_at"),
        )
    }

    private fun saveSession(state: LerixSessionState) {
        val s = LerixRuntime.store
        s.putString("session_id", state.sessionId)
        s.putLong("session_started_at", state.startedAt)
        s.putLong("session_foreground_at", state.foregroundAt)
        s.putLong("session_active_ms", state.activeMs)
        s.putLong("session_last_active_at", state.lastActiveAt)
        s.putLong("session_background_at", state.backgroundAt)
    }
}
