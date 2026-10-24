package com.lerix.sdk.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** lerix-core finds push by this exact class name and its Kotlin INSTANCE field. */
class LerixNotificationsLookupTest {
    @Test
    fun coreCanFindThisObject() {
        assertEquals("com.lerix.sdk.notifications.LerixNotifications", LerixNotifications::class.java.name)
        val instance = Class.forName("com.lerix.sdk.notifications.LerixNotifications").getField("INSTANCE").get(null)
        assertTrue(instance is LerixNotificationsApi)
    }
}
