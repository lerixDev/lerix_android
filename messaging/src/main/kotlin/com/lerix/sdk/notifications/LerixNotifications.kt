@file:OptIn(LerixModuleApi::class)

package com.lerix.sdk.notifications

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lerix.sdk.module.LerixModuleApi
import com.lerix.sdk.module.LerixModuleHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Push notifications for the Lerix Android SDK (`lerix-messaging`). Core finds
 * this object by class lookup and returns it as `Lerix.notifications`.
 */
object LerixNotifications : LerixNotificationsApi {
    private const val DEVICE_TOKEN_KEY = "lerix_device_token"
    private const val REGISTERED_TOKEN_ID_KEY = "lerix_registered_token_id"

    private const val PATH_REGISTER_TOKEN = "plugin/notifications/register-token"
    private const val PATH_SUBSCRIBE_TOPIC = "plugin/notifications/subscribe-topic"
    private const val PATH_UNSUBSCRIBE_TOPIC = "plugin/notifications/unsubscribe-topic"

    @Volatile
    private var cachedToken: String? = null

    init {
        LerixModuleHost.provideNotifications(this)
        // The kill switch lifted push: register the token again so the device receives at once.
        LerixModuleHost.onModuleLifted { module ->
            if (module == "push" || module == "all") getDeviceToken()?.let { setDeviceToken(it) }
        }
    }

    private var onReceived: ((LerixNotificationPayload) -> Unit)? = null
    private var onTapped: ((LerixNotificationPayload) -> Unit)? = null
    internal var pendingTap: LerixNotificationPayload? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun setOnNotificationReceived(handler: (LerixNotificationPayload) -> Unit) {
        onReceived = handler
    }

    override fun setOnNotificationTapped(handler: (LerixNotificationPayload) -> Unit) {
        onTapped = handler
        pendingTap?.let {
            handler(it)
            pendingTap = null
        }
    }

    override fun getInitialNotificationTap(): LerixNotificationPayload? {
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

    override suspend fun requestPermissions(): Boolean {
        val context = LerixModuleHost.appContext
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
            if (LerixModuleHost.debug) println("[Lerix] Failed to fetch FCM token: $e")
            return false
        }
        return true
    }

    override fun requestNotificationPermissionLauncher(activity: android.app.Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestCode)
        }
    }

    override fun checkPermissionStatus(): LerixPermissionStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return LerixPermissionStatus.AUTHORIZED
        val context = LerixModuleHost.appContext
        return if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            LerixPermissionStatus.AUTHORIZED
        } else {
            LerixPermissionStatus.NOT_DETERMINED
        }
    }

    internal fun setDeviceToken(token: String) {
        cachedToken = token
        LerixModuleHost.writeSecure(DEVICE_TOKEN_KEY, token)
        scope.launch {
            try {
                registerTokenWithBackend(token)
            } catch (e: Exception) {
                if (LerixModuleHost.debug) println("[Lerix] Failed to register device token: $e")
            }
        }
    }

    override fun getDeviceToken(): String? {
        cachedToken?.let { return it }
        val stored = LerixModuleHost.readSecure(DEVICE_TOKEN_KEY)
        cachedToken = stored
        return stored
    }

    override fun getDeviceId(): String = LerixModuleHost.deviceId()

    override fun clearToken() {
        cachedToken = null
        LerixModuleHost.deleteSecure(DEVICE_TOKEN_KEY)
        LerixModuleHost.deleteSecure(REGISTERED_TOKEN_ID_KEY)
    }

    override fun getRegisteredTokenId(): String? = LerixModuleHost.readSecure(REGISTERED_TOKEN_ID_KEY)

    override suspend fun subscribeToTopic(topic: String) {
        val body = topicBody(topic)
        LerixModuleHost.post(PATH_SUBSCRIBE_TOPIC, body)
    }

    override suspend fun unsubscribeFromTopic(topic: String) {
        val body = topicBody(topic)
        LerixModuleHost.post(PATH_UNSUBSCRIBE_TOPIC, body)
    }

    private suspend fun topicBody(topic: String): Map<String, Any?> {
        val token = getDeviceToken() ?: throw LerixModuleHost.apiError("NO_TOKEN", "No registered device token")
        val userId = LerixModuleHost.existingUserId() ?: throw LerixModuleHost.apiError("NO_USER", "No registered user")
        return mapOf("userId" to userId, "appId" to (LerixModuleHost.appId() ?: ""), "token" to token, "topicKey" to topic)
    }

    private suspend fun registerTokenWithBackend(token: String) {
        val userId = LerixModuleHost.existingUserId() ?: return
        val response = LerixModuleHost.post(
            PATH_REGISTER_TOKEN,
            mapOf("userId" to userId, "appId" to (LerixModuleHost.appId() ?: ""), "token" to token),
            mapOf("app-user" to userId),
        )
        response?.optString("id")?.takeIf { it.isNotEmpty() }?.let {
            LerixModuleHost.writeSecure(REGISTERED_TOKEN_ID_KEY, it)
        }
    }
}
