package com.lerix.sdk.surveys

import com.lerix.sdk.core.LerixIds
import com.lerix.sdk.core.LerixJson
import com.lerix.sdk.core.LerixLog
import com.lerix.sdk.core.LerixTime
import org.json.JSONObject

/**
 * A due survey for your own UI (`Lerix.surveys.dueNps()` / `dueRating(key)`).
 * Nothing is recorded until you call [shown]; [answer] and [dismiss] call it
 * for you if you didn't. Each prompt is one showing: get a new one for the next.
 */
class LerixSurveyPrompt internal constructor(private val run: LerixSurveyRun) {
    val id: String get() = run.survey.id

    /** `nps` or `rating`. */
    val type: String get() = run.survey.type

    /** The survey key set in the dashboard (always set for a rating). */
    val key: String? get() = run.survey.key

    /** The question in the app's language, or null when the dashboard has none. */
    val question: String? get() = LerixSurveyText.pick(run.survey.question, run.lang)

    /** Rating: the choices in display order. NPS: empty (answer with 0–10). */
    val choices: List<LerixSurveyChoice> get() = run.choices

    val appearance: LerixSurveyAppearance get() = run.survey.appearance.localize(run.lang)

    /** NPS: detractor / passive / promoter for a 0–10 score. Rating: the group of the choice with that value. */
    fun groupOf(score: Int): String? = run.groupOf(score)

    /** Rating: the group of the choice with that id (or value). NPS: the group of a score given as text. */
    fun groupOf(choiceId: String): String? = run.groupOf(choiceId)

    /** The follow-up question for a group, or null when the dashboard has none. */
    fun followUp(group: String): String? = LerixSurveyText.pick(run.survey.followUp[group], run.lang)

    /** The thank-you text for a group, or null when the dashboard has none. */
    fun thanks(group: String): String? = LerixSurveyText.pick(run.survey.thanks[group], run.lang)

    /** Records that the survey is on screen. Call once when you show it. */
    fun shown() = run.shown()

    /**
     * Records the answer: an NPS score 0–10, or the value of a rating choice.
     * Call again with the same answer and a [message] to add the follow-up
     * text. Returns false when the answer doesn't fit the survey.
     */
    @JvmOverloads
    fun answer(score: Int, message: String? = null): Boolean = run.answer(score, message)

    /** Records the answer by rating choice id (or its value as text). Returns false when it doesn't fit. */
    @JvmOverloads
    fun answer(choiceId: String, message: String? = null): Boolean = run.answer(choiceId, message)

    /** Records that it was closed without an answer. Ignored after [answer]. */
    fun dismiss() = run.dismiss()
}

/** Where a showing writes: local state, the merged outbox item, the send trigger. */
internal interface LerixSurveySink {
    fun saveState(surveyId: String, what: String, at: Long)
    fun record(clientId: String, surveyId: String, body: JSONObject)
    fun flush()
    fun breadcrumb(message: String, data: Map<String, Any?>) {}
}

/**
 * One showing of one survey: a client id and the reports it makes ("shown",
 * then "answered" or "dismissed", plus the follow-up message), all upserted on
 * the client id. The built-in sheets and the headless prompt both go through
 * it, so they record exactly the same thing.
 */
internal class LerixSurveyRun(
    private val sink: LerixSurveySink,
    val survey: LerixSurvey,
    val lang: String,
    custom: Map<String, Any?>? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val clientId: String = LerixIds.newId()
    val choices: List<LerixSurveyChoice> = survey.options.map { it.localize(lang) }
    private val customJson: JSONObject? = custom?.takeIf { it.isNotEmpty() }?.let { LerixJson.objectOf(it) }
    private val isNps get() = survey.type == "nps"

    private var shownAt: String? = null
    private var answered: JSONObject? = null
    private var dismissed = false

    @Synchronized
    fun shown() {
        if (shownAt != null) return
        val at = clock()
        shownAt = LerixTime.iso(at)
        sink.saveState(survey.id, "shown", at)
        sink.record(clientId, survey.id, snapshot())
        sink.flush()
        val data = LinkedHashMap<String, Any?>().apply {
            put("surveyId", survey.id)
            survey.key?.let { put("key", it) }
        }
        sink.breadcrumb(if (isNps) "NPS survey shown" else "Rating survey shown", data)
    }

    /** The answer fields for this survey type, or null when the answer doesn't fit. */
    private fun fields(score: Int?, text: String?): JSONObject? {
        if (isNps) {
            val n = score ?: text?.trim()?.toIntOrNull() ?: return null
            return if (n in 0..10) JSONObject().put("score", n) else null
        }
        val choice = if (score != null) {
            choices.firstOrNull { it.value == score }
        } else {
            findChoice(choices, text ?: return null, { it.id }, { it.value })
        } ?: return null
        return JSONObject().put("choice", choice.id).put("score", choice.value)
    }

    fun groupOf(score: Int): String? = group(fields(score, null))

    fun groupOf(text: String): String? = group(fields(null, text))

    private fun group(fields: JSONObject?): String? {
        fields ?: return null
        if (isNps) return npsGroup(fields.getInt("score"))
        val id = fields.getString("choice")
        return choices.firstOrNull { it.id == id }?.group
    }

    fun answer(score: Int, message: String?): Boolean = answer(fields(score, null), "$score", message)

    fun answer(choiceId: String, message: String?): Boolean = answer(fields(null, choiceId), "\"$choiceId\"", message)

    @Synchronized
    private fun answer(fields: JSONObject?, label: String, message: String?): Boolean {
        if (fields == null) {
            LerixLog.debug("survey answer $label doesn't fit survey ${survey.id}")
            return false
        }
        shown()
        if (answered == null) {
            val at = clock()
            answered = fields.put("answeredAt", LerixTime.iso(at))
            sink.saveState(survey.id, "answered", at)
            sink.record(clientId, survey.id, snapshot())
            sink.flush()
        }
        if (message != null) message(message)
        return true
    }

    @Synchronized
    fun message(message: String) {
        val text = message.trim().take(MESSAGE_MAX)
        if (text.isEmpty()) return
        sink.record(clientId, survey.id, snapshot().put("message", text))
        sink.flush()
    }

    @Synchronized
    fun dismiss() {
        if (answered != null || dismissed) return
        shown()
        dismissed = true
        val at = clock()
        sink.saveState(survey.id, "dismissed", at)
        sink.record(clientId, survey.id, snapshot().put("dismissedAt", LerixTime.iso(at)))
        sink.flush()
    }

    /** The full body so far: every upsert carries `shownAt`, `custom` and the answer. */
    private fun snapshot(): JSONObject = JSONObject().apply {
        put("shownAt", shownAt)
        customJson?.let { put("custom", JSONObject(it.toString())) }
        answered?.let { a -> a.keys().forEach { k -> put(k, a.get(k)) } }
    }

    fun toPrompt() = LerixSurveyPrompt(this)

    companion object {
        const val MESSAGE_MAX = 2000
    }
}
