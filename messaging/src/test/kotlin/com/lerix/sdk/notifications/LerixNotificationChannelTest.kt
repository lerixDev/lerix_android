package com.lerix.sdk.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/** Must match the backend's channel ids (androidnotifications.service.spec.ts). */
class LerixNotificationChannelTest {
    @Test
    fun noCustomSoundUsesTheDefaultChannel() {
        assertEquals("lerix_notifications", LerixNotificationChannel.idFor(null))
        assertEquals("lerix_notifications", LerixNotificationChannel.idFor(""))
        assertEquals("lerix_notifications", LerixNotificationChannel.idFor(" "))
        assertEquals("lerix_notifications", LerixNotificationChannel.idFor("default"))
    }

    @Test
    fun customSoundGetsItsOwnSanitizedChannel() {
        assertEquals("lerix_notifications_chime", LerixNotificationChannel.idFor("chime"))
        assertEquals("lerix_notifications_alert_2_mp3", LerixNotificationChannel.idFor("alert-2.mp3"))
    }
}
