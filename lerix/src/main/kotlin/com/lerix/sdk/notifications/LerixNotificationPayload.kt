package com.lerix.sdk.notifications

/** The payload handed to `onNotificationReceived`/`onNotificationTapped`. */
data class LerixNotificationPayload(
    val notificationId: String?,
    val title: String?,
    val body: String?,
    val imageUrl: String?,
    val metadata: Map<String, String>,
) {
    companion object {
        private val RESERVED_KEYS = setOf("notificationId", "title", "body", "imageUrl", "sound", "gcm.n.e", "google.delivered_priority", "google.sent_time", "google.ttl", "google.original_priority", "from", "collapse_key")

        fun fromData(data: Map<String, String>): LerixNotificationPayload = LerixNotificationPayload(
            notificationId = data["notificationId"],
            title = data["title"],
            body = data["body"],
            imageUrl = data["imageUrl"],
            metadata = data.filterKeys { it !in RESERVED_KEYS },
        )
    }
}
