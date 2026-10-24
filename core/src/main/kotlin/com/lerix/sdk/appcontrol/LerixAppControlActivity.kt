package com.lerix.sdk.appcontrol

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.lerix.sdk.R
import com.lerix.sdk.ui.LerixUi

/**
 * Full-screen, non-dismissible maintenance / required-update screen (contract
 * §2.3). Internal: started by the SDK over the app's task. Back sends the task
 * to the background, never into the app; it finishes itself when the state
 * clears.
 */
class LerixAppControlActivity : Activity() {
    private lateinit var column: LinearLayout
    private var accent: Int = 0
    private var renderedKey: String? = null
    private var trackedUpdateKey: String? = null
    private var playStarted = false
    private var backCallback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accent = intent?.getIntExtra(EXTRA_ACCENT, 0)?.takeIf { it != 0 } ?: LerixUi.resolveAccent(this)
        setupWindow()
        setContentView(buildShell())
        registerBack()
        LerixAppControlUi.attachScreen(this)
        render(LerixAppControl.currentState)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        render(LerixAppControl.currentState)
    }

    override fun onResume() {
        super.onResume()
        LerixAppControlUi.attachScreen(this)
        render(LerixAppControl.currentState)
        if (playStarted && !isFinishing) LerixPlayUpdate.resumeIfInProgress(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) {
            (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        LerixAppControlUi.detachScreen(this)
        super.onDestroy()
    }

    /** Shows [state], or finishes when it no longer calls for this screen. */
    internal fun render(state: LerixAppControlState) {
        if (isFinishing || isDestroyed) return
        val screen = LerixAppControlLogic.screenFor(state)
        val enabled = LerixAppControl.options.ui && LerixAppControl.options.enabled
        if (!enabled || (screen != LerixAppControlScreen.MAINTENANCE && screen != LerixAppControlScreen.REQUIRED_UPDATE)) {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, android.R.anim.fade_out)
            return
        }
        val maintenance = state.maintenance
        val update = state.update
        if (screen == LerixAppControlScreen.MAINTENANCE && maintenance != null) {
            val key = "m|$maintenance"
            if (key != renderedKey) {
                renderedKey = key
                renderMaintenance(maintenance)
            }
        } else if (update != null) {
            val key = "u|$update|${LerixAppControl.options.usePlayInAppUpdate}"
            if (key != renderedKey) {
                renderedKey = key
                renderUpdate(update)
            }
            val trackKey = "${update.ruleId}|${update.state}|${update.latestVersion}"
            if (trackKey != trackedUpdateKey) {
                trackedUpdateKey = trackKey
                LerixAppControl.trackShown(update)
            }
        }
    }

    // ---- content ----

    private fun renderMaintenance(m: LerixMaintenance) {
        column.removeAllViews()
        addTitle(m.title ?: getString(R.string.lerix_appctl_maintenance_title))
        addMessage(m.message ?: getString(R.string.lerix_appctl_maintenance_message))
        if (m.showEndsAt) {
            addSecondary(getString(R.string.lerix_appctl_expected_back, formatTime(m.endsAt)))
        }
        val retry = LerixUi.button(this, getString(R.string.lerix_appctl_try_again), primary = true, accent = accent)
        retry.setOnClickListener {
            LerixUi.setEnabled(retry, false)
            LerixAppControl.refresh { state ->
                if (!isFinishing && !isDestroyed) {
                    LerixUi.setEnabled(retry, true)
                    render(state)
                }
            }
        }
        addButton(retry)
    }

    private fun renderUpdate(u: LerixAppUpdate) {
        column.removeAllViews()
        addTitle(u.title ?: getString(R.string.lerix_appctl_required_title))
        addMessage(u.message ?: getString(R.string.lerix_appctl_required_message))
        u.releaseNotes?.let { notes ->
            val box = TextView(this).apply {
                text = notes
                textSize = 14f
                setTextColor(LerixUi.color(this@LerixAppControlActivity, R.color.lerix_text_primary))
                // Paragraph direction decides the side, so Arabic notes align right even without supportsRtl.
                textAlignment = View.TEXT_ALIGNMENT_TEXT_START
                background = LerixUi.rounded(
                    LerixUi.color(this@LerixAppControlActivity, R.color.lerix_surface),
                    dp(12).toFloat(),
                    LerixUi.color(this@LerixAppControlActivity, R.color.lerix_border),
                    dp(1),
                )
                setPaddingRelative(dp(16), dp(14), dp(16), dp(14))
            }
            column.addView(
                box,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(20)
                },
            )
        }
        val canPlay = LerixAppControl.options.usePlayInAppUpdate && LerixPlayUpdate.canUse(this)
        // No store link on mobile → message only (contract §2.3).
        if (u.storeUrl != null || canPlay) {
            val button = LerixUi.button(this, getString(R.string.lerix_appctl_update), primary = true, accent = accent)
            button.setOnClickListener {
                if (canPlay) playStarted = true
                LerixAppControl.openStore(this)
            }
            addButton(button)
        }
    }

    private fun addTitle(text: String) {
        column.addView(
            LerixUi.title(this, text).apply {
                textSize = 24f
                gravity = Gravity.CENTER_HORIZONTAL
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            },
            matchWidth(),
        )
    }

    private fun addMessage(text: String) {
        column.addView(
            LerixUi.body(this, text).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setLineSpacing(0f, 1.15f)
            },
            matchWidth().apply { topMargin = dp(12) },
        )
    }

    private fun addSecondary(text: String) {
        column.addView(
            LerixUi.body(this, text, secondary = true).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            },
            matchWidth().apply { topMargin = dp(16) },
        )
    }

    private fun addButton(button: TextView) {
        column.addView(button, matchWidth().apply { topMargin = dp(32) })
    }

    private fun formatTime(epochMs: Long): String {
        var flags = DateUtils.FORMAT_SHOW_TIME
        if (!DateUtils.isToday(epochMs)) flags = flags or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        return DateUtils.formatDateTime(this, epochMs, flags)
    }

    // ---- shell ----

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                (window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
        }
    }

    private fun buildShell(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(LerixUi.color(this@LerixAppControlActivity, R.color.lerix_background))
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        LerixUi.applySystemInsets(root)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPaddingRelative(dp(28), dp(32), dp(28), dp(32))
        }
        val frame = FrameLayout(this)
        frame.addView(
            column,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        scroll.addView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun registerBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = OnBackInvokedCallback { moveTaskToBack(true) }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int) = LerixUi.dp(this, value)

    internal companion object {
        const val EXTRA_ACCENT = "lerix_accent"
    }
}
