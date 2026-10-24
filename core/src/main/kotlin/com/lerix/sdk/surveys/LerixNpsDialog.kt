package com.lerix.sdk.surveys

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.lerix.sdk.R
import com.lerix.sdk.ui.LerixUi

/**
 * NPS sheet: 0–10 score → follow-up question for the score's group → thanks.
 * The 0–10 scale always reads left to right, also in RTL. The dashboard's
 * appearance sets the button shape, the accent, colour by group (0–6 / 7–8 /
 * 9–10) and the end labels.
 */
internal class LerixNpsDialog(
    activity: Activity,
    run: LerixSurveyRun,
    onClosed: () -> Unit,
) : LerixSurveySheet(activity, run, onClosed) {
    override val defaultQuestion = R.string.lerix_nps_default_question
    override val defaultFollowUp = R.string.lerix_nps_default_followup
    override val defaultThanks = R.string.lerix_nps_default_thanks
    override val followUpHint = R.string.lerix_nps_followup_hint
    override val dismissLabel = R.string.lerix_nps_dismiss

    private val byGroup = appearance.colorMode == "groups"
    private var selected = -1

    override fun renderChoices(container: LinearLayout) {
        val ctx = activity
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, dp(16), 0, dp(6))
        }
        val buttons = ArrayList<TextView>(11)
        for (value in 0..10) {
            val button = (if (shape == "circle") SquareTextView(ctx) else TextView(ctx)).apply {
                text = value.toString()
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                contentDescription = ctx.getString(R.string.lerix_nps_score, value)
                LerixUi.radio(this) { selected == value }
                setOnClickListener {
                    if (hasAnswer || !run.answer(value, null)) return@setOnClickListener
                    selected = value
                    buttons.forEachIndexed { i, b -> paint(b, i) }
                    picked(npsGroup(value))
                }
            }
            paint(button, value)
            buttons += button
            row.addView(
                button,
                LinearLayout.LayoutParams(0, if (shape == "circle") ViewGroup.LayoutParams.WRAP_CONTENT else dp(40), 1f).apply {
                    marginEnd = if (value < 10) dp(3) else 0
                },
            )
        }
        container.addView(row)
        if (!appearance.showLabels) return
        val legend = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val low = appearance.lowLabel ?: ctx.getString(R.string.lerix_nps_not_likely)
        val high = appearance.highLabel ?: ctx.getString(R.string.lerix_nps_very_likely)
        legend.addView(endLabel(low, end = false, rtl = false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        legend.addView(endLabel(high, end = true, rtl = false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(legend)
    }

    /** Neutral, tinted by group, or filled when picked. */
    private fun paint(button: TextView, value: Int) {
        val ctx = activity
        val bg = LerixUi.color(ctx, R.color.lerix_background)
        val fg = LerixUi.color(ctx, R.color.lerix_text_primary)
        val radius = dp(8).toFloat()
        val groupColor = if (byGroup) groupColor(npsGroup(value)) else null
        val (fill, stroke, text) = when {
            value == selected && groupColor != null -> Triple(groupColor, groupColor, LerixUi.onColor(groupColor))
            value == selected -> Triple(accent, accent, LerixUi.onColor(accent))
            groupColor != null -> Triple(
                LerixUi.mix(bg, groupColor, 0.14f),
                LerixUi.mix(bg, groupColor, 0.45f),
                LerixUi.mix(fg, groupColor, 0.80f),
            )
            else -> Triple(LerixUi.color(ctx, R.color.lerix_surface), LerixUi.color(ctx, R.color.lerix_border), fg)
        }
        button.setTextColor(text)
        button.background = LerixUi.ripple(ctx, LerixUi.shaped(ctx, shape, fill, radius, stroke, dp(1)))
    }

    private fun groupColor(group: String): Int =
        LerixUi.parseHex(appearance.groupColors[group]) ?: GROUP_COLORS.getValue(group)

    companion object {
        /** Used by `colorMode: "groups"` when the dashboard sets no colour for a group. */
        val GROUP_COLORS = mapOf(
            "detractor" to 0xFFE5484D.toInt(),
            "passive" to 0xFFF5A524.toInt(),
            "promoter" to 0xFF30A46C.toInt(),
        )
    }
}

/** A TextView as tall as it is wide (the `circle` shape). */
internal class SquareTextView(context: Context) : TextView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
