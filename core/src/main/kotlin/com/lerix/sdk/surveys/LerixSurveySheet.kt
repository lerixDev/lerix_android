package com.lerix.sdk.surveys

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lerix.sdk.R
import com.lerix.sdk.ui.LerixUi

/**
 * The bottom sheet shared by NPS and rating, as a plain styled Dialog
 * (Material is not a dependency): answer step → follow-up question for the
 * answer's group → thanks (closes itself). Picking an answer counts even if
 * the sheet is then closed; closing before that (×, Back, a tap outside) is a
 * dismiss. The survey's accent beats `Lerix.setAccentColor` and the theme's
 * `colorPrimary`.
 */
internal abstract class LerixSurveySheet(
    protected val activity: Activity,
    protected val run: LerixSurveyRun,
    private val onClosed: () -> Unit,
) {
    private val dialog = Dialog(activity, R.style.Lerix_Theme_Sheet)
    protected val appearance: LerixSurveyAppearance = run.survey.appearance.localize(run.lang)

    /** The survey's own accent, if the dashboard set one. */
    protected val surveyAccent: Int? = LerixUi.parseHex(appearance.accent)
    protected val accent: Int = LerixUi.resolveAccent(activity, surveyAccent)
    protected val shape: String = appearance.shape?.takeIf { it in SHAPES } ?: "rounded"
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var answeredGroup: String? = null

    protected abstract val defaultQuestion: Int
    protected abstract val defaultFollowUp: Int
    protected abstract val defaultThanks: Int
    protected abstract val followUpHint: Int
    protected abstract val dismissLabel: Int

    /** Adds the answer step (under the question) to [container]. */
    protected abstract fun renderChoices(container: LinearLayout)

    /** Whether an answer has been picked (the choices ignore taps after it). */
    protected val hasAnswer: Boolean get() = answeredGroup != null

    /** Called by the choices once [run] took the answer: shows the follow-up for [group]. */
    protected fun picked(group: String) {
        if (answeredGroup != null) return
        answeredGroup = group
        // A moment to see the pick (the filled stars, the ring) before the follow-up.
        body.postDelayed({ if (dialog.isShowing) renderFollowUp(group) }, PICK_DELAY_MS)
    }

    fun show() {
        val ctx = activity
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val r = dp(20).toFloat()
            background = GradientDrawable().apply {
                setColor(LerixUi.color(ctx, R.color.lerix_background))
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPaddingRelative(dp(20), dp(8), dp(12), dp(20))
        }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        val close = TextView(ctx).apply {
            text = "✕"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(LerixUi.color(ctx, R.color.lerix_text_secondary))
            contentDescription = ctx.getString(dismissLabel)
            setOnClickListener { dialog.dismiss() }
        }
        top.addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))
        sheet.addView(top)
        body.setPaddingRelative(0, 0, dp(8), 0)
        sheet.addView(body)
        renderAnswerStep()

        dialog.setContentView(sheet)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            if (answeredGroup == null) run.dismiss()
            onClosed()
        }
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            // Lay the sheet under the navigation bar area, padded by its inset.
            decorView.setPadding(0, 0, 0, 0)
        }
        sheet.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPaddingRelative(dp(20), dp(8), dp(12), dp(20) + insets.systemWindowInsetBottom)
            insets
        }
        dialog.show()
    }

    private fun renderAnswerStep() {
        val ctx = activity
        body.removeAllViews()
        body.addView(
            LerixUi.title(ctx, LerixSurveyText.pick(run.survey.question, run.lang) ?: ctx.getString(defaultQuestion)).apply {
                textSize = 18f
            },
        )
        renderChoices(body)
    }

    private fun renderFollowUp(group: String) {
        val ctx = activity
        body.removeAllViews()
        body.addView(
            LerixUi.title(ctx, LerixSurveyText.pick(run.survey.followUp[group], run.lang) ?: ctx.getString(defaultFollowUp)).apply {
                textSize = 18f
            },
        )
        val input = EditText(ctx).apply {
            hint = ctx.getString(followUpHint)
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(LerixUi.color(ctx, R.color.lerix_text_primary))
            setHintTextColor(LerixUi.color(ctx, R.color.lerix_text_secondary))
            background = LerixUi.rounded(
                LerixUi.color(ctx, R.color.lerix_surface),
                dp(12).toFloat(),
                LerixUi.color(ctx, R.color.lerix_border),
                dp(1),
            )
            setPaddingRelative(dp(14), dp(12), dp(14), dp(12))
        }
        body.addView(
            input,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            },
        )
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val skip = LerixUi.button(ctx, ctx.getString(R.string.lerix_skip), primary = false, accent = accent)
        val send = LerixUi.button(ctx, ctx.getString(R.string.lerix_send), primary = true, accent = accent)
        skip.setOnClickListener { renderThanks(group) }
        send.setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) run.message(text)
            renderThanks(group)
        }
        buttons.addView(skip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(View(ctx), LinearLayout.LayoutParams(dp(12), 1))
        buttons.addView(send, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(buttons)
    }

    private fun renderThanks(group: String) {
        val ctx = activity
        dialog.currentFocus?.let { focus ->
            val imm = ctx.getSystemService(Activity.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(focus.windowToken, 0)
        }
        body.removeAllViews()
        val frame = FrameLayout(ctx).apply { setPadding(0, dp(8), 0, dp(16)) }
        frame.addView(
            LerixUi.title(ctx, LerixSurveyText.pick(run.survey.thanks[group], run.lang) ?: ctx.getString(defaultThanks)).apply {
                textSize = 18f
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        body.addView(frame)
        body.postDelayed({ if (dialog.isShowing) runCatching { dialog.dismiss() } }, THANKS_MS)
    }

    /** Width the answer row can use: the sheet minus its paddings. */
    protected fun rowWidth(): Int = activity.resources.displayMetrics.widthPixels - dp(40)

    /** Whether the sheet lays out right to left (Arabic in an app with `supportsRtl`). */
    protected fun isRtl(): Boolean =
        activity.window?.decorView?.layoutDirection == View.LAYOUT_DIRECTION_RTL

    /**
     * An end label under a scale ([end] false: the low end) for a row that runs
     * right to left when [rtl]. Absolute LEFT/RIGHT: START inside a TextView
     * follows the text's own direction, so Arabic text in an LTR row (the NPS
     * scale) would otherwise sit on the wrong side.
     */
    protected fun endLabel(text: String, end: Boolean, rtl: Boolean): TextView =
        LerixUi.body(activity, text, secondary = true).apply {
            textSize = 12f
            textAlignment = View.TEXT_ALIGNMENT_GRAVITY
            gravity = if (end != rtl) Gravity.RIGHT else Gravity.LEFT
        }

    protected fun dp(value: Int) = LerixUi.dp(activity, value)

    companion object {
        val SHAPES = setOf("rounded", "circle", "square", "pill")
        private const val PICK_DELAY_MS = 250L
        private const val THANKS_MS = 2_000L
    }
}
