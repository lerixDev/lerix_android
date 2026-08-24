package com.lerix.sdk

import com.lerix.sdk.models.LerixPingConfig

/**
 * Registration flow. `ping` is a get-or-create call: it registers this app
 * under the project the first time it's called for a given package id +
 * platform, and just looks it up on every call after. `registerUser` then
 * creates a local user scoped to that app record.
 */
internal object LerixInit {
    private const val USER_ID_KEY = "lerix_user_id"
    private const val PING_CONFIG_KEY = "lerix_ping_config"

    /**
     * Registers (or looks up) this app under the project. The returned
     * `id` is the backend's UUID for the app record — NOT the package name
     * sent in the `appid` header — and is what `registerUser`'s
     * `projectApp` field and the notifications routes' `appId` field
     * reference.
     */
    suspend fun ping(): LerixPingConfig {
        val app = LerixDeviceInfo.collectApp()
        val headers = mapOf(
            "appid" to (app.packageName ?: "unknown"),
            "projectid" to LerixKeys.projectId,
            "platform" to "android",
            // Despite the name, the backend's dispatch logic checks this
            // field against the literal string "android" to decide which
            // push service to use — it's a platform discriminator, not a
            // real OS version. Sending the real OS version here silently
            // breaks push delivery: no branch matches, so nothing ever
            // gets sent and no error is ever recorded.
            "os" to "android",
        )

        val response = LerixBackend.get(LerixRoute.PING, headers)
        val data = response?.optJSONObject("data") ?: throw LerixBackendException("Invalid ping response")
        val config = LerixPingConfig.fromJson(data)

        LerixKeys.projectConfig = config
        LerixSecureStorage.write(LerixKeys.appContext, PING_CONFIG_KEY, config.toJson())
        return config
    }

    /** Registers (or re-registers) a local user against the app, returning the user id the backend assigned. */
    suspend fun registerUser(): String {
        val config = cachedOrFreshPingConfig()
        val app = LerixDeviceInfo.collectApp()

        val body = mapOf(
            "projectSlug" to LerixKeys.projectId,
            "projectApp" to (config.id ?: ""),
            "version" to (app.version ?: "0.0.0"),
        )

        val response = LerixBackend.post(LerixRoute.REGISTER_USER, body)
        val userId = response?.optString("user")?.takeIf { it.isNotEmpty() }
            ?: throw LerixBackendException("Invalid register-user response")

        LerixSecureStorage.write(LerixKeys.appContext, USER_ID_KEY, userId)
        LerixKeys.projectUser = userId
        return userId
    }

    suspend fun deleteUser() {
        val userId = LerixKeys.projectUser ?: existingUserId() ?: return
        LerixBackend.delete(LerixRoute.DELETE_USER, mapOf("app-user" to userId))
        LerixSecureStorage.delete(LerixKeys.appContext, USER_ID_KEY)
        LerixKeys.projectUser = null
    }

    fun existingUserId(): String? {
        LerixKeys.projectUser?.let { return it }
        val stored = LerixSecureStorage.read(LerixKeys.appContext, USER_ID_KEY)
        LerixKeys.projectUser = stored
        return stored
    }

    /**
     * The app record's backend UUID, needed by `registerUser` and the
     * notifications routes — cached in memory/secure storage, refreshed
     * via a fresh `ping()` call if neither has it.
     */
    suspend fun cachedOrFreshPingConfig(): LerixPingConfig {
        LerixKeys.projectConfig?.let { return it }
        val raw = LerixSecureStorage.read(LerixKeys.appContext, PING_CONFIG_KEY)
        if (raw != null) {
            val stored = runCatching { LerixPingConfig.fromJsonString(raw) }.getOrNull()
            if (stored != null) {
                LerixKeys.projectConfig = stored
                return stored
            }
        }
        return ping()
    }
}

internal class LerixBackendException(message: String) : Exception(message)
