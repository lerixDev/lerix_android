package com.lerix.sdk.notifications

/**
 * What to do with an incoming FCM message. Silent control messages carry a
 * `data.action` (the backend's unsend is `{ action: "remove", notificationId }`);
 * they must never be shown, or the user sees a blank notification.
 */
internal sealed class LerixPushAction {
    /** A normal notification: deliver it to listeners and display it. */
    object Display : LerixPushAction()

    /** Unsend: cancel the notification the backend sent as [notificationId]. */
    data class Remove(val notificationId: String) : LerixPushAction()

    /** Release Control `{ action: "config_refresh" }`: re-fetch app-config now; never shown. */
    object ConfigRefresh : LerixPushAction()

    /** A silent action this SDK doesn't handle (or a malformed remove): drop it. */
    data class Ignore(val action: String) : LerixPushAction()

    companion object {
        const val ACTION_REMOVE = "remove"
        const val ACTION_CONFIG_REFRESH = "config_refresh"

        fun from(data: Map<String, String>): LerixPushAction {
            val action = data["action"]
            if (action.isNullOrEmpty()) return Display
            if (action == ACTION_CONFIG_REFRESH) return ConfigRefresh
            if (action == ACTION_REMOVE) {
                val id = data["notificationId"]
                if (!id.isNullOrEmpty()) return Remove(id)
            }
            return Ignore(action)
        }
    }
}
