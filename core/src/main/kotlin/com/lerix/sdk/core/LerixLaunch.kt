package com.lerix.sdk.core

/**
 * Per-process launch bookkeeping, shared by analytics and surveys: the app
 * start counter, `firstStartAt`, the install marker (`$first_open`) and the
 * stored app version (`$app_update`). Pending events are persisted until the
 * first foreground emits them, so a process started in the background (a push)
 * doesn't lose them.
 */
internal object LerixLaunch {
    const val APP_STARTS = "app_starts"
    const val FIRST_START_AT = "first_start_at"
    const val INSTALLED_AT = "installed_at"
    const val APP_VERSION = "app_version"
    const val PENDING_FIRST_OPEN = "pending_first_open"
    const val PENDING_UPDATE_FROM = "pending_update_from"
    const val PENDING_UPDATE_TO = "pending_update_to"

    /**
     * [existingInstall] is true when an older SDK version already ran on this
     * install (no marker yet, but SDK data exists): no `$first_open` then.
     */
    fun record(store: LerixStore, now: Long, currentVersion: String, existingInstall: Boolean) {
        store.putLong(APP_STARTS, (store.getLong(APP_STARTS) ?: 0) + 1)
        if (store.getLong(FIRST_START_AT) == null) store.putLong(FIRST_START_AT, now)
        if (store.getLong(INSTALLED_AT) == null) {
            store.putLong(INSTALLED_AT, now)
            if (!existingInstall) store.putBoolean(PENDING_FIRST_OPEN, true)
        }
        val previous = store.getString(APP_VERSION)
        if (previous != null && previous != currentVersion) {
            // Keep the oldest unsent "from" if several updates happen before a foreground.
            if (store.getString(PENDING_UPDATE_FROM) == null) store.putString(PENDING_UPDATE_FROM, previous)
            store.putString(PENDING_UPDATE_TO, currentVersion)
        }
        store.putString(APP_VERSION, currentVersion)
    }

    fun appStarts(store: LerixStore): Long = store.getLong(APP_STARTS) ?: 0
    fun firstStartAt(store: LerixStore, now: Long): Long = store.getLong(FIRST_START_AT) ?: now
}
