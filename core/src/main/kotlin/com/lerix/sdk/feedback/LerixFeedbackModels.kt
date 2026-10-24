package com.lerix.sdk.feedback

import org.json.JSONArray
import org.json.JSONObject

/** `email` / `screenshot` modes of the feedback flow. */
enum class LerixFieldMode(val raw: String) {
    HIDDEN("hidden"),
    OPTIONAL("optional"),
    REQUIRED("required"),
    ;

    companion object {
        fun parse(raw: String?, fallback: LerixFieldMode): LerixFieldMode =
            entries.firstOrNull { it.raw == raw } ?: fallback
    }
}

/**
 * Options for `Lerix.feedback.show` (contract §7). Unset fields come from the
 * project's remote config (`GET /plugin/feedback/config`).
 */
data class LerixFeedbackOptions(
    /** Only offer these label keys (must exist in the project config). */
    val labels: List<String>? = null,
    val email: LerixFieldMode? = null,
    /** HIDDEN or OPTIONAL. */
    val screenshot: LerixFieldMode? = null,
    val prefillEmail: String? = null,
    /** Extra data attached to the feedback (≤ 50 keys, JSON-serialisable, ≤ 16 KB). */
    val custom: Map<String, Any?>? = null,
    /** Accent colour (ARGB) for this flow; defaults to the host theme's primary colour. */
    val accentColor: Int? = null,
)

/** Result of the feedback flow or a headless submit. */
data class LerixFeedbackResult(
    /** True when the feedback was accepted: sent, or saved in the outbox to send later. */
    val submitted: Boolean,
    /** Server id, once the backend has stored it (null while still queued). */
    val id: String? = null,
    /** Client idempotency id (`clientId`). */
    val clientId: String? = null,
)

internal data class LerixFeedbackLabel(
    val key: String,
    val title: Map<String, String>,
    val color: String?,
    val hidden: Boolean,
)

internal data class LerixFeedbackConfig(
    val enabled: Boolean,
    val email: LerixFieldMode,
    val screenshot: LerixFieldMode,
    val maxScreenshots: Int,
    val maxMessageLength: Int,
    val labels: List<LerixFeedbackLabel>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("email", email.raw)
        put("screenshot", screenshot.raw)
        put("maxScreenshots", maxScreenshots)
        put("maxMessageLength", maxMessageLength)
        put(
            "labels",
            JSONArray().apply {
                labels.forEach { l ->
                    put(
                        JSONObject().apply {
                            put("key", l.key)
                            put("title", JSONObject(l.title as Map<*, *>))
                            put("color", l.color ?: JSONObject.NULL)
                            put("hidden", l.hidden)
                        },
                    )
                }
            },
        )
    }

    companion object {
        /** Built-in defaults, used offline with no cache (same as the server seed). */
        val DEFAULT = LerixFeedbackConfig(
            enabled = true,
            email = LerixFieldMode.OPTIONAL,
            screenshot = LerixFieldMode.OPTIONAL,
            maxScreenshots = 3,
            maxMessageLength = 4000,
            labels = listOf(
                LerixFeedbackLabel("bug", mapOf("en" to "Bug", "ar" to "خطأ"), "#E5484D", false),
                LerixFeedbackLabel("improvement", mapOf("en" to "Improvement", "ar" to "تحسين"), "#0090FF", false),
                LerixFeedbackLabel("praise", mapOf("en" to "Praise", "ar" to "إشادة"), "#30A46C", false),
            ),
        )

        fun fromJson(json: JSONObject): LerixFeedbackConfig {
            val labels = mutableListOf<LerixFeedbackLabel>()
            val arr = json.optJSONArray("labels")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val key = o.optString("key").takeIf { it.isNotBlank() } ?: continue
                    labels += LerixFeedbackLabel(
                        key = key,
                        title = stringMap(o.optJSONObject("title")),
                        color = if (o.isNull("color")) null else o.optString("color").takeIf { it.isNotBlank() },
                        hidden = o.optBoolean("hidden", false),
                    )
                }
            }
            val screenshot = LerixFieldMode.parse(json.optString("screenshot"), LerixFieldMode.OPTIONAL)
            return LerixFeedbackConfig(
                enabled = json.optBoolean("enabled", true),
                email = LerixFieldMode.parse(json.optString("email"), LerixFieldMode.OPTIONAL),
                screenshot = if (screenshot == LerixFieldMode.REQUIRED) LerixFieldMode.OPTIONAL else screenshot,
                maxScreenshots = json.optInt("maxScreenshots", 3).coerceIn(0, 5),
                maxMessageLength = json.optInt("maxMessageLength", 4000).coerceIn(1, 100_000),
                labels = labels,
            )
        }

        fun stringMap(obj: JSONObject?): Map<String, String> {
            if (obj == null) return emptyMap()
            val out = LinkedHashMap<String, String>()
            obj.keys().forEach { k -> if (!obj.isNull(k)) out[k] = obj.optString(k) }
            return out
        }
    }
}
