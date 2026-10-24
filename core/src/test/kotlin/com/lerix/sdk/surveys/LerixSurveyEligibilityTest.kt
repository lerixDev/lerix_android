package com.lerix.sdk.surveys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LerixFnv1aTest {
    @Test
    fun knownVectors() {
        assertEquals(0x811c9dc5L, LerixFnv1a.hash32(""))
        assertEquals(0xe40c292cL, LerixFnv1a.hash32("a"))
        assertEquals(0xbf9cf968L, LerixFnv1a.hash32("foobar"))
    }

    @Test
    fun hashesUtf8BytesAndStaysUnsigned() {
        // Multi-byte input exercises the signed-byte path.
        val h = LerixFnv1a.hash32("مرحبا 👋")
        assertTrue(h in 0L..0xffffffffL)
        assertEquals(h, LerixFnv1a.hash32("مرحبا 👋"))
        assertTrue(LerixFnv1a.hash32("ÿÿÿÿ") in 0L..0xffffffffL)
    }

    @Test
    fun bucketIsHashModulo10000() {
        val h = LerixFnv1a.hash32("install-1survey-1")
        assertEquals((h % 10_000) / 10_000.0, LerixFnv1a.bucket("install-1survey-1"), 0.0)
        assertTrue(LerixFnv1a.bucket("x") < 1.0)
    }
}

class LerixSurveyEligibilityTest {
    private val day = LerixSurveyEligibility.DAY_MS
    private val rules = LerixSurveyRules(
        sampleRate = 1.0,
        frequencyDays = 90,
        initialDelayDays = 7,
        minAppStarts = 3,
        dismissCooldownDays = 14,
    )
    private val surveyId = "7f1c2a3e-0000-4000-8000-000000000001"
    private val installId = "install-abc"
    private val t0 = 1_760_000_000_000L

    private fun spreadMs(): Long =
        (LerixFnv1a.hash32(installId + surveyId + "spread") % (90L * 86_400L)) * 1000L

    @Test
    fun firstNextDueIsSpreadAcrossTheFrequencyWindow() {
        val state = LerixSurveyLocalState(firstStartAt = t0, appStarts = 10)
        val due = LerixSurveyEligibility.nextDue(surveyId, installId, rules, state)
        assertEquals(t0 + spreadMs(), due)
        assertTrue(due >= t0 && due < t0 + 90 * day)
    }

    @Test
    fun eligibleWhenEveryRulePasses() {
        val state = LerixSurveyLocalState(firstStartAt = t0, appStarts = 3)
        val now = t0 + 91 * day
        val result = LerixSurveyEligibility.evaluate(surveyId, installId, rules, state, now)
        assertTrue(result.failing.toString(), result.eligible)
    }

    @Test
    fun failsOnMinAppStartsAndInitialDelay() {
        val state = LerixSurveyLocalState(firstStartAt = t0, appStarts = 2)
        val result = LerixSurveyEligibility.evaluate(surveyId, installId, rules, state, t0 + 3 * day)
        assertFalse(result.eligible)
        assertTrue(result.failing.any { it.startsWith("appStarts") })
        assertTrue(result.failing.any { it.startsWith("initialDelayDays") })
    }

    @Test
    fun sampleRateZeroExcludesEveryone() {
        val state = LerixSurveyLocalState(firstStartAt = t0, appStarts = 99)
        val r = LerixSurveyEligibility.evaluate(surveyId, installId, rules.copy(sampleRate = 0.0), state, t0 + 200 * day)
        assertFalse(r.eligible)
        assertTrue(r.failing.any { it.startsWith("bucket") })
    }

    @Test
    fun sampleRateUsesInstallAndSurveyBucket() {
        val bucket = LerixFnv1a.bucket(installId + surveyId)
        val state = LerixSurveyLocalState(firstStartAt = t0, appStarts = 99)
        val now = t0 + 200 * day
        assertTrue(LerixSurveyEligibility.evaluate(surveyId, installId, rules.copy(sampleRate = bucket + 0.0001), state, now).eligible)
        assertFalse(LerixSurveyEligibility.evaluate(surveyId, installId, rules.copy(sampleRate = bucket), state, now).eligible)
    }

    @Test
    fun answeredUsesFrequency() {
        val answered = t0 + 100 * day
        val state = LerixSurveyLocalState(firstStartAt = t0, appStarts = 9, lastShownAt = answered, lastAnsweredAt = answered)
        assertEquals(answered + 90 * day, LerixSurveyEligibility.nextDue(surveyId, installId, rules, state))
        assertFalse(LerixSurveyEligibility.evaluate(surveyId, installId, rules, state, answered + 89 * day).eligible)
        assertTrue(LerixSurveyEligibility.evaluate(surveyId, installId, rules, state, answered + 90 * day).eligible)
    }

    @Test
    fun dismissedUsesCooldownNotFrequency() {
        val dismissed = t0 + 100 * day
        val state = LerixSurveyLocalState(firstStartAt = t0, appStarts = 9, lastShownAt = dismissed, lastDismissedAt = dismissed)
        assertEquals(dismissed + 14 * day, LerixSurveyEligibility.nextDue(surveyId, installId, rules, state))
    }

    @Test
    fun bothSetUsesTheLaterEventsRuleAndATieGoesToTheAnswer() {
        val answered = t0 + 100 * day
        // Dismissed after the answer: the dismiss cooldown applies.
        val dismissedLater = LerixSurveyLocalState(t0, 9, lastAnsweredAt = answered, lastDismissedAt = answered + 95 * day)
        assertEquals(answered + 109 * day, LerixSurveyEligibility.nextDue(surveyId, installId, rules, dismissedLater))
        // Answered after a dismiss: the frequency applies.
        val answeredLater = LerixSurveyLocalState(t0, 9, lastAnsweredAt = answered, lastDismissedAt = answered - 10 * day)
        assertEquals(answered + 90 * day, LerixSurveyEligibility.nextDue(surveyId, installId, rules, answeredLater))
        val tie = LerixSurveyLocalState(t0, 9, lastAnsweredAt = answered, lastDismissedAt = answered)
        assertEquals(answered + 90 * day, LerixSurveyEligibility.nextDue(surveyId, installId, rules, tie))
    }

    @Test
    fun forceSkipsEveryCheck() {
        val state = LerixSurveyLocalState(firstStartAt = t0, appStarts = 0)
        val r = LerixSurveyEligibility.evaluate(surveyId, installId, rules.copy(sampleRate = 0.0), state, t0, force = true)
        assertTrue(r.eligible)
    }

    @Test
    fun zeroFrequencyDoesNotDivideByZero() {
        val state = LerixSurveyLocalState(firstStartAt = t0, appStarts = 9)
        assertEquals(t0, LerixSurveyEligibility.nextDue(surveyId, installId, rules.copy(frequencyDays = 0), state))
    }

    @Test
    fun npsGroups() {
        assertEquals("detractor", npsGroup(0))
        assertEquals("detractor", npsGroup(6))
        assertEquals("passive", npsGroup(7))
        assertEquals("passive", npsGroup(8))
        assertEquals("promoter", npsGroup(9))
        assertEquals("promoter", npsGroup(10))
    }
}
