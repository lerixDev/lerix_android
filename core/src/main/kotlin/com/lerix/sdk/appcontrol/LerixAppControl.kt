package com.lerix.sdk.appcontrol

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.lerix.sdk.LerixApiException
import com.lerix.sdk.LerixBackend
import com.lerix.sdk.LerixCrashReporter
import com.lerix.sdk.LerixInit
import com.lerix.sdk.LerixRoute
import com.lerix.sdk.analytics.LerixAnalytics
import com.lerix.sdk.core.LerixLifecycle
import com.lerix.sdk.core.LerixLog
import com.lerix.sdk.core.LerixRuntime
import com.lerix.sdk.feedback.LerixFeedbackOutbox
import com.lerix.sdk.module.LerixModules
import com.lerix.sdk.surveys.LerixSurveys
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Release Control (`Lerix.appControl`): update prompts, forced updates,
 * maintenance mode and the vendor kill switch, driven by
 * `GET /plugin/app-config` (contract §1–§2).
 *
 * Collect [state] (or [addListener]) to draw your own UI with
 * `LerixAppControlOptions(ui = false)`; [openStore], [later] and [skip] act on
 * the visible update and record the funnel events. Nothing here throws.
 */
object LerixAppControl {
    private const val CACHE_KEY = "appctl_cache"
    private const val LATER_KEY = "appctl_later_key"
    private const val LATER_UNTIL = "appctl_later_until"
    private const val SKIP_KEY = "appctl_skip_key"
    private const val APPLIED_ETAG = "appctl_applied_etag"
    private const val POLL_MAX_TTL_SECONDS = 60
    private const val PUSH_FETCH_TIMEOUT_MS = 10_000L

    private val _state = MutableStateFlow(LerixAppControlState.EMPTY)

    /** The current state (contract §2.3), filtered by time, "Later" and "Skip". */
    val state: StateFlow<LerixAppControlState> = _state.asStateFlow()

    /** Shorthand for `state.value`. */
    val currentState: LerixAppControlState get() = _state.value

    @Volatile internal var options = LerixAppControlOptions()
        private set

    private val listeners = CopyOnWriteArrayList<LerixAppControlListener>()
    private val maintenanceShown: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val lock = Any()
    private val fetchMutex = Mutex()

    @Volatile private var started = false
    @Volatile private var loaded = false
    @Volatile private var cache: LerixAppControlCache? = null
    @Volatile private var lastFetchDoneAt = 0L
    @Volatile private var disabledModules: Set<String> = emptySet()
    @Volatile private var transitionJob: Job? = null
    @Volatile private var pollJob: Job? = null
    @Volatile private var deferredInit: (() -> Unit)? = null

    // ---- public API ----------------------------------------------------------------------

    /** Calls [listener] on the main thread on every change (not with the current value; read [currentState]). */
    fun addListener(listener: LerixAppControlListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: LerixAppControlListener) {
        listeners.remove(listener)
    }

    /** Fetches `app-config` now (ignoring `ttlSeconds`) and returns the new state. Offline → the cached state. */
    suspend fun refresh(): LerixAppControlState {
        if (!started || !options.enabled) return _state.value
        fetch(force = true)
        return _state.value
    }

    /** Callback flavour of [refresh] for Java / non-coroutine callers; [callback] runs on the main thread. */
    fun refresh(callback: (LerixAppControlState) -> Unit) {
        if (!LerixRuntime.initialized) {
            main.post { callback(_state.value) }
            return
        }
        LerixRuntime.scope.launch {
            val s = refresh()
            main.post { callback(s) }
        }
    }

    /**
     * Opens the store for the visible update ("Update"). For a required update
     * with `usePlayInAppUpdate` on a Play-installed app it starts a Play
     * IMMEDIATE update from [activity] (or the current activity). Returns false
     * when there is nothing to open.
     */
    fun openStore(activity: Activity? = null): Boolean = guard(false) {
        val update = _state.value.update ?: return@guard false
        trackAction("update", update)
        val host = activity ?: LerixAppControlUi.foregroundActivity()
        if (update.isRequired && options.usePlayInAppUpdate && host != null && LerixPlayUpdate.canUse(host)) {
            val url = update.storeUrl
            LerixPlayUpdate.startImmediate(host) { if (url != null) openUrl(host, url) }
            return@guard true
        }
        val url = update.storeUrl ?: return@guard false
        openUrl(host ?: LerixRuntime.context, url)
    }

    /** "Later": hides the visible available/recommended update for its `remindAfterHours`. */
    fun later() {
        guard(Unit) {
            val update = _state.value.update ?: return@guard
            if (update.isRequired) return@guard
            trackAction("later", update)
            val store = LerixRuntime.store
            store.putString(LATER_KEY, LerixAppControlLogic.promptKey(update))
            store.putLong(LATER_UNTIL, System.currentTimeMillis() + update.remindAfterHours * 3_600_000L)
            reevaluate()
        }
    }

    /** "Skip this version": hides the visible `available` update until `latestVersion` changes. No-op otherwise. */
    fun skip() {
        guard(Unit) {
            val update = _state.value.update ?: return@guard
            if (update.state != LerixUpdateState.AVAILABLE || !update.allowSkip) return@guard
            trackAction("skip", update)
            LerixRuntime.store.putString(SKIP_KEY, LerixAppControlLogic.promptKey(update))
            reevaluate()
        }
    }

    // ---- lifecycle (internal) -------------------------------------------------------------

    internal fun start(options: LerixAppControlOptions) {
        guard(Unit) {
            this.options = options
            if (!LerixRuntime.initialized) return@guard
            ensureLoaded()
            if (started) {
                reevaluate()
                return@guard
            }
            started = true
            // Apply the persisted response before any network (contract §2.1).
            reevaluate()
            if (!options.enabled) return@guard
            LerixLifecycle.markInternal(LerixAppControlActivity::class.java)
            LerixLifecycle.addListener(lifecycleListener)
            LerixRuntime.scope.launch { fetch(force = true) }
        }
    }

    /** Silent push `{action: "config_refresh"}` (§2.1): fetch now. Runs on the FCM worker thread. */
    internal fun onConfigRefreshPush() {
        guard(Unit) {
            if (!started || !options.enabled) return@guard
            // Blocking so the fetch completes before FCM may let the process die.
            runBlocking { withTimeoutOrNull(PUSH_FETCH_TIMEOUT_MS) { fetch(force = true) } }
        }
    }

    /**
     * Lerix.initialize found every module disabled: [rerun] runs the init
     * sequence (ping, register, pending crash) once the switch lifts.
     */
    internal fun deferInit(rerun: () -> Unit) {
        deferredInit = rerun
    }

    // ---- E6 gate --------------------------------------------------------------------------

    /** True while the vendor kill switch disables [module] (or `all`). Cheap; never throws. */
    internal fun isModuleDisabled(module: String): Boolean = guard(false) {
        val c = currentCache() ?: return@guard false
        LerixAppControlLogic.isModuleBlocked(c.config.sdk, System.currentTimeMillis() + c.clockOffsetMs, module)
    }

    /** Throws `SDK_DISABLED` when [route] is switched off by `sdk.disabled` (every path except app-config). */
    internal fun checkNetworkAllowed(route: LerixRoute) {
        val blocked = guard(false) {
            val c = currentCache() ?: return@guard false
            LerixAppControlLogic.isPathBlocked(c.config.sdk, System.currentTimeMillis() + c.clockOffsetMs, route.path)
        }
        if (blocked) {
            LerixLog.debug("${route.path} skipped: disabled by Lerix (sdk.disabled)")
            throw LerixApiException("SDK_DISABLED", "Disabled by Lerix until the kill switch is lifted")
        }
    }

    // ---- fetching -------------------------------------------------------------------------

    private suspend fun fetch(force: Boolean) {
        val requestedAt = System.currentTimeMillis()
        fetchMutex.withLock {
            // A fetch that finished while this one waited already answered it.
            if (lastFetchDoneAt >= requestedAt) return@withLock
            if (!force) {
                val c = cache
                if (c != null && requestedAt - c.lastFetchAt < c.config.ttlSeconds * 1000L) return@withLock
            }
            doFetch()
            lastFetchDoneAt = System.currentTimeMillis()
        }
        // Also when the fetch was skipped: returning to the foreground within the ttl must restart the poll.
        ensurePolling()
    }

    private suspend fun doFetch() {
        val previous = cache
        val headers = HashMap<String, String>()
        runCatching { LerixInit.existingUserId() }.getOrNull()?.let { headers["app-user"] = it }
        previous?.etag?.let { headers["If-None-Match"] = it }
        val sentAt = System.currentTimeMillis()
        val response = try {
            LerixBackend.exchange(LerixRoute.APP_CONFIG, headers, query = query(), timeoutMs = 10_000)
        } catch (e: Exception) {
            LerixLog.debug("app-config failed: $e")
            null
        }
        val receivedAt = System.currentTimeMillis()
        if (response != null && response.status != 304 && response.status !in 200..299) {
            LerixLog.debug("app-config → ${response.status} ${response.text.take(200)}")
        }
        val (appVersion, build) = runningApp()
        val next = LerixAppControlLogic.applyResponse(
            previous, response?.status, response?.text, sentAt, receivedAt,
            appVersion, build, LerixRuntime.SDK_VERSION,
        )
        if (next === previous) return
        synchronized(lock) { cache = next }
        next?.let { LerixRuntime.store.putString(CACHE_KEY, it.toJson()) }
        if (response != null && response.status in 200..299 && next != null) {
            val etag = next.etag
            if (etag != null && etag != LerixRuntime.store.getString(APPLIED_ETAG)) {
                LerixRuntime.store.putString(APPLIED_ETAG, etag)
                track("\$config_applied", mapOf("etag" to etag))
            }
        }
        reevaluate()
    }

    private fun query(): String {
        val app = LerixRuntime.app()
        val params = linkedMapOf<String, String?>(
            "platform" to "android",
            "appVersion" to app.version,
            "build" to runningApp().second,
            "appId" to (app.packageName ?: LerixRuntime.context.packageName),
            "environment" to LerixRuntime.environment,
            "locale" to runCatching { LerixRuntime.locale().toLanguageTag() }.getOrNull(),
            "sdk" to LerixRuntime.SDK_NAME,
            "sdkVersion" to LerixRuntime.SDK_VERSION,
            "installId" to runCatching { LerixRuntime.installId() }.getOrNull(),
        )
        return params.entries
            .filter { !it.value.isNullOrEmpty() }
            .joinToString("&") { "${enc(it.key)}=${enc(it.value!!)}" }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    /** `versionName` and integer `versionCode` of the running app (contract §1.1). */
    private fun runningApp(): Pair<String?, String?> {
        val app = LerixRuntime.app()
        return app.version to app.buildNo?.takeIf { it.toLongOrNull() != null }
    }

    private fun ensurePolling() {
        if (!options.enabled || !LerixLifecycle.inForeground) return
        val ttl = cache?.config?.ttlSeconds ?: return
        if (ttl > POLL_MAX_TTL_SECONDS) return
        if (pollJob?.isActive == true) return
        pollJob = LerixRuntime.scope.launch {
            while (isActive && LerixLifecycle.inForeground) {
                val current = cache?.config?.ttlSeconds ?: break
                if (current > POLL_MAX_TTL_SECONDS) break
                delay(current * 1000L)
                if (LerixLifecycle.inForeground) fetch(force = false)
            }
        }
    }

    private val lifecycleListener = object : LerixLifecycle.Listener {
        override fun onForeground() {
            guard(Unit) {
                if (!options.enabled) return@guard
                reevaluate()
                LerixRuntime.scope.launch { fetch(force = false) }
            }
        }

        override fun onBackground() {
            pollJob?.cancel()
            pollJob = null
        }

        override fun onActivityResumed(activity: Activity) {
            guard(Unit) { LerixAppControlUi.onActivityResumed(activity, _state.value) }
        }

        override fun onActivityPaused(activity: Activity) {
            guard(Unit) { LerixAppControlUi.onActivityPaused(activity) }
        }
    }

    // ---- state ----------------------------------------------------------------------------

    private fun currentCache(): LerixAppControlCache? {
        if (!loaded) {
            if (!LerixRuntime.initialized) return null
            ensureLoaded()
        }
        return cache
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val (appVersion, build) = runningApp()
            cache = LerixAppControlLogic.forRunningApp(
                LerixAppControlCache.fromJson(LerixRuntime.store.getString(CACHE_KEY)),
                appVersion,
                build,
                LerixRuntime.SDK_VERSION,
            )
            disabledModules = cache?.let {
                LerixAppControlLogic.disabledModules(it.config.sdk, System.currentTimeMillis() + it.clockOffsetMs)
            } ?: emptySet()
            loaded = true
        }
    }

    private fun prefs(): LerixAppControlPrefs {
        val store = LerixRuntime.store
        return LerixAppControlPrefs(
            laterKey = store.getString(LATER_KEY),
            laterUntil = store.getLong(LATER_UNTIL),
            skipKey = store.getString(SKIP_KEY),
        )
    }

    /** Re-evaluates the cache against the clock and publishes the result. */
    internal fun reevaluate() {
        guard(Unit) {
            if (!LerixRuntime.initialized) return@guard
            ensureLoaded()
            val now = System.currentTimeMillis()
            val (previous, next, lifted) = synchronized(lock) {
                val evaluation = LerixAppControlLogic.evaluate(cache, now, prefs(), options.enabled)
                val previous = _state.value
                _state.value = evaluation.state
                scheduleTransition(evaluation.nextChangeAt, now)
                val nowDisabled = evaluation.state.sdk?.disabled ?: emptySet()
                val lifted = disabledModules - nowDisabled
                disabledModules = nowDisabled
                Triple(previous, evaluation.state, lifted)
            }
            if (lifted.isNotEmpty()) onSdkLifted(lifted)
            next.maintenance?.let { markMaintenanceShown(it) }
            if (previous != next) {
                main.post {
                    listeners.forEach { l ->
                        try {
                            l.onChange(next)
                        } catch (t: Throwable) {
                            LerixLog.debug("appControl listener threw: $t")
                        }
                    }
                    guard(Unit) { LerixAppControlUi.onState(next) }
                }
            }
        }
    }

    private fun scheduleTransition(at: Long?, now: Long) {
        transitionJob?.cancel()
        transitionJob = null
        if (at == null) return
        // +50 ms so the boundary (endsAt, until) has really passed when we re-evaluate.
        val wait = (at - now + 50).coerceAtLeast(50)
        transitionJob = LerixRuntime.scope.launch {
            delay(wait)
            reevaluate()
        }
    }

    /** The kill switch lifted [modules]: send what stayed queued (contract §2.5). */
    private fun onSdkLifted(modules: Set<String>) {
        LerixLog.debug("sdk.disabled lifted for $modules")
        val all = LerixAppControlLogic.MODULE_ALL in modules
        if (all) {
            deferredInit?.let { rerun ->
                deferredInit = null
                rerun()
            }
        }
        LerixRuntime.scope.launch {
            runCatching { LerixFeedbackOutbox.process() }
            runCatching { LerixSurveys.process() }
            runCatching { LerixAnalytics.flush() }
            if (all || "errors" in modules) runCatching { LerixCrashReporter.reportPendingCrashIfAny() }
            // lerix-messaging registers its token again (it listens through LerixModuleHost).
            if (all || "push" in modules) LerixModules.notifyLifted(if (all) listOf("all") else listOf("push"))
        }
    }

    // ---- events / helpers -----------------------------------------------------------------

    internal fun trackShown(update: LerixAppUpdate) {
        track(
            "\$update_prompt_shown",
            mapOf(
                "state" to update.state.raw,
                "reason" to update.reason,
                "ruleId" to update.ruleId,
                "latestVersion" to update.latestVersion,
            ),
        )
    }

    /** `$maintenance_shown` once per app session per window, when it becomes active in [state] (even with `ui = false`). */
    private fun markMaintenanceShown(maintenance: LerixMaintenance) {
        val session = runCatching { LerixAnalytics.currentSessionId() }.getOrNull() ?: "process"
        if (maintenanceShown.add("$session|${maintenance.id}")) {
            track("\$maintenance_shown", mapOf("maintenanceId" to maintenance.id))
        }
    }

    private fun trackAction(action: String, update: LerixAppUpdate) {
        track(
            "\$update_prompt_action",
            mapOf("action" to action, "state" to update.state.raw, "ruleId" to update.ruleId),
        )
    }

    private fun track(name: String, props: Map<String, Any?>) {
        if (!options.enabled) return
        runCatching { LerixAnalytics.trackReserved(name, props) }
    }

    private fun openUrl(context: Context, url: String): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        LerixLog.debug("no app can open $url")
        false
    } catch (t: Throwable) {
        LerixLog.debug("openStore failed: $t")
        false
    }

    private inline fun <T> guard(fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        LerixLog.debug("appControl: $t")
        fallback
    }
}
