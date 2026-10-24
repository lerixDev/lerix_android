package com.lerix.sdk.module

import android.content.Context
import com.lerix.sdk.LerixApiException
import com.lerix.sdk.LerixBackend
import com.lerix.sdk.LerixBackendException
import com.lerix.sdk.LerixDeviceInfo
import com.lerix.sdk.LerixInit
import com.lerix.sdk.LerixKeys
import com.lerix.sdk.LerixRoute
import com.lerix.sdk.LerixSecureStorage
import com.lerix.sdk.appcontrol.LerixAppControl
import com.lerix.sdk.notifications.LerixNotificationsApi
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** For Lerix's own artifacts (`lerix-messaging`), not for apps: it can change in any release. */
@RequiresOptIn(
    message = "For Lerix's own artifacts (lerix-messaging), not for apps.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class LerixModuleApi

/**
 * What `lerix-core` offers the other Lerix artifacts, so they never reach
 * into core's internals (same idea as lerix_flutter's `LerixHost`).
 */
@LerixModuleApi
object LerixModuleHost {
    val appContext: Context get() = LerixKeys.appContext
    val debug: Boolean get() = LerixKeys.debug

    /** Binds the SDK to [context] when a service or receiver starts the process. */
    fun bind(context: Context) = LerixKeys.bind(context)

    /** Backend calls with the project key, gated by the vendor kill switch. */
    suspend fun get(path: String): JSONObject? = LerixBackend.get(LerixRoute("GET", path))

    suspend fun post(path: String, body: Map<String, Any?>, headers: Map<String, String> = emptyMap()): JSONObject? =
        LerixBackend.post(LerixRoute("POST", path), body, headers)

    fun readSecure(key: String): String? = LerixSecureStorage.read(LerixKeys.appContext, key)
    fun writeSecure(key: String, value: String) = LerixSecureStorage.write(LerixKeys.appContext, key, value)
    fun deleteSecure(key: String) = LerixSecureStorage.delete(LerixKeys.appContext, key)

    /** This install's project user, once registered. */
    fun existingUserId(): String? = LerixInit.existingUserId()

    /** The project app this build belongs to (from the ping). */
    suspend fun appId(): String? = LerixInit.cachedOrFreshPingConfig().id

    fun deviceId(): String = LerixDeviceInfo.deviceId()

    /** A silent `config_refresh` push arrived: refetch release control. */
    fun onConfigRefreshPush() = LerixAppControl.onConfigRefreshPush()

    fun apiError(code: String, message: String): Exception = LerixApiException(code, message)
    fun backendError(message: String): Exception = LerixBackendException(message)

    /** Makes [impl] what `Lerix.notifications` returns. */
    fun provideNotifications(impl: LerixNotificationsApi) {
        LerixModules.notifications = impl
    }

    /** [listener] hears each kill-switch module that lifts (`push`, `all`, ...). */
    fun onModuleLifted(listener: (String) -> Unit) {
        LerixModules.liftListeners += listener
    }
}

/** Where core finds the optional artifacts. */
internal object LerixModules {
    private const val MESSAGING_CLASS = "com.lerix.sdk.notifications.LerixNotifications"

    @Volatile
    var notifications: LerixNotificationsApi? = null

    val liftListeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Volatile
    private var looked = false

    /**
     * `lerix-messaging` is found by class lookup (like the optional Play update
     * library), so apps don't call anything to enable it. Loading its object
     * runs its registration.
     */
    fun discover() {
        if (looked) return
        looked = true
        if (notifications != null) return
        runCatching {
            val instance = Class.forName(MESSAGING_CLASS).getField("INSTANCE").get(null)
            if (instance is LerixNotificationsApi) notifications = instance
        }
    }

    fun notificationsOrMissing(): LerixNotificationsApi {
        discover()
        return notifications ?: MissingNotifications
    }

    fun notifyLifted(modules: Collection<String>) {
        discover()
        for (module in modules) for (listener in liftListeners) runCatching { listener(module) }
    }
}

/** `Lerix.notifications` without `lerix-messaging`. */
internal object MissingNotifications : LerixNotificationsApi {
    const val MESSAGE = "ERR_MESSAGING_NOT_INSTALLED: push notifications need the lerix-messaging artifact " +
        "(com.github.lerix.lerix-android:lerix-messaging, or the all-in-one lerix)."

    private fun missing(): Nothing = throw IllegalStateException(MESSAGE)

    override fun setOnNotificationReceived(handler: (com.lerix.sdk.notifications.LerixNotificationPayload) -> Unit) {
        if (LerixKeys.debug) println("[Lerix] $MESSAGE")
    }
    override fun setOnNotificationTapped(handler: (com.lerix.sdk.notifications.LerixNotificationPayload) -> Unit) {
        if (LerixKeys.debug) println("[Lerix] $MESSAGE")
    }
    override fun getInitialNotificationTap() = null
    override suspend fun requestPermissions(): Boolean = missing()
    override fun requestNotificationPermissionLauncher(activity: android.app.Activity, requestCode: Int) = missing()
    override fun checkPermissionStatus(): com.lerix.sdk.notifications.LerixPermissionStatus = missing()
    override fun getDeviceToken(): String? = null
    override fun getDeviceId(): String = LerixDeviceInfo.deviceId()
    override fun clearToken() {}
    override fun getRegisteredTokenId(): String? = null
    override suspend fun subscribeToTopic(topic: String) = missing()
    override suspend fun unsubscribeFromTopic(topic: String) = missing()
}
