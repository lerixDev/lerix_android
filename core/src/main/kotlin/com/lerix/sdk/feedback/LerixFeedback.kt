package com.lerix.sdk.feedback

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import com.lerix.sdk.LerixRoute
import com.lerix.sdk.analytics.LerixAnalytics
import com.lerix.sdk.core.LerixFeature
import com.lerix.sdk.core.LerixIds
import com.lerix.sdk.core.LerixJson
import com.lerix.sdk.core.LerixLog
import com.lerix.sdk.core.LerixRetryDecision
import com.lerix.sdk.core.LerixRuntime
import com.lerix.sdk.core.LerixSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** In-app feedback: `Lerix.feedback`. */
object LerixFeedback {
    private const val CONFIG_KEY = "feedback_config"
    private const val CONFIG_AT_KEY = "feedback_config_at"
    private const val EMAIL_KEY = "feedback_email"
    private const val CONFIG_TTL_MS = 24 * 3_600_000L
    private const val CONFIG_TIMEOUT_MS = 2_500L
    private const val MAX_CUSTOM_KEYS = 50
    private const val MAX_CUSTOM_BYTES = 16 * 1024
    private const val MAX_ATTACHMENTS = 5
    private const val MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    private val main = Handler(Looper.getMainLooper())

    internal val userEmail: String?
        get() = if (LerixRuntime.initialized) LerixRuntime.store.getString(EMAIL_KEY) else null

    /**
     * Opens the feedback flow over [activity]: message → labels → screenshots
     * (with drawing) → email → review → sent. The current screen is captured
     * before the UI appears. [onResult] runs on the main thread when the flow
     * closes. Requires [com.lerix.sdk.Lerix.initialize].
     */
    fun show(
        activity: Activity,
        options: LerixFeedbackOptions = LerixFeedbackOptions(),
        onResult: ((LerixFeedbackResult) -> Unit)? = null,
    ) {
        if (!LerixRuntime.initialized) {
            LerixLog.debug("feedback.show called before Lerix.initialize")
            onResult?.let { cb -> main.post { cb(LerixFeedbackResult(false)) } }
            return
        }
        val run = { LerixFeedbackFlow.start(activity, options, onResult) }
        if (Looper.myLooper() == Looper.getMainLooper()) run() else main.post { run() }
    }

    /**
     * Headless submit for your own UI. [screenshots] are PNG, JPEG or WebP
     * bytes (≤ 5 MB each, at most 5). The feedback is saved to the outbox
     * first, so it is never lost offline; the result carries the server id when
     * it was delivered right away. Never throws.
     */
    suspend fun submit(
        message: String,
        labels: List<String> = emptyList(),
        email: String? = null,
        screenshots: List<ByteArray> = emptyList(),
        custom: Map<String, Any?>? = null,
    ): LerixFeedbackResult {
        if (!LerixRuntime.initialized) return LerixFeedbackResult(false)
        if (message.isBlank()) {
            LerixLog.debug("feedback.submit: empty message")
            return LerixFeedbackResult(false)
        }
        return try {
            val clientId = LerixIds.newId()
            val body = buildBody(clientId, message, labels, email ?: userEmail, custom)
            val files = screenshots.take(MAX_ATTACHMENTS).mapNotNull { bytes ->
                val mime = LerixScreenshot.sniffMime(bytes)
                if (mime == null || bytes.size > MAX_ATTACHMENT_BYTES) {
                    LerixLog.debug("feedback.submit: skipped a screenshot (must be png/jpeg/webp ≤ 5 MB)")
                    null
                } else {
                    LerixPendingFile(bytes, mime, null, null)
                }
            }
            withContext(Dispatchers.IO) { LerixFeedbackOutbox.enqueue(clientId, body, files) }
            LerixFeedbackOutbox.process(force = true)
            LerixFeedbackResult(true, LerixFeedbackOutbox.serverId(clientId), clientId)
        } catch (e: Exception) {
            LerixLog.debug("feedback.submit failed: $e")
            LerixFeedbackResult(false)
        }
    }

    /** Prefills the email field of the feedback flow (null clears it). Stored on the device only. */
    fun setUserEmail(email: String?) {
        if (!LerixRuntime.initialized) return
        LerixRuntime.store.putString(EMAIL_KEY, email?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** Opens the feedback flow when the user shakes the device (accelerometer; off by default). */
    fun enableShakeToReport(application: Application, options: LerixFeedbackOptions = LerixFeedbackOptions()) {
        LerixShakeDetector.enable(application, options)
    }

    fun disableShakeToReport() {
        LerixShakeDetector.disable()
    }

    // ---- internal ----

    /** Cached config if fresher than 24 h, else a quick fetch, else the cache, else the defaults. */
    internal suspend fun loadConfig(): LerixFeedbackConfig {
        if (LerixRuntime.hints.isDisabled(LerixFeature.FEEDBACK)) {
            return (cachedConfig() ?: LerixFeedbackConfig.DEFAULT).copy(enabled = false)
        }
        val store = LerixRuntime.store
        val cached = cachedConfig()
        val age = System.currentTimeMillis() - (store.getLong(CONFIG_AT_KEY) ?: 0)
        if (cached != null && age in 0 until CONFIG_TTL_MS) return cached
        val fetch = LerixRuntime.scope.async { fetchConfig() }
        val fresh = withTimeoutOrNull(CONFIG_TIMEOUT_MS) { fetch.await() }
        return fresh ?: cached ?: LerixFeedbackConfig.DEFAULT
    }

    private fun cachedConfig(): LerixFeedbackConfig? =
        LerixRuntime.store.getString(CONFIG_KEY)?.let {
            runCatching { LerixFeedbackConfig.fromJson(JSONObject(it)) }.getOrNull()
        }

    private suspend fun fetchConfig(): LerixFeedbackConfig? {
        val userId = LerixRuntime.ensureUser() ?: return null
        val result = LerixSender.send(LerixFeature.FEEDBACK, LerixRoute.FEEDBACK_CONFIG, userId, null)
        if (result.decision != LerixRetryDecision.SUCCESS) return null
        val json = result.response?.json ?: return null
        val config = runCatching { LerixFeedbackConfig.fromJson(json) }.getOrNull() ?: return null
        LerixRuntime.store.putString(CONFIG_KEY, config.toJson().toString())
        LerixRuntime.store.putLong(CONFIG_AT_KEY, System.currentTimeMillis())
        return config
    }

    internal fun maxMessageLength(): Int = cachedConfig()?.maxMessageLength ?: 4000

    /** `POST /plugin/feedback` body (contract §1.3), without `attachmentIds`. */
    internal fun buildBody(
        clientId: String,
        message: String,
        labels: List<String>,
        email: String?,
        custom: Map<String, Any?>?,
    ): JSONObject {
        val app = LerixRuntime.app()
        return JSONObject().apply {
            put("clientId", clientId)
            put("message", message.trim().take(maxMessageLength()))
            put("labels", JSONArray(labels.distinct()))
            val cleanEmail = email?.trim()
            if (!cleanEmail.isNullOrEmpty()) {
                if (EMAIL.matches(cleanEmail)) put("email", cleanEmail) else LerixLog.debug("feedback: invalid email omitted")
            }
            put("platform", "android")
            put("appVersion", app.version ?: "0.0.0")
            put("buildNumber", app.buildNo ?: "0")
            put("environment", LerixRuntime.environment)
            LerixAnalytics.currentSessionId()?.let { put("sessionId", it) }
            put("device", LerixRuntime.feedbackDevice())
            put("app", LerixRuntime.feedbackApp())
            sanitizeCustom(custom)?.let { put("custom", it) }
            put("breadcrumbs", LerixRuntime.breadcrumbs.toJson())
        }
    }

    internal fun isValidEmail(email: String): Boolean = EMAIL.matches(email.trim())

    private fun sanitizeCustom(custom: Map<String, Any?>?): JSONObject? {
        if (custom.isNullOrEmpty()) return null
        val limited = custom.entries.take(MAX_CUSTOM_KEYS).associate { it.key to it.value }
        if (custom.size > MAX_CUSTOM_KEYS) LerixLog.debug("feedback custom: only the first $MAX_CUSTOM_KEYS keys are sent")
        val json = LerixJson.objectOf(limited)
        if (json.toString().toByteArray(Charsets.UTF_8).size > MAX_CUSTOM_BYTES) {
            LerixLog.debug("feedback custom data is larger than 16 KB and was dropped")
            return null
        }
        return json
    }
}
