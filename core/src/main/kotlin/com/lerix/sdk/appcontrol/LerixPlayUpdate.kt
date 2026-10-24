package com.lerix.sdk.appcontrol

import android.app.Activity
import android.content.Context
import android.os.Build
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.lerix.sdk.core.LerixLog

/**
 * Optional Google Play in-app updates (IMMEDIATE) for required updates.
 * `com.google.android.play:app-update` is a compileOnly dependency: every Play
 * class is touched only from [LerixPlayUpdateImpl], after a runtime class check,
 * so apps without it fall back to the store link.
 */
internal object LerixPlayUpdate {
    const val PLAY_STORE_PACKAGE = "com.android.vending"
    const val REQUEST_CODE = 0x4C58

    private val available: Boolean by lazy {
        try {
            Class.forName("com.google.android.play.core.appupdate.AppUpdateManagerFactory")
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Play app-update library present and this install came from Google Play. */
    fun canUse(context: Context): Boolean = available && installedFromPlay(context)

    fun installedFromPlay(context: Context): Boolean = try {
        installer(context) == PLAY_STORE_PACKAGE
    } catch (t: Throwable) {
        false
    }

    @Suppress("DEPRECATION")
    private fun installer(context: Context): String? {
        val pm = context.packageManager
        val pkg = context.packageName
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            pm.getInstallerPackageName(pkg)
        }
    }

    /** Starts an IMMEDIATE update; [fallback] (the store link) when Play can't do it. */
    fun startImmediate(activity: Activity, fallback: () -> Unit) {
        if (!available) return fallback()
        try {
            LerixPlayUpdateImpl.start(activity, onlyIfInProgress = false, fallback = fallback)
        } catch (t: Throwable) {
            LerixLog.debug("Play in-app update unavailable: $t")
            fallback()
        }
    }

    /** Resumes an IMMEDIATE update the user left (Play's recommended onResume check). */
    fun resumeIfInProgress(activity: Activity) {
        if (!available) return
        try {
            LerixPlayUpdateImpl.start(activity, onlyIfInProgress = true, fallback = {})
        } catch (t: Throwable) {
            LerixLog.debug("Play in-app update resume failed: $t")
        }
    }
}

private object LerixPlayUpdateImpl {
    fun start(activity: Activity, onlyIfInProgress: Boolean, fallback: () -> Unit) {
        val manager = AppUpdateManagerFactory.create(activity.applicationContext)
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                try {
                    val availability = info.updateAvailability()
                    val inProgress = availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                    val canStart = inProgress || (
                        !onlyIfInProgress &&
                            availability == UpdateAvailability.UPDATE_AVAILABLE &&
                            info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                        )
                    if (!canStart || activity.isFinishing || activity.isDestroyed) {
                        fallback()
                        return@addOnSuccessListener
                    }
                    val started = manager.startUpdateFlowForResult(
                        info,
                        activity,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                        LerixPlayUpdate.REQUEST_CODE,
                    )
                    if (!started) fallback()
                } catch (t: Throwable) {
                    LerixLog.debug("Play in-app update failed: $t")
                    fallback()
                }
            }
            .addOnFailureListener { e ->
                LerixLog.debug("Play appUpdateInfo failed: $e")
                fallback()
            }
    }
}
