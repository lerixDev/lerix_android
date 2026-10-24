package com.lerix.sdk

import com.lerix.sdk.appcontrol.LerixAppControl
import com.lerix.sdk.models.BugSeverity
import com.lerix.sdk.models.BugType
import com.lerix.sdk.models.LerixErrorEvent

internal object ErrorsHandler {
    private const val USER_NOT_REGISTERED_CODE = "USER_PROJECT_NOT_EXIST"
    private const val MAX_RETRY_ATTEMPTS = 3

    suspend fun throwError(
        issue: String,
        stack: List<String>,
        type: BugType? = null,
        severity: BugSeverity? = null,
        metadata: Map<String, Any?>? = null,
        sessionId: String? = null,
    ) {
        // Vendor kill switch (contract §2.5): no error traffic while `errors`/`all` is disabled.
        if (LerixAppControl.isModuleDisabled("errors")) {
            if (LerixKeys.debug) println("[Lerix] Error not sent: error reporting is disabled by Lerix")
            return
        }
        // Single choke point for every error path (manual reports, crashes
        // persisted and sent on the next launch): ignore lists, then beforeSend.
        val event = LerixErrorFilter.apply(
            LerixErrorEvent(issue, stack, type, severity, metadata),
            LerixKeys.ignoreErrors,
            LerixKeys.ignoreErrorPatterns,
            LerixKeys.beforeSend,
        ) { e ->
            if (LerixKeys.debug) println("[Lerix] beforeSend threw, sending the original event: $e")
        }
        if (event == null) {
            if (LerixKeys.debug) println("[Lerix] Error dropped by ignoreErrors/beforeSend: $issue")
            return
        }

        try {
            send(event.message, event.stack, event.type, event.severity, event.metadata, sessionId, attempt = 1)
        } catch (e: Exception) {
            if (LerixKeys.debug) {
                println("[Lerix] Failed to report error after retries: $e")
            }
        }
    }

    private suspend fun send(
        issue: String,
        stack: List<String>,
        type: BugType?,
        severity: BugSeverity?,
        metadata: Map<String, Any?>?,
        sessionId: String?,
        attempt: Int,
    ) {
        val userId = LerixInit.existingUserId()
        if (userId == null) {
            LerixInit.registerUser()
            send(issue, stack, type, severity, metadata, sessionId, attempt)
            return
        }

        val device = LerixDeviceInfo.collectDevice()
        val app = LerixDeviceInfo.collectApp()

        val body = mutableMapOf<String, Any?>(
            "issue" to issue,
            "stack" to stack,
            "device" to device.toJson(),
            "app" to app.toJson(),
        )
        metadata?.let { body["metadata"] = org.json.JSONObject(it).toString() }
        type?.let { body["type"] = it.raw }
        severity?.let { body["severity"] = it.raw }
        // Analytics session at the time of the error (crash-free sessions).
        sessionId?.let { body["sessionId"] = it }

        try {
            LerixBackend.post(LerixRoute.SEND_BUG, body, mapOf("app-user" to userId))
        } catch (e: LerixApiException) {
            if (e.code != USER_NOT_REGISTERED_CODE) throw e
            if (attempt >= MAX_RETRY_ATTEMPTS) throw e
            LerixInit.deleteUser()
            LerixInit.registerUser()
            send(issue, stack, type, severity, metadata, sessionId, attempt + 1)
        }
    }
}
