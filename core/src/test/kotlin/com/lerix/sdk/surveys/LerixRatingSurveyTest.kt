package com.lerix.sdk.surveys

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val CONFIG = """
{
  "surveys": [
    { "id": "nps-1", "type": "nps", "key": null, "question": { "en": "Recommend us?" },
      "appearance": { "shape": "pill", "accent": "#30a46c", "colorMode": "groups",
                      "groupColors": { "detractor": "#111111", "passive": "nope" } } },
    { "id": "rate-1", "type": "rating", "key": "checkout",
      "question": { "en": "How was checkout?", "ar": "كيف كانت تجربة الدفع؟" },
      "options": [
        { "id": "o1", "value": 1, "group": "negative", "emoji": "😞", "label": { "en": "Bad", "ar": "سيئ" } },
        { "id": "o2", "value": 2, "group": "neutral", "emoji": "😐" },
        { "id": "o3", "value": 3, "group": "positive", "emoji": "😍", "color": "#30A46C" }
      ],
      "followUp": { "negative": { "en": "What went wrong?" } },
      "thanks": { "positive": { "en": "Glad to hear it!" } },
      "appearance": { "style": "emoji", "lowLabel": { "en": "Bad", "ar": "سيئ" }, "accent": "green", "showLabels": false },
      "frequencyDays": 0 }
  ]
}
"""

class LerixSurveyParseTest {
    @Test
    fun readsBothTypesWithRatingDefaultsAndOptionsInOrder() {
        val (nps, rating) = LerixSurvey.listFromJson(CONFIG)
        assertEquals("nps-1", nps.id)
        assertEquals("nps", nps.type)
        assertNull(nps.key)
        assertTrue(nps.options.isEmpty())
        assertEquals(90, nps.rules.frequencyDays)
        assertEquals(3, nps.rules.minAppStarts)
        assertEquals("pill", nps.appearance.shape)
        assertEquals("#30a46c", nps.appearance.accent)
        assertEquals("groups", nps.appearance.colorMode)
        assertEquals(mapOf("detractor" to "#111111"), nps.appearance.groupColors)

        assertEquals("rating", rating.type)
        assertEquals("checkout", rating.key)
        assertEquals(LerixSurveyRules(1.0, 0, 0, 0, 7), rating.rules)
        assertEquals(listOf("o1", "o2", "o3"), rating.options.map { it.id })
        assertEquals("#30A46C", rating.options[2].color)
        assertNull(rating.options[1].color)
    }

    @Test
    fun ratingCadenceFallsBackTo30_0_0_7() {
        val o = JSONObject("""{ "id": "r", "type": "rating", "key": "k", "options": [
            { "id": "a", "value": 0, "group": "negative" }, { "id": "b", "value": 100, "group": "positive" } ] }""")
        assertEquals(LerixSurveyRules(1.0, 30, 0, 0, 7), LerixSurvey.fromJson(o)!!.rules)
    }

    @Test
    fun dropsABadValueInsteadOfTheSurvey() {
        val rating = LerixSurvey.listFromJson(CONFIG)[1]
        assertNull(rating.appearance.accent)
        assertEquals("emoji", rating.appearance.style)
        assertFalse(rating.appearance.showLabels)
        assertTrue(LerixSurvey.listFromJson(CONFIG)[0].appearance.showLabels)
    }

    @Test
    fun skipsUnreadableEntriesAndShortRatingsKeepsTheRest() {
        val raw = """{ "surveys": [
            null,
            { "type": "nps" },
            { "id": 7, "type": "nps" },
            { "id": "r", "type": "rating", "options": [ { "id": "a", "value": 1, "group": "positive" },
                                                       { "id": "b", "value": "x", "group": "positive" } ] },
            { "id": "r2", "type": "rating", "options": [ { "id": "a", "value": 1, "group": "positive" },
                                                        { "id": "a", "value": 2, "group": "positive" },
                                                        { "id": "c", "value": 1.5, "group": "positive" },
                                                        { "id": "d", "value": 3, "group": "great" } ] },
            { "id": "future", "type": "poll" },
            { "id": "nps-1", "type": "nps", "appearance": "bad", "question": { "en": 5, "ar": "سؤال" } }
        ] }"""
        val out = LerixSurvey.listFromJson(raw)
        assertEquals(listOf("future", "nps-1"), out.map { it.id })
        assertEquals(mapOf("ar" to "سؤال"), out[1].question)
        assertEquals(LerixAppearanceSpec(), out[1].appearance)
        assertTrue(LerixSurvey.listFromJson("not json").isEmpty())
        assertTrue(LerixSurvey.listFromJson("""{ "surveys": 3 }""").isEmpty())
    }

    @Test
    fun localizesTheAppearanceLabelsAndChoices() {
        val rating = LerixSurvey.listFromJson(CONFIG)[1]
        val ar = rating.appearance.localize("ar")
        assertEquals("emoji", ar.style)
        assertEquals("سيئ", ar.lowLabel)
        assertNull(ar.highLabel)
        assertNull(ar.accent)
        assertEquals("سيئ", rating.options[0].localize("ar").label)
        assertEquals("Bad", rating.options[0].localize("fr").label)
        assertNull(rating.options[1].localize("en").label)
    }

    @Test
    fun resolvesTheStyleLikeTheWebSdk() {
        assertEquals("emoji", LerixRatingDialog.resolveStyle("emoji", 5))
        assertEquals("thumbs", LerixRatingDialog.resolveStyle("thumbs", 2))
        assertEquals("stars", LerixRatingDialog.resolveStyle("thumbs", 3))
        assertEquals("stars", LerixRatingDialog.resolveStyle("sliders", 5))
        assertEquals("stars", LerixRatingDialog.resolveStyle(null, 5))
    }

    @Test
    fun findChoiceMatchesTheIdFirstThenTheValue() {
        val choices = listOf("o1" to 1, "5" to 3)
        fun find(answer: String) = findChoice(choices, answer, { it.first }, { it.second })?.first
        assertEquals("o1", find("o1"))
        assertEquals("5", find("5"))
        assertEquals("o1", find("1"))
        assertEquals("5", find("3"))
        assertNull(find("nope"))
        assertNull(find(" "))
    }
}

class LerixRatingEligibilityTest {
    private val day = LerixSurveyEligibility.DAY_MS
    private val rules = LerixSurveyRules(1.0, 30, 0, 0, 7)
    private val first = 1_767_225_600_000L // 2026-01-01

    private fun eval(r: LerixSurveyRules, s: LerixSurveyLocalState, now: Long, id: String = "rate-1", install: String = "install-1") =
        LerixSurveyEligibility.evaluate(id, install, r, s, now, rating = true)

    @Test
    fun hasNoFirstShowingSpread() {
        val state = LerixSurveyLocalState(firstStartAt = first, appStarts = 0)
        assertEquals(first, LerixSurveyEligibility.nextDue("rate-1", "install-1", rules, state, rating = true))
        assertTrue(eval(rules, state, first).eligible)
        // NPS with the same rules is spread across the frequency window.
        val npsDue = LerixSurveyEligibility.nextDue("rate-1", "install-1", rules, state)
        assertEquals(first + (LerixFnv1a.hash32("install-1rate-1spread") % (30L * 86_400L)) * 1000L, npsDue)
    }

    @Test
    fun waitsFrequencyDaysAfterAnAnswerAndTheCooldownAfterADismiss() {
        val answered = first + day
        val after = LerixSurveyLocalState(first, 1, lastAnsweredAt = answered)
        assertEquals(answered + 30 * day, LerixSurveyEligibility.nextDue("rate-1", "i", rules, after, rating = true))
        val dismissed = LerixSurveyLocalState(first, 1, lastDismissedAt = answered)
        assertEquals(answered + 7 * day, LerixSurveyEligibility.nextDue("rate-1", "i", rules, dismissed, rating = true))
        assertFalse(eval(rules, after, answered + 29 * day).eligible)
        assertTrue(eval(rules, after, answered + 30 * day).eligible)
    }

    @Test
    fun laterEventWinsAndATieGoesToTheAnswer() {
        val t = first + day
        val dismissLater = LerixSurveyLocalState(first, 1, lastAnsweredAt = t, lastDismissedAt = t + day)
        assertEquals(t + day + 7 * day, LerixSurveyEligibility.nextDue("r", "i", rules, dismissLater, rating = true))
        val tie = LerixSurveyLocalState(first, 1, lastAnsweredAt = t, lastDismissedAt = t)
        assertEquals(t + 30 * day, LerixSurveyEligibility.nextDue("r", "i", rules, tie, rating = true))
    }

    @Test
    fun frequencyDaysZeroMeansNoGapAfterAnAnswer() {
        val now = first + day
        val state = LerixSurveyLocalState(first, 1, lastAnsweredAt = now)
        assertTrue(eval(rules.copy(frequencyDays = 0), state, now).eligible)
    }

    @Test
    fun keepsSamplingTheInitialDelayAndMinAppStarts() {
        val state = LerixSurveyLocalState(first, 0)
        assertFalse(eval(rules.copy(sampleRate = 0.0), state, first).eligible)
        assertFalse(eval(rules.copy(initialDelayDays = 2), state, first + day).eligible)
        assertFalse(eval(rules.copy(minAppStarts = 1), state, first).eligible)
        assertTrue(LerixSurveyEligibility.evaluate("r", "i", rules.copy(sampleRate = 0.0), state, first, force = true, rating = true).eligible)
    }
}

class LerixSurveyPromptTest {
    private class Sink : LerixSurveySink {
        val bodies = mutableListOf<JSONObject>()
        val clientIds = mutableSetOf<String>()
        val state = mutableMapOf<String, Long>()
        var flushes = 0
        val crumbs = mutableListOf<String>()
        override fun saveState(surveyId: String, what: String, at: Long) {
            state["${surveyId}_$what"] = at
        }
        override fun record(clientId: String, surveyId: String, body: JSONObject) {
            clientIds += clientId
            bodies += JSONObject(body.toString())
        }
        override fun flush() {
            flushes++
        }
        override fun breadcrumb(message: String, data: Map<String, Any?>) {
            crumbs += message
        }
    }

    private val surveys = LerixSurvey.listFromJson(CONFIG)
    private val keys = setOf("shownAt", "answeredAt", "choice", "score", "message", "dismissedAt")
    private fun shape(b: JSONObject) = b.keys().asSequence().filter { it in keys }.sorted().joinToString(",")

    @Test
    fun ratingRecordsShownTheChoiceWithItsValueThenTheMessageOnOneClientId() {
        val sink = Sink()
        val prompt = LerixSurveyRun(sink, surveys[1], "en", mapOf("orderId" to "A-1")).toPrompt()
        assertEquals("rate-1", prompt.id)
        assertEquals("rating", prompt.type)
        assertEquals("checkout", prompt.key)
        assertEquals("How was checkout?", prompt.question)
        assertEquals(listOf("😞", "😐", "😍"), prompt.choices.map { it.emoji })
        assertEquals("negative", prompt.groupOf("o1"))
        assertEquals("positive", prompt.groupOf(3))
        assertNull(prompt.groupOf("nope"))
        assertEquals("What went wrong?", prompt.followUp("negative"))
        assertNull(prompt.followUp("positive"))
        assertEquals("Glad to hear it!", prompt.thanks("positive"))
        assertEquals(0, sink.bodies.size) // nothing until shown()

        prompt.shown()
        prompt.shown()
        assertFalse(prompt.answer("nope"))
        assertFalse(prompt.answer(9))
        assertTrue(prompt.answer("o3"))
        assertTrue(prompt.answer("o3", "Fast!"))
        prompt.dismiss() // ignored after an answer

        assertEquals(listOf("shownAt", "answeredAt,choice,score,shownAt", "answeredAt,choice,message,score,shownAt"), sink.bodies.map(::shape))
        assertEquals(1, sink.clientIds.size)
        assertEquals("o3", sink.bodies[1].getString("choice"))
        assertEquals(3, sink.bodies[1].getInt("score"))
        assertEquals("A-1", sink.bodies[1].getJSONObject("custom").getString("orderId"))
        assertEquals("Fast!", sink.bodies[2].getString("message"))
        assertEquals(setOf("rate-1_shown", "rate-1_answered"), sink.state.keys)
        assertEquals(3, sink.flushes)
        assertEquals(listOf("Rating survey shown"), sink.crumbs)
    }

    @Test
    fun answerByValueStoresTheChoiceId() {
        val sink = Sink()
        val prompt = LerixSurveyRun(sink, surveys[1], "ar").toPrompt()
        assertEquals("كيف كانت تجربة الدفع؟", prompt.question)
        assertEquals("سيئ", prompt.appearance.lowLabel)
        assertTrue(prompt.answer(1))
        assertEquals("shownAt", shape(sink.bodies[0])) // shown() implied
        assertEquals("o1", sink.bodies[1].getString("choice"))
        assertFalse(sink.bodies[1].has("custom"))
    }

    @Test
    fun npsTakes0To10AndMapsTheGroup() {
        val sink = Sink()
        val prompt = LerixSurveyRun(sink, surveys[0], "en").toPrompt()
        assertEquals("nps", prompt.type)
        assertTrue(prompt.choices.isEmpty())
        assertEquals("detractor", prompt.groupOf(6))
        assertEquals("passive", prompt.groupOf(8))
        assertEquals("promoter", prompt.groupOf("10"))
        assertNull(prompt.groupOf(11))
        assertFalse(prompt.answer(11))
        assertFalse(prompt.answer(-1))
        assertTrue(prompt.answer(9))
        assertEquals(9, sink.bodies.last().getInt("score"))
        assertFalse(sink.bodies.last().has("choice"))
        assertEquals(listOf("NPS survey shown"), sink.crumbs)
    }

    @Test
    fun aDismissWithoutAnAnswerRecordsDismissedAtOnce() {
        val sink = Sink()
        val prompt = LerixSurveyRun(sink, surveys[1], "en").toPrompt()
        prompt.dismiss()
        prompt.dismiss()
        assertEquals(listOf("shownAt", "dismissedAt,shownAt"), sink.bodies.map(::shape))
        assertTrue(sink.state.containsKey("rate-1_dismissed"))
        // An answer after a dismiss still counts (the later event wins in eligibility).
        assertTrue(prompt.answer("o2"))
        assertEquals("o2", sink.bodies.last().getString("choice"))
    }

    @Test
    fun blankMessagesAreIgnoredAndLongOnesTrimmed() {
        val sink = Sink()
        val prompt = LerixSurveyRun(sink, surveys[0], "en").toPrompt()
        assertTrue(prompt.answer(7, "   "))
        assertEquals(2, sink.bodies.size)
        assertTrue(prompt.answer(7, "x".repeat(2500)))
        assertEquals(2000, sink.bodies.last().getString("message").length)
        assertEquals(7, sink.bodies.last().getInt("score"))
    }
}
