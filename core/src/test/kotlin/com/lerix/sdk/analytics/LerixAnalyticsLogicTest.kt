package com.lerix.sdk.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LerixEventValidatorTest {
    @Test
    fun names() {
        assertTrue(LerixEventValidator.isValidName("checkout_started"))
        assertTrue(LerixEventValidator.isValidName("Cart: item added"))
        assertTrue(LerixEventValidator.isValidName("a.b-c"))
        assertFalse(LerixEventValidator.isValidName("a")) // min 2 chars
        assertFalse(LerixEventValidator.isValidName("1abc"))
        assertFalse(LerixEventValidator.isValidName("x".repeat(65)))
        assertFalse(LerixEventValidator.isValidName("emoji 🎉"))
        // Reserved names only for the SDK's own events; other $ names never.
        assertFalse(LerixEventValidator.isValidName("\$first_open"))
        assertTrue(LerixEventValidator.isValidName("\$first_open", allowReserved = true))
        assertFalse(LerixEventValidator.isValidName("\$custom", allowReserved = true))
    }

    @Test
    fun propsAreSanitizedNotThrown() {
        val dropped = mutableListOf<String>()
        val out = LerixEventValidator.sanitizeProps(
            linkedMapOf(
                "items" to 3,
                "price" to 9.5,
                "coupon" to "EID",
                "ok" to true,
                "none" to null,
                "bad key" to 1,
                "nan" to Double.NaN,
                "inf" to Float.POSITIVE_INFINITY,
                "list" to listOf(1, 2),
                "long" to "y".repeat(2000),
            ),
        ) { dropped += it }
        assertEquals(3L, out["items"])
        assertEquals(9.5, out["price"])
        assertEquals("EID", out["coupon"])
        assertEquals(true, out["ok"])
        assertTrue(out.containsKey("none"))
        assertNull(out["none"])
        assertFalse(out.containsKey("bad key"))
        assertFalse(out.containsKey("nan"))
        assertFalse(out.containsKey("inf"))
        assertFalse(out.containsKey("list"))
        assertEquals(1024, (out["long"] as String).length)
        assertEquals(4, dropped.size)
    }

    @Test
    fun atMost25Props() {
        val props = (1..30).associate { "k$it" to it }
        assertEquals(25, LerixEventValidator.sanitizeProps(props).size)
    }
}

class LerixSessionTrackerTest {
    private var n = 0
    private val tracker = LerixSessionTracker({ "s${++n}" })
    private val min = 60_000L

    @Test
    fun coldStartStartsASession() {
        val (state, events) = tracker.onForeground(LerixSessionState(), 1_000, coldStart = true)
        assertEquals("s1", state.sessionId)
        assertEquals(listOf("\$session_start"), events.map { it.name })
        assertEquals(true, events[0].props["coldStart"])
        assertEquals("s1", tracker.currentId(state, 1_000))
    }

    @Test
    fun backgroundEndsWithForegroundDuration() {
        val (s1, _) = tracker.onForeground(LerixSessionState(), 0, coldStart = true)
        val (s2, events) = tracker.onBackground(s1, 90_000)
        assertEquals("\$session_end", events.single().name)
        assertEquals(90L, events.single().props["duration"])
        assertEquals("s1", events.single().sessionId)
        assertEquals(90_000L, s2.backgroundAt)
    }

    @Test
    fun shortBackgroundResumesTheSameSession() {
        val (s1, _) = tracker.onForeground(LerixSessionState(), 0, coldStart = true)
        val (s2, _) = tracker.onBackground(s1, 60_000)
        val (s3, events) = tracker.onForeground(s2, 60_000 + 29 * min, coldStart = false)
        assertTrue(events.isEmpty())
        assertEquals("s1", s3.sessionId)
        // Cumulative foreground time only (the background gap isn't counted).
        val (_, end) = tracker.onBackground(s3, 60_000 + 29 * min + 30_000)
        assertEquals(90L, end.single().props["duration"])
    }

    @Test
    fun thirtyMinutesInBackgroundRollsOver() {
        val (s1, _) = tracker.onForeground(LerixSessionState(), 0, coldStart = true)
        val (s2, _) = tracker.onBackground(s1, 10_000)
        assertEquals("s1", tracker.currentId(s2, 10_000 + 29 * min))
        assertNull(tracker.currentId(s2, 10_000 + 30 * min))
        val (s3, events) = tracker.onForeground(s2, 10_000 + 30 * min, coldStart = false)
        assertEquals("s2", s3.sessionId)
        assertEquals(listOf("\$session_start"), events.map { it.name })
        assertEquals(false, events[0].props["coldStart"])
    }

    @Test
    fun coldStartWithinWindowKeepsPersistedSession() {
        val (s1, _) = tracker.onForeground(LerixSessionState(), 0, coldStart = true)
        val (s2, _) = tracker.onBackground(s1, 10_000)
        val (s3, events) = tracker.onForeground(s2, 10_000 + 5 * min, coldStart = true)
        assertTrue(events.isEmpty())
        assertEquals("s1", s3.sessionId)
    }

    @Test
    fun killedSessionIsEndedOnTheNextColdStart() {
        val (s1, _) = tracker.onForeground(LerixSessionState(), 0, coldStart = true)
        val active = s1.copy(lastActiveAt = 45_000) // process died in the foreground
        val (s2, events) = tracker.onForeground(active, 50 * min, coldStart = true)
        assertEquals(listOf("\$session_end", "\$session_start"), events.map { it.name })
        assertEquals("s1", events[0].sessionId)
        assertEquals(45_000L, events[0].ts)
        assertEquals(45L, events[0].props["duration"])
        assertEquals("s2", s2.sessionId)
    }

    @Test
    fun repeatedForegroundWhileForegroundIsANoOp() {
        val (s1, _) = tracker.onForeground(LerixSessionState(), 0, coldStart = true)
        val (s2, events) = tracker.onForeground(s1, 5_000, coldStart = false)
        assertTrue(events.isEmpty())
        assertEquals("s1", s2.sessionId)
    }

    @Test
    fun sessionEndHasAFixedEventIdPerSession() {
        assertEquals("end_s1", LerixSessionTracker.endEventId("s1"))
    }

    @Test
    fun backgroundWithoutSessionEmitsNothing() {
        val (_, events) = tracker.onBackground(LerixSessionState(), 1_000)
        assertTrue(events.isEmpty())
    }
}
