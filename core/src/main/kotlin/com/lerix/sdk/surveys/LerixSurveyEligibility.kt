package com.lerix.sdk.surveys

/** FNV-1a 32-bit over the UTF-8 bytes of a string, as an unsigned value in a Long (0..2^32-1). */
internal object LerixFnv1a {
    private const val OFFSET_BASIS = 0x811c9dc5L
    private const val PRIME = 0x01000193L
    private const val MASK = 0xffffffffL

    fun hash32(input: String): Long {
        var hash = OFFSET_BASIS
        for (b in input.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toLong() and 0xff)
            hash = (hash * PRIME) and MASK
        }
        return hash
    }

    /** `(fnv1a32(s) % 10000) / 10000`, in [0, 1). */
    fun bucket(input: String): Double = (hash32(input) % 10_000L) / 10_000.0
}

internal data class LerixSurveyRules(
    val sampleRate: Double,
    val frequencyDays: Int,
    val initialDelayDays: Int,
    val minAppStarts: Int,
    val dismissCooldownDays: Int,
)

internal data class LerixSurveyLocalState(
    val firstStartAt: Long,
    val appStarts: Long,
    val lastShownAt: Long? = null,
    val lastAnsweredAt: Long? = null,
    val lastDismissedAt: Long? = null,
)

internal data class LerixEligibility(
    val eligible: Boolean,
    /** Human-readable failing conditions (logged in debug mode). */
    val failing: List<String>,
    val nextDue: Long,
)

/** Client eligibility, identical in every SDK (contract §2.2). Times are epoch ms. */
internal object LerixSurveyEligibility {
    const val DAY_MS = 86_400_000L

    /**
     * When the survey may next be shown. A rating ([rating] true, plan §4.2) has
     * no first-showing spread: it is shown at a moment the app picks, so before
     * any answer or dismiss it is due from `firstStartAt`. `frequencyDays` 0
     * means no gap after an answer.
     */
    fun nextDue(
        surveyId: String,
        installId: String,
        rules: LerixSurveyRules,
        state: LerixSurveyLocalState,
        rating: Boolean = false,
    ): Long {
        val answered = state.lastAnsweredAt?.let { it + rules.frequencyDays * DAY_MS }
        val dismissed = state.lastDismissedAt?.let { it + rules.dismissCooldownDays * DAY_MS }
        return when {
            // Both set: the later event's rule; a tie goes to the answer (same as the iOS/web/Flutter SDKs).
            answered != null && dismissed != null ->
                if (state.lastAnsweredAt!! >= state.lastDismissedAt!!) answered else dismissed
            answered != null -> answered
            dismissed != null -> dismissed
            rating -> state.firstStartAt
            else -> {
                val windowSeconds = rules.frequencyDays.toLong() * 86_400L
                val spread = if (windowSeconds > 0) {
                    LerixFnv1a.hash32(installId + surveyId + "spread") % windowSeconds
                } else {
                    0L
                }
                state.firstStartAt + spread * 1000L
            }
        }
    }

    fun evaluate(
        surveyId: String,
        installId: String,
        rules: LerixSurveyRules,
        state: LerixSurveyLocalState,
        now: Long,
        force: Boolean = false,
        rating: Boolean = false,
    ): LerixEligibility {
        val due = nextDue(surveyId, installId, rules, state, rating)
        if (force) return LerixEligibility(true, emptyList(), due)
        val failing = mutableListOf<String>()
        if (state.appStarts < rules.minAppStarts) {
            failing += "appStarts ${state.appStarts} < minAppStarts ${rules.minAppStarts}"
        }
        val delayEnd = state.firstStartAt + rules.initialDelayDays * DAY_MS
        if (now < delayEnd) failing += "initialDelayDays ${rules.initialDelayDays} not reached"
        val bucket = LerixFnv1a.bucket(installId + surveyId)
        if (bucket >= rules.sampleRate) failing += "bucket $bucket >= sampleRate ${rules.sampleRate}"
        if (now < due) failing += "not due yet"
        return LerixEligibility(failing.isEmpty(), failing, due)
    }
}
