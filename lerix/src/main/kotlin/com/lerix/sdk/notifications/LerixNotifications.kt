package com.lerix.sdk.notifications

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lerix.sdk.LerixApiException
import com.lerix.sdk.LerixBackend
import com.lerix.sdk.LerixFirebase
import com.lerix.sdk.LerixInit
import com.lerix.sdk.LerixKeys
import com.lerix.sdk.LerixRoute
import com.lerix.sdk.LerixSecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LerixPermissionStatus { AUTHORIZED, DENIED, NOT_DETERMINED }

object LerixNotifications {
    private const val DEVICE_TOKEN_KEY = "lerix_device_token"
    private const val REGISTERED_TOKEN_ID_KEY = "lerix_registered_token_id"

    private var onReceived: ((LerixNotificationPayload) -> Unit)? = null
    private var onTapped: ((LerixNotificationPayload) -> Unit)? = null
    internal var pendingTap: LerixNotificationPayload? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun setOnNotificationReceived(handler: (LerixNotificationPayload) -> Unit) {
        onReceived = handler
    }

    fun setOnNotificationTapped(handler: (LerixNotificationPayload) -> Unit) {
        onTapped = handler
        pendingTap?.let {
            handler(it)
            pendingTap = null
        }
    }

    fun getInitialNotificationTap(): LerixNotificationPayload? {
        val tap = pendingTap
        pendingTap = null
        return tap
    }

    internal fun deliverReceived(payload: LerixNotificationPayload) {
        onReceived?.invoke(payload)
    }

    internal fun deliverTap(payload: LerixNotificationPayload) {
        val handler = onTapped
        if (handler != null) {
            handler(payload)
        } else {
            pendingTap = payload
        }
    }

    suspend fun requestPermissions(): Boolean {
        val context = LerixKeys.appContext
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (!granted) return false

        try {
            val messaging = LerixFirebase.messaging()
            val token = withContext(Dispatchers.IO) {
                com.google.android.gms.tasks.Tasks.await(messaging.token)
            }
            setDeviceToken(token)
        } catch (e: Exception) {
            if (LerixKeys.debug) println("[Lerix] Failed to fetch FCM token: $e")
            return false
        }
        return true
    }

    fun requestNotificationPermissionLauncher(activity: android.app.Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestCode)
        }
    }

    fun checkPermissionStatus(): LerixPermissionStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return LerixPermissionStatus.AUTHORIZED
        val context = LerixKeys.appContext
        return if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            LerixPermissionStatus.AUTHORIZED
        } else {
            LerixPermissionStatus.NOT_DETERMINED
        }
    }

    internal fun setDeviceToken(token: String) {
        LerixKeys.deviceToken = token
        LerixSecureStorage.write(LerixKeys.appContext, DEVICE_TOKEN_KEY, token)
        scope.launch {
            try {
                registerTokenWithBackend(token)
            } catch (e: Exception) {
                if (LerixKeys.debug) println("[Lerix] Failed to register device token: $e")
            }
        }
    }

    fun getDeviceToken(): String? {
        LerixKeys.deviceToken?.let { return it }
        val stored = LerixSecureStorage.read(LerixKeys.appContext, DEVICE_TOKEN_KEY)
        LerixKeys.deviceToken = stored
        return stored
    }

    fun getDeviceId(): String = com.lerix.sdk.LerixDeviceInfo.deviceId()

    fun clearToken() {
        LerixKeys.deviceToken = null
        LerixSecureStorage.delete(LerixKeys.appContext, DEVICE_TOKEN_KEY)
        LerixSecureStorage.delete(LerixKeys.appContext, REGISTERED_TOKEN_ID_KEY)
    }

    fun getRegisteredTokenId(): String? = LerixSecureStorage.read(LerixKeys.appContext, REGISTERED_TOKEN_ID_KEY)

    suspend fun subscribeToTopic(topic: String) {
        val body = topicBody(topic)
        LerixBackend.post(LerixRoute.SUBSCRIBE_TOPIC, body)
    }

    suspend fun unsubscribeFromTopic(topic: String) {
        val body = topicBody(topic)
        LerixBackend.post(LerixRoute.UNSUBSCRIBE_TOPIC, body)
    }

    private suspend fun topicBody(topic: String): Map<String, Any?> {
        val token = getDeviceToken() ?: throw LerixApiException("NO_TOKEN", "No registered device token")
        val userId = LerixInit.existingUserId() ?: throw LerixApiException("NO_USER", "No registered user")
        val config = LerixInit.cachedOrFreshPingConfig()
        return mapOf("userId" to userId, "appId" to (config.id ?: ""), "token" to token, "topicKey" to topic)
    }

    private suspend fun registerTokenWithBackend(token: String) {
        val userId = LerixInit.existingUserId() ?: return
        val config = LerixInit.cachedOrFreshPingConfig()
        val response = LerixBackend.post(
            LerixRoute.REGISTER_TOKEN,
            mapOf("userId" to userId, "appId" to (config.id ?: ""), "token" to token),
            mapOf("app-user" to userId),
        )
        response?.optString("id")?.takeIf { it.isNotEmpty() }?.let {
            LerixSecureStorage.write(LerixKeys.appContext, REGISTERED_TOKEN_ID_KEY, it)
        }
    }
}
