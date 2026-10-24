package com.lerix.sdk.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class LerixPushActionTest {
    @Test
    fun removeWithNotificationIdIsRemoval() {
        assertEquals(
            LerixPushAction.Remove("n-1"),
            LerixPushAction.from(mapOf("action" to "remove", "notificationId" to "n-1")),
        )
    }

    @Test
    fun removeWithoutNotificationIdIsIgnoredNotDisplayed() {
        assertEquals(LerixPushAction.Ignore("remove"), LerixPushAction.from(mapOf("action" to "remove")))
        assertEquals(
            LerixPushAction.Ignore("remove"),
            LerixPushAction.from(mapOf("action" to "remove", "notificationId" to "")),
        )
    }

    @Test
    fun unknownSilentActionIsIgnored() {
        assertEquals(
            LerixPushAction.Ignore("something_new"),
            LerixPushAction.from(mapOf("action" to "something_new", "notificationId" to "n-1")),
        )
    }

    @Test
    fun configRefreshIsASilentRefetch() {
        assertEquals(LerixPushAction.ConfigRefresh, LerixPushAction.from(mapOf("action" to "config_refresh")))
        assertEquals(
            LerixPushAction.ConfigRefresh,
            LerixPushAction.from(mapOf("action" to "config_refresh", "notificationId" to "n-1")),
        )
        assertEquals(LerixPushAction.Ignore("CONFIG_REFRESH"), LerixPushAction.from(mapOf("action" to "CONFIG_REFRESH")))
    }

    @Test
    fun actionIsCaseSensitive() {
        assertEquals(
            LerixPushAction.Ignore("REMOVE"),
            LerixPushAction.from(mapOf("action" to "REMOVE", "notificationId" to "n-1")),
        )
    }

    @Test
    fun normalNotificationIsDisplayed() {
        assertEquals(
            LerixPushAction.Display,
            LerixPushAction.from(mapOf("notificationId" to "n-1", "metadata" to "{}")),
        )
        assertEquals(LerixPushAction.Display, LerixPushAction.from(mapOf("action" to "", "title" to "Hi")))
        assertEquals(LerixPushAction.Display, LerixPushAction.from(emptyMap()))
    }
}
