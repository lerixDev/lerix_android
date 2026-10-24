package com.lerix.sdk.notifications

/**
 * Notification channel ids. Must stay in sync with the backend's
 * `AndroidNotificationsService.androidChannelId` (it names the channel FCM
 * shows a backgrounded notification on) and lerix_flutter's
 * `LerixMessagingService.channelIdFor`, which created these ids first, so
 * channels already on devices keep their user settings.
 *
 * A channel's sound is fixed at creation, so each custom sound gets its own.
 */
internal object LerixNotificationChannel {
    const val DEFAULT_ID = "lerix_notifications"

    /** The custom sound name, or null for none / the backend's `"default"`. */
    fun customSound(sound: String?): String? =
        if (sound.isNullOrBlank() || sound == "default") null else sound

    fun idFor(sound: String?): String {
        val custom = customSound(sound) ?: return DEFAULT_ID
        return "${DEFAULT_ID}_${custom.replace(Regex("[^a-zA-Z0-9_]"), "_")}"
    }
}
