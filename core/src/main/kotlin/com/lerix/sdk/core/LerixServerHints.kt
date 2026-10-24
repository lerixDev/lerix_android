package com.lerix.sdk.core

import org.json.JSONObject

/** Features that the server can switch off independently with `403 SDK_DISABLED`. */
internal enum class LerixFeature(val key: String) {
    FEEDBACK("feedback"),
    SURVEYS("surveys"),
    ANALYTICS("analytics"),
}

/**
 * The server-hint channel (contract §0): `warnings[]` on success bodies are
 * logged in debug mode, and `403 {code: SDK_DISABLED, retryAfterSeconds}`
 * pauses that feature's traffic until the time passes (persisted).
 */
internal class LerixServerHints(
    private val store: LerixStore,
    /** Release Control's vendor kill switch (`sdk.disabled`, contract §2.5): pauses the feature without dropping its queue. */
    private val killSwitch: (LerixFeature) -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun isDisabled(feature: LerixFeature): Boolean {
        if (runCatching { killSwitch(feature) }.getOrDefault(false)) return true
        val until = store.getLong(key(feature)) ?: return false
        if (now() >= until) {
            store.remove(key(feature))
            return false
        }
        return true
    }

    fun disabledUntil(feature: LerixFeature): Long? = store.getLong(key(feature))

    /** Inspects a response; returns true when it was an SDK_DISABLED response. */
    fun handle(feature: LerixFeature, status: Int?, json: JSONObject?): Boolean {
        val warnings = json?.optJSONArray("warnings")
        if (warnings != null) {
            for (i in 0 until warnings.length()) {
                val w = warnings.optJSONObject(i) ?: continue
                LerixLog.debug("server warning ${w.optString("code")}: ${w.optString("message")}")
            }
        }
        val code = json?.optString("code")?.ifEmpty { null } ?: json?.opt("error") as? String
        if (status == 403 && code == "SDK_DISABLED") {
            val seconds = json!!.optLong("retryAfterSeconds", 3600L).coerceIn(60L, 7 * 86_400L)
            store.putLong(key(feature), now() + seconds * 1000)
            LerixLog.debug("${feature.key} disabled by the server for ${seconds}s")
            return true
        }
        return false
    }

    private fun key(feature: LerixFeature) = "disabled_until_${feature.key}"
}
