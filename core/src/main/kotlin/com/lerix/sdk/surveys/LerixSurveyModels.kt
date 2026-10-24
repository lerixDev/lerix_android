package com.lerix.sdk.surveys

import org.json.JSONArray
import org.json.JSONObject

/** A survey's look as set in the dashboard. Texts are picked for the app's language. */
data class LerixSurveyAppearance(
    /** NPS: `numbers`. Rating: `stars`, `hearts`, `emoji`, `thumbs` or `numbers`. Null: the default. */
    val style: String? = null,
    /** `rounded`, `circle`, `square` or `pill`. Null: `rounded`. */
    val shape: String? = null,
    /** `accent`, `groups` (NPS) or `options` (rating). Null: `accent`. */
    val colorMode: String? = null,
    /** `#RRGGBB`, or null to use the app's colour. */
    val accent: String? = null,
    /** NPS `colorMode = "groups"`: `detractor` / `passive` / `promoter` → `#RRGGBB`. */
    val groupColors: Map<String, String> = emptyMap(),
    /** End label under the low end of the scale, or null for the SDK's text. */
    val lowLabel: String? = null,
    /** End label under the high end of the scale, or null for the SDK's text. */
    val highLabel: String? = null,
    /** Whether the end labels / choice labels are shown (default true). */
    val showLabels: Boolean = true,
)

/** One choice of a rating survey, with its label picked for the app's language. */
data class LerixSurveyChoice(
    val id: String,
    val value: Int,
    /** `negative`, `neutral` or `positive`. */
    val group: String,
    val label: String?,
    val emoji: String?,
    /** `#RRGGBB` or null. */
    val color: String?,
)

/** `appearance` as sent by the server, labels still per language. */
internal data class LerixAppearanceSpec(
    val style: String? = null,
    val shape: String? = null,
    val colorMode: String? = null,
    val accent: String? = null,
    val groupColors: Map<String, String> = emptyMap(),
    val lowLabel: Map<String, String> = emptyMap(),
    val highLabel: Map<String, String> = emptyMap(),
    val showLabels: Boolean = true,
) {
    fun localize(lang: String) = LerixSurveyAppearance(
        style = style,
        shape = shape,
        colorMode = colorMode,
        accent = accent,
        groupColors = groupColors,
        lowLabel = LerixSurveyText.pick(lowLabel, lang),
        highLabel = LerixSurveyText.pick(highLabel, lang),
        showLabels = showLabels,
    )
}

/** One rating option as sent by the server. */
internal data class LerixRatingOption(
    val id: String,
    val value: Int,
    val group: String,
    val label: Map<String, String>,
    val emoji: String?,
    val color: String?,
) {
    fun localize(lang: String) = LerixSurveyChoice(id, value, group, LerixSurveyText.pick(label, lang), emoji, color)
}

internal data class LerixSurvey(
    val id: String,
    val type: String,
    val key: String? = null,
    val question: Map<String, String> = emptyMap(),
    val followUp: Map<String, Map<String, String>> = emptyMap(),
    val thanks: Map<String, Map<String, String>> = emptyMap(),
    val appearance: LerixAppearanceSpec = LerixAppearanceSpec(),
    /** Rating only, in display order. */
    val options: List<LerixRatingOption> = emptyList(),
    val rules: LerixSurveyRules,
) {
    companion object {
        private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")
        private val RATING_GROUPS = setOf("negative", "neutral", "positive")
        private val NPS_GROUPS = listOf("detractor", "passive", "promoter")

        /**
         * One entry of `GET /plugin/surveys/config`, or null when it can't be
         * read (no string id, or a rating with fewer than two readable
         * options). A bad field falls back to its default instead of failing
         * the survey. Unknown types are kept (and never shown).
         */
        fun fromJson(o: JSONObject): LerixSurvey? {
            val id = (o.opt("id") as? String)?.takeIf { it.isNotEmpty() } ?: return null
            val type = o.opt("type") as? String ?: "nps"
            val rating = type == "rating"
            val options = if (rating) options(o.opt("options")) else emptyList()
            if (rating && options.size < 2) return null
            return LerixSurvey(
                id = id,
                type = type,
                key = o.opt("key") as? String,
                question = text(o.opt("question")),
                followUp = groups(o.opt("followUp")),
                thanks = groups(o.opt("thanks")),
                appearance = appearance(o.opt("appearance")),
                options = options,
                rules = LerixSurveyRules(
                    sampleRate = number(o, "sampleRate") ?: 1.0,
                    frequencyDays = number(o, "frequencyDays")?.toInt() ?: if (rating) 30 else 90,
                    initialDelayDays = number(o, "initialDelayDays")?.toInt() ?: if (rating) 0 else 7,
                    minAppStarts = number(o, "minAppStarts")?.toInt() ?: if (rating) 0 else 3,
                    dismissCooldownDays = number(o, "dismissCooldownDays")?.toInt() ?: if (rating) 7 else 14,
                ),
            )
        }

        /** The `surveys` array, lossily: unreadable entries are skipped, the rest kept. */
        fun listFromJson(raw: String?): List<LerixSurvey> {
            if (raw == null) return emptyList()
            val arr = runCatching { JSONObject(raw).optJSONArray("surveys") }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                (arr.opt(i) as? JSONObject)?.let { runCatching { fromJson(it) }.getOrNull() }
            }
        }

        fun color(value: Any?): String? = (value as? String)?.takeIf { HEX_COLOR.matches(it) }

        private fun number(o: JSONObject, key: String): Double? {
            val n = when (val v = o.opt(key)) {
                is Number -> v.toDouble()
                is String -> v.trim().toDoubleOrNull()
                else -> null
            }
            return n?.takeIf { it.isFinite() }
        }

        private fun integer(value: Any?): Int? {
            val d = when (value) {
                is Number -> value.toDouble()
                is String -> value.trim().toDoubleOrNull()
                else -> null
            } ?: return null
            return if (d.isFinite() && d == Math.floor(d) && d in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) d.toInt() else null
        }

        /** A localized text map; only non-empty strings are kept. */
        private fun text(value: Any?): Map<String, String> {
            val obj = value as? JSONObject ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            obj.keys().forEach { k -> (obj.opt(k) as? String)?.takeIf { it.isNotEmpty() }?.let { out[k] = it } }
            return out
        }

        private fun groups(value: Any?): Map<String, Map<String, String>> {
            val obj = value as? JSONObject ?: return emptyMap()
            val out = LinkedHashMap<String, Map<String, String>>()
            obj.keys().forEach { k -> out[k] = text(obj.opt(k)) }
            return out
        }

        private fun appearance(value: Any?): LerixAppearanceSpec {
            val a = value as? JSONObject ?: return LerixAppearanceSpec()
            val groupColors = LinkedHashMap<String, String>()
            (a.opt("groupColors") as? JSONObject)?.let { g ->
                NPS_GROUPS.forEach { name -> color(g.opt(name))?.let { groupColors[name] = it } }
            }
            return LerixAppearanceSpec(
                style = a.opt("style") as? String,
                shape = a.opt("shape") as? String,
                colorMode = a.opt("colorMode") as? String,
                accent = color(a.opt("accent")),
                groupColors = groupColors,
                lowLabel = text(a.opt("lowLabel")),
                highLabel = text(a.opt("highLabel")),
                showLabels = a.opt("showLabels") as? Boolean ?: true,
            )
        }

        private fun options(value: Any?): List<LerixRatingOption> {
            val arr = value as? JSONArray ?: return emptyList()
            val out = mutableListOf<LerixRatingOption>()
            val ids = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.opt(i) as? JSONObject ?: continue
                val id = o.opt("id") as? String ?: continue
                if (id in ids) continue
                val v = integer(o.opt("value")) ?: continue
                val group = (o.opt("group") as? String)?.takeIf { it in RATING_GROUPS } ?: continue
                ids += id
                out += LerixRatingOption(
                    id = id,
                    value = v,
                    group = group,
                    label = text(o.opt("label")),
                    emoji = (o.opt("emoji") as? String)?.takeIf { it.isNotEmpty() },
                    color = color(o.opt("color")),
                )
            }
            return out
        }
    }
}

/** NPS group for a 0..10 score. */
internal fun npsGroup(score: Int): String = when {
    score <= 6 -> "detractor"
    score <= 8 -> "passive"
    else -> "promoter"
}

/** The rating choice an answer names: its id first, else its value (lerix_core `findChoice`). */
internal fun <T> findChoice(choices: List<T>, answer: String, id: (T) -> String, value: (T) -> Int): T? {
    choices.firstOrNull { id(it) == answer }?.let { return it }
    val n = answer.trim().takeIf { it.isNotEmpty() }?.toIntOrNull() ?: return null
    return choices.firstOrNull { value(it) == n }
}

internal object LerixSurveyText {
    /** `map[lang]`, then the same base language, then `map.en`, else null (contract §1.1). */
    fun pick(map: Map<String, String>?, lang: String): String? {
        if (map.isNullOrEmpty()) return null
        return map[lang]?.takeIf { it.isNotBlank() }
            ?: map.entries.firstOrNull { it.key.substringBefore('-').equals(lang, true) && it.value.isNotBlank() }?.value
            ?: map["en"]?.takeIf { it.isNotBlank() }
    }
}
