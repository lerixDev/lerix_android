package com.lerix.sdk

import android.app.Application
import android.content.Context
import android.view.View
import com.lerix.sdk.analytics.LerixAnalytics
import com.lerix.sdk.analytics.LerixAnalyticsOptions
import com.lerix.sdk.appcontrol.LerixAppControl
import com.lerix.sdk.appcontrol.LerixAppControlLogic
import com.lerix.sdk.appcontrol.LerixAppControlOptions
import com.lerix.sdk.core.LerixLaunch
import com.lerix.sdk.core.LerixLifecycle
import com.lerix.sdk.core.LerixLog
import com.lerix.sdk.core.LerixRuntime
import com.lerix.sdk.feedback.LerixFeedback
import com.lerix.sdk.feedback.LerixFeedbackActivity
import com.lerix.sdk.feedback.LerixFeedbackOutbox
import com.lerix.sdk.feedback.LerixScreenshot
import com.lerix.sdk.models.BugSeverity
import com.lerix.sdk.models.BugType
import com.lerix.sdk.models.LerixErrorEvent
import com.lerix.sdk.module.LerixModules
import com.lerix.sdk.notifications.LerixNotificationsApi
import com.lerix.sdk.surveys.LerixSurveys
import com.lerix.sdk.ui.LerixUi
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object Lerix {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Configures the SDK. Error filtering applies to every report (uncaught
     * crashes sent on the next launch and [throwError]) right before it is sent:
     * - [ignoreErrors]: drop an error whose message contains any of these
     *   strings (case-insensitive).
     * - [ignoreErrorPatterns]: drop an error whose message matches any of these
     *   regexes (`containsMatchIn`).
     * - [beforeSend]: called after the ignore lists, on a background thread.
     *   Return the (optionally modified) event to send it, or null to drop it.
     *   If it throws, the original event is sent.
     * - [environment]: `^[a-z][a-z0-9_-]{1,31}$`, sent with feedback, surveys
     *   and analytics. Default: `dev` for a debuggable build or an emulator,
     *   otherwise `prod`.
     * - [analytics]: automatic sessions / screen views, or analytics off.
     * - [appControl]: Release Control (update prompts, forced updates,
     *   maintenance screen); see [Lerix.appControl].
     *
     * Call it from `Application.onCreate()` so app starts, sessions and screen
     * views are tracked from the first activity.
     */
    fun initialize(
        context: Context,
        apiKey: String,
        projectId: String,
        url: String = "https://api.lerix.dev/v1",
        debugMode: Boolean = false,
        enableCrashReporting: Boolean = true,
        onError: ((Throwable) -> Unit)? = null,
        ignoreErrors: List<String> = emptyList(),
        ignoreErrorPatterns: List<Regex> = emptyList(),
        beforeSend: ((LerixErrorEvent) -> LerixErrorEvent?)? = null,
        environment: String? = null,
        analytics: LerixAnalyticsOptions = LerixAnalyticsOptions(),
        appControl: LerixAppControlOptions = LerixAppControlOptions(),
    ) {
        LerixKeys.bind(context)
        LerixKeys.apiKey = apiKey
        LerixKeys.projectId = projectId
        LerixKeys.url = url
        LerixKeys.debug = debugMode
        LerixKeys.ignoreErrors = ignoreErrors
        LerixKeys.ignoreErrorPatterns = ignoreErrorPatterns
        LerixKeys.beforeSend = beforeSend
        LerixLog.enabled = debugMode

        startEngagement(context, environment, analytics)
        // Applies the cached app-config (and its kill switch) before any network call.
        LerixAppControl.start(appControl)

        if (enableCrashReporting) {
            LerixCrashReporter.install()
        }

        LerixRuntime.initJob = launchInit(debugMode, onError)
    }

    private fun launchInit(debugMode: Boolean, onError: ((Throwable) -> Unit)?): Job = scope.launch {
        if (LerixAppControl.isModuleDisabled(LerixAppControlLogic.MODULE_ALL)) {
            // Vendor kill switch (contract §2.5): no ping/registration until it lifts.
            if (debugMode) println("[Lerix] SDK disabled by Lerix; initialization deferred")
            LerixAppControl.deferInit { LerixRuntime.initJob = launchInit(debugMode, onError) }
            return@launch
        }
        try {
            LerixInit.ping()
            if (LerixInit.existingUserId() == null) {
                LerixInit.registerUser()
            } else {
                // Sends a setUser id that was queued or failed earlier.
                LerixInit.syncIdentityQuietly()
            }
            LerixCrashReporter.reportPendingCrashIfAny()
        } catch (e: Exception) {
            if (debugMode) println("[Lerix] Initialization failed: $e")
            onError?.invoke(e)
        }
    }

    fun throwError(
        issue: String,
        stack: List<String> = emptyList(),
        type: BugType = BugType.RUNTIME_ERROR,
        severity: BugSeverity = BugSeverity.MEDIUM,
        metadata: Map<String, Any?>? = null,
    ) {
        val sessionId = LerixAnalytics.currentSessionId()
        scope.launch {
            ErrorsHandler.throwError(issue, stack, type, severity, metadata, sessionId)
        }
    }

    /** Push notifications, from the `lerix-messaging` artifact (part of `lerix`). */
    val notifications: LerixNotificationsApi get() = LerixModules.notificationsOrMissing()

    /**
     * Release Control (contract §2): `state` (StateFlow), `addListener`,
     * `refresh()`, and `openStore()` / `later()` / `skip()` for a custom UI.
     */
    val appControl: LerixAppControl get() = LerixAppControl

    fun getUserId(): String? = LerixInit.existingUserId()

    fun isUserRegistered(): Boolean = LerixInit.existingUserId() != null

    suspend fun deleteUser() {
        LerixInit.deleteUser()
    }

    /**
     * Links this install to your app's own user id. Call after login.
     *
     * Your backend can then target all of this user's devices with
     * `externalUserIds` when sending notifications. If the project requires
     * identity verification, pass [identityHash]: the hex HMAC-SHA256 of
     * [externalId] keyed with the project's identity secret, computed on your
     * server. Never ship the identity secret in the app.
     *
     * The id is stored in encrypted preferences and re-sent automatically
     * whenever this install gets a new Lerix user id. Called before
     * [initialize] has registered the install, it is queued and sent once
     * registration completes. Throws if the backend rejects it (e.g. an
     * invalid hash), like [deleteUser].
     */
    suspend fun setUser(externalId: String, identityHash: String? = null) {
        LerixInit.setUser(externalId, identityHash)
    }

    /** Unlinks this install from your app's user. Call on logout. [getUserId] is kept. */
    suspend fun clearUser() {
        LerixInit.clearUser()
    }

    suspend fun reRegisterUser() {
        LerixInit.deleteUser()
        LerixInit.registerUser()
    }

    // ---- Feedback, surveys and analytics ----

    /** In-app feedback: `show(activity)`, headless `submit(...)`, shake to report. */
    val feedback: LerixFeedback get() = LerixFeedback

    /** NPS surveys: `maybeShowNps(activity)`. */
    val surveys: LerixSurveys get() = LerixSurveys

    /** Analytics controls: `flush()`, `setEnabled(false)` (opt-out, persisted). */
    val analytics: LerixAnalytics get() = LerixAnalytics

    /**
     * Records a custom event. [name] must match `^[A-Za-z][A-Za-z0-9 _.:-]{1,63}$`;
     * [props] values are strings (≤ 1024 chars), finite numbers, booleans or
     * null, at most 25 keys. Invalid events/props are dropped locally; this
     * never throws.
     */
    fun track(name: String, props: Map<String, Any?>? = null) {
        LerixAnalytics.track(name, props)
    }

    /** Records a screen view manually (Activities are tracked automatically). Never throws. */
    fun screen(name: String, props: Map<String, Any?>? = null) {
        LerixAnalytics.screen(name, props, automatic = false)
    }

    /**
     * Adds a breadcrumb (last 50 are kept and attached to feedback).
     * [category] is one of navigation, log, http, ui, custom.
     */
    fun addBreadcrumb(message: String, category: String = "custom", data: Map<String, Any?>? = null) {
        LerixRuntime.breadcrumbs.add(message, category, data)
    }

    /** Masks [view] (a black rectangle over its bounds) in feedback screenshots. */
    fun markConfidential(view: View) {
        LerixScreenshot.markConfidential(view)
    }

    fun unmarkConfidential(view: View) {
        LerixScreenshot.unmarkConfidential(view)
    }

    /** Accent colour (ARGB) for the feedback and NPS UI. Null follows the host theme's primary colour. */
    fun setAccentColor(color: Int?) {
        LerixUi.accentOverride = color
    }

    private fun startEngagement(context: Context, environment: String?, analytics: LerixAnalyticsOptions) {
        try {
            val firstInit = !LerixRuntime.initialized
            LerixRuntime.init(context, environment)
            if (!firstInit) return
            // An install that ran an older SDK version has encrypted prefs (its registered
            // user id) but no install marker. Checked before init's own registration writes them.
            val legacyPrefs = File(context.applicationInfo.dataDir, "shared_prefs/com.lerix.sdk.secure_prefs.xml")
            LerixRuntime.legacyInstall = legacyPrefs.exists() && LerixRuntime.store.getLong(LerixLaunch.INSTALLED_AT) == null
            LerixLifecycle.markInternal(LerixFeedbackActivity::class.java)
            LerixAnalytics.configure(analytics)
            (context.applicationContext as? Application)?.let { LerixLifecycle.register(it) }
            LerixFeedbackOutbox.start()
            LerixSurveys.start()
        } catch (t: Throwable) {
            if (LerixKeys.debug) println("[Lerix] Feedback/analytics setup failed: $t")
        }
    }
}
