package com.lerix.sdk

import android.content.Context
import com.lerix.sdk.models.BugSeverity
import com.lerix.sdk.models.BugType
import com.lerix.sdk.notifications.LerixNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object Lerix {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(
        context: Context,
        apiKey: String,
        projectId: String,
        url: String = "https://api.lerix.dev/v1",
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
    }

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
}
