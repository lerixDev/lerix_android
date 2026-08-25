package com.lerix.sdk

import com.lerix.sdk.models.LerixPingConfig

internal object LerixInit {
    private const val USER_ID_KEY = "lerix_user_id"
    private const val PING_CONFIG_KEY = "lerix_ping_config"

    suspend fun ping(): LerixPingConfig {
        val app = LerixDeviceInfo.collectApp()
        val headers = mapOf(
            "appid" to (app.packageName ?: "unknown"),
            "projectid" to LerixKeys.projectId,
            "platform" to "android",
            "os" to "android",
        )

        val response = LerixBackend.get(LerixRoute.PING, headers)
        val data = response?.optJSONObject("data") ?: throw LerixBackendException("Invalid ping response")
        val config = LerixPingConfig.fromJson(data)

        LerixKeys.projectConfig = config
        LerixSecureStorage.write(LerixKeys.appContext, PING_CONFIG_KEY, config.toJson())
        return config
    }

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
