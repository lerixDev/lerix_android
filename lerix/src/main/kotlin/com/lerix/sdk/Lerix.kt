package com.lerix.sdk

import android.content.Context
import com.lerix.sdk.models.BugSeverity
import com.lerix.sdk.models.BugType
import com.lerix.sdk.notifications.LerixNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Public entry point for the native Android SDK — `Lerix.initialize`,
 * `Lerix.throwError`, `Lerix.notifications` — mirroring the Flutter and
 * native iOS SDKs feature-for-feature.
 */
object Lerix {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Configure the SDK and register this install with the backend. Call
     * once, e.g. from a custom `Application.onCreate()`.
     *
     * Pass `enableCrashReporting = false` to skip installing the uncaught
     * exception handler (e.g. if your app already has its own crash
     * reporter and you only want manual `throwError` calls).
     */
    fun initialize(
        context: Context,
        apiKey: String,
        projectId: String,
        url: String = "https://api.atelerix.dev/v1",
        debugMode: Boolean = false,
        enableCrashReporting: Boolean = true,
        onError: ((Throwable) -> Unit)? = null,
    ) {
        LerixKeys.bind(context)
        LerixKeys.apiKey = apiKey
        LerixKeys.projectId = projectId
        LerixKeys.url = url
        LerixKeys.debug = debugMode

        if (enableCrashReporting) {
            LerixCrashReporter.install()
        }

        scope.launch {
            try {
                LerixInit.ping()
                // Registration only needs to happen once per install — the
                // secure-storage-persisted user id survives relaunches, so
                // this avoids creating a fresh backend user on every launch.
                if (LerixInit.existingUserId() == null) {
                    LerixInit.registerUser()
                }
                LerixCrashReporter.reportPendingCrashIfAny()
            } catch (e: Exception) {
                if (debugMode) println("[Lerix] Initialization failed: $e")
                onError?.invoke(e)
            }
        }
    }

    /** Manually report a caught error/crash. */
    fun throwError(
        issue: String,
        stack: List<String> = emptyList(),
        type: BugType = BugType.RUNTIME_ERROR,
        severity: BugSeverity = BugSeverity.MEDIUM,
        metadata: Map<String, Any?>? = null,
    ) {
        scope.launch {
            ErrorsHandler.throwError(issue, stack, type, severity, metadata)
        }
    }

    val notifications: LerixNotifications get() = LerixNotifications

    fun getUserId(): String? = LerixInit.existingUserId()

    fun isUserRegistered(): Boolean = LerixInit.existingUserId() != null

    suspend fun deleteUser() {
        LerixInit.deleteUser()
    }

    suspend fun reRegisterUser() {
        LerixInit.deleteUser()
        LerixInit.registerUser()
    }
}
