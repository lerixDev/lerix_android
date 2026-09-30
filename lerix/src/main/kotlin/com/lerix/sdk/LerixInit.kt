package com.lerix.sdk

import com.lerix.sdk.models.LerixPingConfig

internal object LerixInit {
    private const val USER_ID_KEY = "lerix_user_id"
    private const val PING_CONFIG_KEY = "lerix_ping_config"
    private const val EXTERNAL_ID_KEY = "lerix_external_id"
    private const val IDENTITY_HASH_KEY = "lerix_identity_hash"

    // The install user id the stored external id was last confirmed for. When
    // it differs from the current user (fresh registerUser, a replaced stale
    // user, or an identify that failed earlier) the external id is re-sent.
    private const val IDENTIFIED_USER_KEY = "lerix_identified_user"
    private const val USER_NOT_REGISTERED_CODE = "USER_PROJECT_NOT_EXIST"

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
        // A fresh install user has no external id on the backend yet.
        syncIdentityQuietly()
        return userId
    }

    /**
     * Persists the host app's user id and sends it to the backend. With no
     * install user id yet (init still running, or offline) it stays queued and
     * [registerUser] / [Lerix.initialize] send it once registration completes.
     */
    suspend fun setUser(externalId: String, identityHash: String?) {
        val id = externalId.trim()
        if (id.isEmpty()) throw LerixApiException("EXTERNAL_ID_EMPTY", "externalId must not be empty")

        val context = LerixKeys.appContext
        LerixSecureStorage.write(context, EXTERNAL_ID_KEY, id)
        if (!identityHash.isNullOrEmpty()) {
            LerixSecureStorage.write(context, IDENTITY_HASH_KEY, identityHash)
        } else {
            LerixSecureStorage.delete(context, IDENTITY_HASH_KEY)
        }
        LerixSecureStorage.delete(context, IDENTIFIED_USER_KEY)

        val userId = existingUserId()
        if (userId == null) {
            if (LerixKeys.debug) println("[Lerix] setUser queued until this install is registered")
            return
        }
        identify(userId, id, identityHash, recoverStaleUser = true)
    }

    /** Forgets the stored external id and unlinks the install on the backend. */
    suspend fun clearUser() {
        val context = LerixKeys.appContext
        LerixSecureStorage.delete(context, EXTERNAL_ID_KEY)
        LerixSecureStorage.delete(context, IDENTITY_HASH_KEY)
        LerixSecureStorage.delete(context, IDENTIFIED_USER_KEY)

        val userId = existingUserId() ?: return
        LerixBackend.post(LerixRoute.LOGOUT, headers = mapOf("app-user" to userId))
    }

    /**
     * Re-sends the stored external id if it hasn't been confirmed for the
     * current install user. Never throws: a failure is retried on the next
     * registration or launch.
     */
    suspend fun syncIdentityQuietly() {
        try {
            val context = LerixKeys.appContext
            val userId = existingUserId() ?: return
            val externalId = LerixSecureStorage.read(context, EXTERNAL_ID_KEY)
            if (externalId.isNullOrEmpty()) return
            if (LerixSecureStorage.read(context, IDENTIFIED_USER_KEY) == userId) return
            identify(
                userId,
                externalId,
                LerixSecureStorage.read(context, IDENTITY_HASH_KEY),
                recoverStaleUser = false,
            )
        } catch (e: Exception) {
            if (LerixKeys.debug) println("[Lerix] identify failed: $e")
        }
    }

    private suspend fun identify(
        userId: String,
        externalId: String,
        identityHash: String?,
        recoverStaleUser: Boolean,
    ) {
        val body = mutableMapOf<String, Any?>("externalId" to externalId)
        if (!identityHash.isNullOrEmpty()) body["identityHash"] = identityHash

        try {
            LerixBackend.post(LerixRoute.IDENTIFY, body, mapOf("app-user" to userId))
            LerixSecureStorage.write(LerixKeys.appContext, IDENTIFIED_USER_KEY, userId)
        } catch (e: LerixApiException) {
            if (!recoverStaleUser || e.code != USER_NOT_REGISTERED_CODE) throw e
            // The stored install user no longer exists on the backend. Drop it
            // locally (a backend delete would fail the same way) and register a
            // new one; registerUser re-sends the stored external id.
            LerixSecureStorage.delete(LerixKeys.appContext, USER_ID_KEY)
            LerixKeys.projectUser = null
            val newUserId = registerUser()
            if (LerixSecureStorage.read(LerixKeys.appContext, IDENTIFIED_USER_KEY) != newUserId) throw e
        }
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
