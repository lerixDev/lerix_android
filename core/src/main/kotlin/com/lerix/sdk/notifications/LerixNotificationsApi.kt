package com.lerix.sdk.notifications

import android.app.Activity

enum class LerixPermissionStatus { AUTHORIZED, DENIED, NOT_DETERMINED }

/**
 * `Lerix.notifications`: push notifications, provided by the `lerix-messaging`
 * artifact (included in `lerix`). With only `lerix-core`, the calls that need
 * push throw `ERR_MESSAGING_NOT_INSTALLED`.
 */
interface LerixNotificationsApi {
    fun setOnNotificationReceived(handler: (LerixNotificationPayload) -> Unit)
    fun setOnNotificationTapped(handler: (LerixNotificationPayload) -> Unit)
    fun getInitialNotificationTap(): LerixNotificationPayload?
    suspend fun requestPermissions(): Boolean
    fun requestNotificationPermissionLauncher(activity: Activity, requestCode: Int)
    fun checkPermissionStatus(): LerixPermissionStatus
    fun getDeviceToken(): String?
    fun getDeviceId(): String
    fun clearToken()
    fun getRegisteredTokenId(): String?
    suspend fun subscribeToTopic(topic: String)
    suspend fun unsubscribeFromTopic(topic: String)
}
