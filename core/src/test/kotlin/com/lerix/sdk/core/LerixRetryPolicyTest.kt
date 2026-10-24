package com.lerix.sdk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LerixRetryPolicyTest {
    @Test
    fun decisionsFollowTheContract() {
        assertEquals(LerixRetryDecision.SUCCESS, LerixRetryPolicy.decide(200))
        assertEquals(LerixRetryDecision.SUCCESS, LerixRetryPolicy.decide(201))
        for (s in listOf(400, 413, 422)) assertEquals("$s", LerixRetryDecision.DROP, LerixRetryPolicy.decide(s))
        for (s in listOf(401, 403, 404, 429, 500, 502, 503)) assertEquals("$s", LerixRetryDecision.RETRY, LerixRetryPolicy.decide(s))
        assertEquals(LerixRetryDecision.REREGISTER, LerixRetryPolicy.decide(409))
        assertEquals(LerixRetryDecision.RETRY, LerixRetryPolicy.decide(null)) // network
    }

    @Test
    fun invalidAppUserCodesReRegisterInsteadOfDropping() {
        assertEquals(LerixRetryDecision.REREGISTER, LerixRetryPolicy.decide(400, "USER_PROJECT_NOT_EXIST"))
        assertEquals(LerixRetryDecision.REREGISTER, LerixRetryPolicy.decide(403, "PROJECT_USER_MISMATCH"))
        assertEquals(LerixRetryDecision.DROP, LerixRetryPolicy.decide(400, "VALIDATION_FAILED"))
        assertEquals(LerixRetryDecision.SUCCESS, LerixRetryPolicy.decide(200, "USER_PROJECT_NOT_EXIST"))
    }

    @Test
    fun unauthorizedNeverDeletes() {
        val d = LerixRetryPolicy.decide(401)
        assertTrue(d != LerixRetryDecision.DROP && d != LerixRetryDecision.SUCCESS)
    }

    @Test
    fun backoffGrowsFrom2sTo5MinWithJitter() {
        val random = Random(42)
        for (failures in 1..30) {
            val ms = LerixRetryPolicy.backoffMs(failures, random)
            assertTrue("$failures → $ms", ms in LerixRetryPolicy.MIN_BACKOFF_MS..LerixRetryPolicy.MAX_BACKOFF_MS)
        }
        assertTrue(LerixRetryPolicy.backoffMs(1, Random(1)) <= 2_500)
        assertTrue(LerixRetryPolicy.backoffMs(20, Random(1)) >= 225_000)
    }

    @Test
    fun backoffStateGatesAttempts() {
        var now = 1_000L
        val backoff = LerixBackoff { now }
        assertTrue(backoff.canAttempt())
        backoff.onFailure()
        assertFalse(backoff.canAttempt())
        now += LerixRetryPolicy.MAX_BACKOFF_MS
        assertTrue(backoff.canAttempt())
        backoff.onSuccess()
        assertEquals(0, backoff.failures)
    }

    @Test
    fun clientIdFormat() {
        assertTrue(LerixIds.isValidClientId(LerixIds.newId()))
        assertTrue(LerixIds.isValidClientId("V1StGXR8_Z5jdHi6B-myT"))
        assertFalse(LerixIds.isValidClientId("short"))
        assertFalse(LerixIds.isValidClientId("has space in it"))
    }
}
