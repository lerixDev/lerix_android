package com.lerix.sdk

import com.lerix.sdk.models.BugSeverity
import com.lerix.sdk.models.BugType

/** Reports errors/crashes to the backend, including retry-on-"user not registered" logic. */
internal object ErrorsHandler {
    private const val USER_NOT_REGISTERED_CODE = "USER_PROJECT_NOT_EXIST"
    private const val MAX_RETRY_ATTEMPTS = 3

    suspend fun throwError(
        issue: String,
        stack: List<String>,
        type: BugType? = null,
        severity: BugSeverity? = null,
        metadata: Map<String, Any?>? = null,
    ) {
        try {
            send(issue, stack, type, severity, metadata, attempt = 1)
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
        attempt: Int,
    ) {
        val userId = LerixInit.existingUserId()
        if (userId == null) {
            LerixInit.registerUser()
            send(issue, stack, type, severity, metadata, attempt)
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

        try {
            LerixBackend.post(LerixRoute.SEND_BUG, body, mapOf("app-user" to userId))
        } catch (e: LerixApiException) {
            if (e.code != USER_NOT_REGISTERED_CODE) throw e
            if (attempt >= MAX_RETRY_ATTEMPTS) throw e
            LerixInit.deleteUser()
            LerixInit.registerUser()
            send(issue, stack, type, severity, metadata, attempt + 1)
        }
    }
}
