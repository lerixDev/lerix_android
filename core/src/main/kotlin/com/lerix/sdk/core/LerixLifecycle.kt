package com.lerix.sdk.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.lerix.sdk.analytics.LerixAnalytics
import java.lang.ref.WeakReference

/**
 * App lifecycle without androidx.lifecycle-process (not a dependency): counts
 * started activities. Foreground = first activity started; background = last
 * one stopped (ignoring configuration changes). Also tracks the resumed
 * activity for feedback (shake, screenshot capture) and emits automatic
 * `$screen_view`s.
 */
internal object LerixLifecycle : Application.ActivityLifecycleCallbacks {
    interface Listener {
        fun onForeground() {}
        fun onBackground() {}
        fun onActivityResumed(activity: Activity) {}
        fun onActivityPaused(activity: Activity) {}
    }

    private var registered = false
    private var started = 0
    private var hadForeground = false
    @Volatile var inForeground = false
        private set
    private var resumed: WeakReference<Activity>? = null
    private val listeners = mutableListOf<Listener>()

    /** Activities the SDK itself shows; never reported as screens. */
    private val internalActivities = mutableSetOf<String>()

    fun register(application: Application) {
        if (registered) return
        registered = true
        application.registerActivityLifecycleCallbacks(this)
    }

    fun addListener(listener: Listener) {
        if (listener !in listeners) listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun markInternal(activityClass: Class<out Activity>) {
        internalActivities += activityClass.name
    }

    fun isInternal(activity: Activity) = activity.javaClass.name in internalActivities

    /** The resumed (or, failing that, last resumed) host activity. */
    fun currentActivity(): Activity? = resumed?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (LerixAnalytics.trackFragments && !isInternal(activity)) LerixFragmentScreens.attach(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        started += 1
        if (started == 1 && !inForeground) {
            inForeground = true
            val cold = !hadForeground
            hadForeground = true
            LerixAnalytics.onForeground(coldStart = cold)
            listeners.toList().forEach { it.onForeground() }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (!isInternal(activity)) {
            resumed = WeakReference(activity)
            if (LerixAnalytics.autoScreens) {
                LerixAnalytics.screen(screenName(activity), null, automatic = true)
            }
        }
        listeners.toList().forEach { it.onActivityResumed(activity) }
    }

    override fun onActivityPaused(activity: Activity) {
        listeners.toList().forEach { it.onActivityPaused(activity) }
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && inForeground && !activity.isChangingConfigurations) {
            inForeground = false
            LerixAnalytics.onBackground()
            listeners.toList().forEach { it.onBackground() }
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    private fun screenName(activity: Activity): String =
        activity.javaClass.simpleName.ifEmpty { activity.title?.toString() ?: "Activity" }
}

/** Optional `$screen_view` for AndroidX fragments; compiled against the transitive androidx.fragment. */
internal object LerixFragmentScreens {
    private val available: Boolean by lazy {
        try {
            Class.forName("androidx.fragment.app.FragmentActivity")
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun attach(activity: Activity) {
        if (!available) return
        try {
            val fa = activity as? androidx.fragment.app.FragmentActivity ?: return
            fa.supportFragmentManager.registerFragmentLifecycleCallbacks(
                object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentResumed(
                        fm: androidx.fragment.app.FragmentManager,
                        f: androidx.fragment.app.Fragment,
                    ) {
                        val name = f.javaClass.name
                        // Skip framework containers (NavHostFragment, dialogs, Glide's manager…).
                        if (name.startsWith("androidx.") || name.startsWith("com.google.android.") ||
                            name.startsWith("com.bumptech.") || f.view == null
                        ) {
                            return
                        }
                        LerixAnalytics.screen(f.javaClass.simpleName, null, automatic = true)
                    }
                },
                true,
            )
        } catch (t: Throwable) {
            LerixLog.debug("fragment tracking unavailable: $t")
        }
    }
}
