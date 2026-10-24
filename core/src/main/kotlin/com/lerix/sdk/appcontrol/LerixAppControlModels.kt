package com.lerix.sdk.appcontrol

/**
 * Release Control options for [com.lerix.sdk.Lerix.initialize] (contract §2.6).
 *
 * - [enabled]: `false` → no `app-config` fetch, no UI, no events. A cached
 *   vendor kill switch (`sdk.disabled`) still applies until it expires.
 * - [ui]: the built-in maintenance screen, required-update screen and
 *   update dialog. `false` → draw your own from [LerixAppControl.state].
 * - [usePlayInAppUpdate]: for a required update on an app installed by Google
 *   Play, "Update" starts a Play IMMEDIATE in-app update instead of opening the
 *   store link. Needs `com.google.android.play:app-update` on your classpath;
 *   without it (or when not installed by Play) the store link is used.
 */
data class LerixAppControlOptions(
    val enabled: Boolean = true,
    val ui: Boolean = true,
    val usePlayInAppUpdate: Boolean = false,
)

enum class LerixUpdateState(val raw: String) {
    AVAILABLE("available"),
    RECOMMENDED("recommended"),
    REQUIRED("required"),
    ;

    companion object {
        fun from(raw: String?): LerixUpdateState? = values().firstOrNull { it.raw == raw }
    }
}

/**
 * The `update` block of `GET /plugin/app-config` (contract §1.2). Times are
 * epoch milliseconds (UTC instants). [title]/[message] are the server copy;
 * null means the SDK's built-in text is used.
 */
data class LerixAppUpdate(
    val state: LerixUpdateState,
    /** `outdated`, `below_minimum`, `blocked_version` or `deadline_passed`; null when unknown. */
    val reason: String?,
    val ruleId: String?,
    val latestVersion: String?,
    val minVersion: String?,
    val storeUrl: String?,
    val title: String?,
    val message: String?,
    val releaseNotes: String?,
    val remindAfterHours: Int,
    val allowSkip: Boolean,
    val requiredAt: Long?,
) {
    val isRequired: Boolean get() = state == LerixUpdateState.REQUIRED
}

/** The `maintenance` block (contract §1.2); only present in the state while it is active. */
data class LerixMaintenance(
    val id: String,
    val title: String?,
    val message: String?,
    val imageUrl: String?,
    val startsAt: Long,
    val endsAt: Long,
    val showEndsAt: Boolean,
)

/** The vendor kill switch (contract §2.5): [disabled] modules stay off until [until]. */
data class LerixSdkControl(
    val disabled: Set<String>,
    val until: Long,
)

/**
 * What Release Control currently says, already filtered by time, "Later" and
 * "Skip this version" (contract §2.3). Precedence for UI: maintenance >
 * required update > recommended/available update.
 */
data class LerixAppControlState(
    val update: LerixAppUpdate? = null,
    val maintenance: LerixMaintenance? = null,
    val sdk: LerixSdkControl? = null,
) {
    /** A maintenance window or a required update: the app must not be usable. */
    val isBlocking: Boolean get() = maintenance != null || update?.state == LerixUpdateState.REQUIRED

    companion object {
        val EMPTY = LerixAppControlState()
    }
}

/** Called on the main thread whenever [LerixAppControl.state] changes. */
fun interface LerixAppControlListener {
    fun onChange(state: LerixAppControlState)
}
