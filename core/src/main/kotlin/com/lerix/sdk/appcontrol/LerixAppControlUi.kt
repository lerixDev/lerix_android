package com.lerix.sdk.appcontrol

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import com.lerix.sdk.analytics.LerixAnalytics
import com.lerix.sdk.core.LerixLifecycle
import com.lerix.sdk.core.LerixLog
import com.lerix.sdk.ui.LerixUi
import java.lang.ref.WeakReference

/**
 * Built-in Release Control UI (contract §2.3), main thread only: launches the
 * full-screen [LerixAppControlActivity] over the current task for maintenance
 * and required updates (again whenever another activity of the app resumes
 * while the state holds), and shows [LerixUpdatePrompt] once per app session
 * for available/recommended updates.
 */
internal object LerixAppControlUi {
    private const val SOFT_PROMPT_DELAY_MS = 600L
    private const val RELAUNCH_GUARD_MS = 1_000L

    private var resumed: WeakReference<Activity>? = null
    private var screen: WeakReference<LerixAppControlActivity>? = null
    // Weak: the showing dialog is kept alive by the window manager; this must not pin its Activity.
    private var promptRef: WeakReference<LerixUpdatePrompt>? = null
    private var prompt: LerixUpdatePrompt?
        get() = promptRef?.get()
        set(value) {
            promptRef = value?.let { WeakReference(it) }
        }
    private var promptSession: String? = null
    private var lastLaunchAt = 0L
    private var lastLaunchFrom: WeakReference<Activity>? = null

    private val uiEnabled: Boolean
        get() = LerixAppControl.options.ui && LerixAppControl.options.enabled

    /** The activity to start the store / Play flow from: our screen, else the resumed app activity. */
    fun foregroundActivity(): Activity? =
        screen?.get()?.takeIf { it.isAlive() }
            ?: resumed?.get()?.takeIf { it.isAlive() }
            ?: LerixLifecycle.currentActivity()

    fun onActivityResumed(activity: Activity, state: LerixAppControlState) {
        if (activity is LerixAppControlActivity) return
        resumed = WeakReference(activity)
        apply(activity, state)
    }

    fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
        val p = prompt ?: return
        if (p.owner === activity && (activity.isFinishing || activity.isChangingConfigurations)) {
            // Rotation recreates the activity: show the prompt again on the new instance.
            if (activity.isChangingConfigurations) promptSession = null
            prompt = null
            p.dismissQuietly()
        }
    }

    fun onState(state: LerixAppControlState) {
        screen?.get()?.takeIf { it.isAlive() }?.render(state)
        resumed?.get()?.takeIf { it.isAlive() }?.let { apply(it, state) }
    }

    fun attachScreen(activity: LerixAppControlActivity) {
        screen = WeakReference(activity)
    }

    fun detachScreen(activity: LerixAppControlActivity) {
        if (screen?.get() === activity) screen = null
    }

    fun onPromptClosed(p: LerixUpdatePrompt) {
        if (prompt === p) prompt = null
    }

    private fun apply(activity: Activity, state: LerixAppControlState) {
        if (!uiEnabled || !activity.isAlive()) return
        when (LerixAppControlLogic.screenFor(state)) {
            LerixAppControlScreen.MAINTENANCE, LerixAppControlScreen.REQUIRED_UPDATE -> {
                dismissPrompt()
                launchScreen(activity)
            }
            LerixAppControlScreen.SOFT_UPDATE -> maybeShowPrompt(activity)
            LerixAppControlScreen.NONE -> dismissPrompt()
        }
    }

    private fun launchScreen(from: Activity) {
        val now = SystemClock.elapsedRealtime()
        val since = now - lastLaunchAt
        if (LerixAppControlLogic.isDuplicateLaunch(lastLaunchFrom?.get() === from, since, RELAUNCH_GUARD_MS)) {
            recheckAfter(from, RELAUNCH_GUARD_MS - since)
            return
        }
        lastLaunchAt = now
        lastLaunchFrom = WeakReference(from)
        try {
            val intent = Intent(from, LerixAppControlActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(LerixAppControlActivity.EXTRA_ACCENT, LerixUi.resolveAccent(from))
            from.startActivity(intent)
        } catch (t: Throwable) {
            LerixLog.debug("could not show the release control screen: $t")
        }
    }

    /**
     * After a dropped duplicate: if [activity] is still the resumed one once the guard has passed, the screen
     * did not come up over it (or it came back on top), so cover it again.
     */
    private fun recheckAfter(activity: Activity, delayMs: Long) {
        val decor = activity.window?.decorView ?: return
        decor.postDelayed({
            try {
                if (resumed?.get() === activity && activity.isAlive()) apply(activity, LerixAppControl.currentState)
            } catch (t: Throwable) {
                LerixLog.debug("release control re-check failed: $t")
            }
        }, delayMs.coerceAtLeast(0) + 50)
    }

    private fun maybeShowPrompt(activity: Activity) {
        // Never over the SDK's own screens (feedback), and once per app session.
        if (LerixLifecycle.isInternal(activity)) return
        if (prompt != null || promptSession == sessionKey()) return
        val decor = activity.window?.decorView ?: return
        // Not before the app's first frame: post after layout, plus a short delay.
        decor.postDelayed({
            try {
                if (resumed?.get() !== activity || !activity.isAlive() || !uiEnabled) return@postDelayed
                val state = LerixAppControl.currentState
                if (LerixAppControlLogic.screenFor(state) != LerixAppControlScreen.SOFT_UPDATE) return@postDelayed
                val update = state.update ?: return@postDelayed
                val session = sessionKey()
                if (prompt != null || promptSession == session) return@postDelayed
                promptSession = session
                val p = LerixUpdatePrompt(activity, update)
                prompt = p
                p.show()
                LerixAppControl.trackShown(update)
            } catch (t: Throwable) {
                LerixLog.debug("update prompt failed: $t")
            }
        }, SOFT_PROMPT_DELAY_MS)
    }

    private fun dismissPrompt() {
        val p = prompt ?: return
        prompt = null
        p.dismissQuietly()
    }

    private fun sessionKey(): String = runCatching { LerixAnalytics.currentSessionId() }.getOrNull() ?: "process"

    private fun Activity.isAlive() = !isFinishing && !isDestroyed
}
