package com.lerix.sdk

import com.lerix.sdk.models.BugSeverity
import com.lerix.sdk.models.BugType
import com.lerix.sdk.models.LerixErrorEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LerixErrorFilterTest {
    private val event = LerixErrorEvent(
        message = "java.net.SocketTimeoutException: Read timed out",
        stack = listOf("at a.b.c(C.kt:1)"),
        type = BugType.CRASH,
        severity = BugSeverity.CRITICAL,
        metadata = mapOf("k" to "v"),
    )

    @Test
    fun noFiltersSendsEventUnchanged() {
        assertSame(event, LerixErrorFilter.apply(event, emptyList(), emptyList(), null))
    }

    @Test
    fun ignoreErrorsMatchesCaseInsensitiveSubstring() {
        assertNull(LerixErrorFilter.apply(event, listOf("READ TIMED"), emptyList(), null))
        assertSame(event, LerixErrorFilter.apply(event, listOf("connection refused"), emptyList(), null))
    }

    @Test
    fun ignoreErrorsSkipsEmptyAndWhitespaceStrings() {
        assertFalse(LerixErrorFilter.isIgnored("anything goes", listOf(""), emptyList()))
        assertFalse(LerixErrorFilter.isIgnored("anything goes", listOf(" ", "\t\n"), emptyList()))
        assertSame(event, LerixErrorFilter.apply(event, listOf("", "  "), emptyList(), null))
    }

    @Test
    fun ignoreErrorPatternsUsesContainsMatchIn() {
        assertNull(LerixErrorFilter.apply(event, emptyList(), listOf(Regex("Socket\\w+Exception")), null))
        assertTrue(LerixErrorFilter.isIgnored("x timeout y", emptyList(), listOf(Regex("time.ut"))))
        assertFalse(LerixErrorFilter.isIgnored("x timeout y", emptyList(), listOf(Regex("^timeout$"))))
    }

    @Test
    fun ignoreListsRunBeforeBeforeSend() {
        var called = false
        val result = LerixErrorFilter.apply(event, listOf("timed out"), emptyList(), beforeSend = { called = true; it })
        assertNull(result)
        assertFalse(called)
    }

    @Test
    fun beforeSendCanModifyEvent() {
        val result = LerixErrorFilter.apply(event, emptyList(), emptyList(), beforeSend = {
            it.copy(message = "redacted", metadata = null)
        })
        assertEquals("redacted", result?.message)
        assertNull(result?.metadata)
        assertEquals(event.stack, result?.stack)
        assertEquals(BugType.CRASH, result?.type)
    }

    @Test
    fun beforeSendReturningNullDropsEvent() {
        assertNull(LerixErrorFilter.apply(event, emptyList(), emptyList(), beforeSend = { null }))
    }

    @Test
    fun beforeSendThrowingSendsOriginalAndDoesNotEscape() {
        var reported: Throwable? = null
        val result = LerixErrorFilter.apply(
            event,
            emptyList(),
            emptyList(),
            { throw IllegalStateException("boom") },
            { reported = it },
        )
        assertSame(event, result)
        assertTrue(reported is IllegalStateException)
    }
}
