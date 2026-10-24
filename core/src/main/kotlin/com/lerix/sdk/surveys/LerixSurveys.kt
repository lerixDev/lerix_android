package com.lerix.sdk.surveys

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.lerix.sdk.LerixRoute
import com.lerix.sdk.core.LerixBackoff
import com.lerix.sdk.core.LerixFeature
import com.lerix.sdk.core.LerixFileQueue
import com.lerix.sdk.core.LerixLaunch
import com.lerix.sdk.core.LerixLog
import com.lerix.sdk.core.LerixRetryDecision
import com.lerix.sdk.core.LerixRuntime
import com.lerix.sdk.core.LerixSender
import com.lerix.sdk.core.LerixTime
import com.lerix.sdk.ui.LerixUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** Surveys (NPS and rating/CSAT): `Lerix.surveys`. */
object LerixSurveys {
    private const val CONFIG_KEY = "surveys_config"
    private const val CONFIG_AT_KEY = "surveys_config_at"
    private const val CONFIG_SIG_KEY = "surveys_config_sig"
    private const val CONFIG_TTL_MS = 24 * 3_600_000L

    private val queue: LerixFileQueue by lazy { LerixFileQueue(LerixRuntime.outboxDir("surveys")) }
    private val mutex = Mutex()
    private val backoff = LerixBackoff()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var retryJob: Job? = null
    @Volatile private var showing = false

    /**
     * Shows the NPS bottom sheet over [activity] when the active NPS survey's
     * rules allow it (min app starts, initial delay, sampling, frequency,
     * dismiss cooldown). [force] skips every check (for testing). [custom] is
     * stored with the response. Returns true when the sheet was shown. Never throws.
     */
    suspend fun maybeShowNps(activity: Activity, force: Boolean = false, custom: Map<String, Any?>? = null): Boolean =
        present(activity, "nps", null, force, custom)

    /** Callback variant of [maybeShowNps] for code without coroutines; [onResult] runs on the main thread. */
    fun maybeShowNps(
        activity: Activity,
        force: Boolean = false,
        custom: Map<String, Any?>? = null,
        onResult: (Boolean) -> Unit,
    ) {
        LerixRuntime.scope.launch {
            val shown = maybeShowNps(activity, force, custom)
            main.post { onResult(shown) }
        }
    }

    /**
     * Shows the rating survey with [key] (set in the dashboard) over [activity]
     * when it is active, matches this app and is due: at most once per
     * `frequencyDays` (0 = no limit) and not within `dismissCooldownDays` of a
     * dismiss. [force] skips every check (e.g. a "Rate us" button). [custom] is
     * stored with the response. Returns true when the sheet was shown; in
     * debug mode the log says why not. Never throws.
     */
    suspend fun showRating(
        activity: Activity,
        key: String,
        force: Boolean = false,
        custom: Map<String, Any?>? = null,
    ): Boolean {
        if (key.isEmpty()) {
            LerixLog.debug("showRating needs the survey key set in the dashboard")
            return false
        }
        return present(activity, "rating", key, force, custom)
    }

    /** Callback variant of [showRating]; [onResult] runs on the main thread. */
    fun showRating(
        activity: Activity,
        key: String,
        force: Boolean = false,
        custom: Map<String, Any?>? = null,
        onResult: (Boolean) -> Unit,
    ) {
        LerixRuntime.scope.launch {
            val shown = showRating(activity, key, force, custom)
            main.post { onResult(shown) }
        }
    }

    /**
     * The NPS survey as a prompt for your own UI when this install is due (or
     * always with [force]), else null. Records nothing until you call the
     * prompt's methods. Never throws.
     */
    suspend fun dueNps(force: Boolean = false, custom: Map<String, Any?>? = null): LerixSurveyPrompt? =
        prompt("nps", null, force, custom)

    /** Callback variant of [dueNps]; [onResult] runs on the main thread. */
    fun dueNps(force: Boolean = false, custom: Map<String, Any?>? = null, onResult: (LerixSurveyPrompt?) -> Unit) {
        LerixRuntime.scope.launch {
            val prompt = dueNps(force, custom)
            main.post { onResult(prompt) }
        }
    }

    /** The rating survey with [key] as a prompt for your own UI when it is due, else null. Never throws. */
    suspend fun dueRating(key: String, force: Boolean = false, custom: Map<String, Any?>? = null): LerixSurveyPrompt? =
        if (key.isEmpty()) null else prompt("rating", key, force, custom)

    /** Callback variant of [dueRating]; [onResult] runs on the main thread. */
    fun dueRating(
        key: String,
        force: Boolean = false,
        custom: Map<String, Any?>? = null,
        onResult: (LerixSurveyPrompt?) -> Unit,
    ) {
        LerixRuntime.scope.launch {
            val prompt = dueRating(key, force, custom)
            main.post { onResult(prompt) }
        }
    }

    /**
     * The survey to show, or null with the reason logged in debug mode: the
     * active NPS survey, or the active rating with [key], if this install is
     * due (contract §2.2, plan §4.2). [force] skips every rule.
     */
    private suspend fun due(type: String, key: String?, force: Boolean): LerixSurvey? {
        val label = if (type == "nps") "NPS" else "Rating \"$key\""
        val survey = loadSurveys().firstOrNull { it.type == type && (type == "nps" || it.key == key) }
        if (survey == null) {
            LerixLog.debug("$label: no active survey for this platform/version/environment")
            return null
        }
        if (force) return survey
        if (LerixRuntime.hints.isDisabled(LerixFeature.SURVEYS)) {
            LerixLog.debug("$label skipped: surveys are paused by the server (SDK_DISABLED)")
            return null
        }
        val now = System.currentTimeMillis()
        val state = localState(survey.id, now)
        val result = LerixSurveyEligibility.evaluate(
            survey.id, LerixRuntime.installId(), survey.rules, state, now, rating = type == "rating",
        )
        if (!result.eligible) {
            LerixLog.debug("$label not shown: ${result.failing.joinToString("; ")}. nextDue=${LerixTime.iso(result.nextDue)}")
            return null
        }
        return survey
    }

    private suspend fun present(activity: Activity, type: String, key: String?, force: Boolean, custom: Map<String, Any?>?): Boolean {
        if (!LerixRuntime.initialized || showing) return false
        return try {
            val survey = due(type, key, force) ?: return false
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed || showing) return@withContext false
                showing = true
                try {
                    show(activity, LerixSurveyRun(sink, survey, LerixUi.language(), custom))
                    true
                } catch (e: Exception) {
                    showing = false
                    throw e
                }
            }
        } catch (e: Exception) {
            LerixLog.debug("showing the survey failed: $e")
            false
        }
    }

    private suspend fun prompt(type: String, key: String?, force: Boolean, custom: Map<String, Any?>?): LerixSurveyPrompt? {
        if (!LerixRuntime.initialized) return null
        return try {
            due(type, key, force)?.let { LerixSurveyRun(sink, it, LerixUi.language(), custom).toPrompt() }
        } catch (e: Exception) {
            LerixLog.debug("dueSurvey failed: $e")
            null
        }
    }

    private fun show(activity: Activity, run: LerixSurveyRun) {
        val onClosed = { showing = false }
        if (run.survey.type == "nps") {
            LerixNpsDialog(activity, run, onClosed).show()
        } else {
            LerixRatingDialog(activity, run, onClosed).show()
        }
        run.shown()
    }

    private val sink = object : LerixSurveySink {
        override fun saveState(surveyId: String, what: String, at: Long) = putState(surveyId, what, at)
        override fun record(clientId: String, surveyId: String, body: JSONObject) = this@LerixSurveys.record(clientId, surveyId, body)
        override fun flush() = sendAfterWrites()
        override fun breadcrumb(message: String, data: Map<String, Any?>) = LerixRuntime.breadcrumbs.add(message, "ui", data)
    }

    // ---- local state (contract §2.2) ----

    private fun localState(surveyId: String, now: Long): LerixSurveyLocalState {
        val store = LerixRuntime.store
        return LerixSurveyLocalState(
            firstStartAt = LerixLaunch.firstStartAt(store, now),
            appStarts = LerixLaunch.appStarts(store),
            lastShownAt = store.getLong("survey_${surveyId}_shown"),
            lastAnsweredAt = store.getLong("survey_${surveyId}_answered"),
            lastDismissedAt = store.getLong("survey_${surveyId}_dismissed"),
        )
    }

    private fun putState(surveyId: String, what: String, at: Long) {
        LerixRuntime.store.putLong("survey_${surveyId}_$what", at)
    }

    // ---- config ----

    private suspend fun loadSurveys(): List<LerixSurvey> {
        val store = LerixRuntime.store
        val app = LerixRuntime.app()
        val signature = "${app.version}|${LerixRuntime.environment}"
        val cached = store.getString(CONFIG_KEY)
        val age = System.currentTimeMillis() - (store.getLong(CONFIG_AT_KEY) ?: 0)
        val fresh = cached != null && age in 0 until CONFIG_TTL_MS && store.getString(CONFIG_SIG_KEY) == signature
        val raw = if (fresh) cached else fetchConfig(app.version ?: "0.0.0", signature) ?: cached
        return parse(raw)
    }

    private suspend fun fetchConfig(appVersion: String, signature: String): String? {
        val userId = LerixRuntime.ensureUser() ?: return null
        val query = "platform=android&appVersion=${enc(appVersion)}&environment=${enc(LerixRuntime.environment)}"
        val result = LerixSender.send(LerixFeature.SURVEYS, LerixRoute.SURVEYS_CONFIG, userId, null, query = query)
        if (result.decision != LerixRetryDecision.SUCCESS) return null
        val json = result.response?.json ?: return null
        val text = json.toString()
        LerixRuntime.store.putString(CONFIG_KEY, text)
        LerixRuntime.store.putLong(CONFIG_AT_KEY, System.currentTimeMillis())
        LerixRuntime.store.putString(CONFIG_SIG_KEY, signature)
        return text
    }

    private fun parse(raw: String?): List<LerixSurvey> = LerixSurvey.listFromJson(raw)

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    // ---- outbox: one item per showing (clientId), merged and upserted ----

    /** Merges [patch] into the queued body for [clientId] (one outbox item per showing). */
    private fun record(clientId: String, surveyId: String, patch: JSONObject) {
        LerixRuntime.scope.launch(LerixRuntime.serial) {
            try {
                val existing = queue.read(clientId)?.let { runCatching { JSONObject(it) }.getOrNull() }
                val body = existing ?: baseBody(clientId, surveyId)
                patch.keys().forEach { body.put(it, patch.get(it)) }
                queue.put(clientId, body.toString())
            } catch (e: Exception) {
                LerixLog.debug("survey response write failed: $e")
            }
        }
    }

    private fun baseBody(clientId: String, surveyId: String): JSONObject {
        val app = LerixRuntime.app()
        return JSONObject().apply {
            put("clientId", clientId)
            put("surveyId", surveyId)
            put("platform", "android")
            put("appVersion", app.version ?: "0.0.0")
            put("environment", LerixRuntime.environment)
            put("locale", LerixRuntime.locale().toLanguageTag())
            put("custom", JSONObject())
        }
    }

    /** Runs [process] after the queued response writes on the serial dispatcher. */
    private fun sendAfterWrites() {
        LerixRuntime.scope.launch {
            withContext(LerixRuntime.serial) {}
            process()
        }
    }

    internal fun start() {
        LerixRuntime.scope.launch {
            delay(5_000)
            process()
        }
    }

    internal suspend fun process() {
        mutex.withLock {
            for (entry in queue.list()) {
                if (LerixRuntime.hints.isDisabled(LerixFeature.SURVEYS)) return
                if (!backoff.canAttempt()) {
                    scheduleRetry()
                    return
                }
                val text = queue.read(entry)
                if (text == null || runCatching { JSONObject(text) }.isFailure) {
                    queue.delete(entry.id)
                    continue
                }
                val userId = LerixRuntime.ensureUser()
                if (userId == null) {
                    backoff.onFailure()
                    scheduleRetry()
                    return
                }
                val result = LerixSender.send(LerixFeature.SURVEYS, LerixRoute.SURVEY_RESPONSE, userId, text.toByteArray(Charsets.UTF_8))
                when (result.decision) {
                    LerixRetryDecision.SUCCESS, LerixRetryDecision.DROP -> {
                        // Only delete if not updated meanwhile (an answer merged after this send).
                        withContext(LerixRuntime.serial) {
                            if (queue.read(entry.id) == text) queue.delete(entry.id)
                        }
                        backoff.onSuccess()
                    }
                    LerixRetryDecision.RETRY, LerixRetryDecision.REREGISTER -> {
                        backoff.onFailure()
                        scheduleRetry()
                        return
                    }
                }
            }
        }
    }

    private fun scheduleRetry() {
        val job = retryJob
        if (job != null && job.isActive) return
        retryJob = LerixRuntime.scope.launch {
            delay(backoff.delayMs().coerceAtLeast(2_000))
            retryJob = null
            process()
        }
    }
}
