package com.lerix.sdk.module

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LerixModulesTest {
    @Test
    fun withoutMessagingPushSaysWhatToAdd() {
        // lerix-core's own tests don't have lerix-messaging on the classpath.
        val notifications = LerixModules.notificationsOrMissing()
        assertSame(MissingNotifications, notifications)
        assertNull(notifications.getDeviceToken())
        notifications.setOnNotificationTapped { } // never throws
        try {
            runBlocking { notifications.subscribeToTopic("news") }
            fail("expected ERR_MESSAGING_NOT_INSTALLED")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.startsWith("ERR_MESSAGING_NOT_INSTALLED"))
        }
    }

    @Test
    fun liftsReachEveryListener() {
        val heard = mutableListOf<String>()
        LerixModules.liftListeners += { heard += it }
        LerixModules.notifyLifted(listOf("push"))
        assertTrue("push" in heard)
    }
}
