package com.lerix.sdk.surveys

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lerix.sdk.R
import com.lerix.sdk.ui.LerixUi
import kotlin.math.min

/**
 * Rating sheet: the choices in the dashboard's style (stars, hearts, emoji,
 * thumbs or numbers) → follow-up question for the choice's group → thanks.
 * Unlike the NPS scale, the row follows the layout direction (mirrored in RTL).
 */
internal class LerixRatingDialog(
    activity: Activity,
    run: LerixSurveyRun,
    onClosed: () -> Unit,
) : LerixSurveySheet(activity, run, onClosed) {
    override val defaultQuestion = R.string.lerix_rating_default_question
    override val defaultFollowUp = R.string.lerix_rating_default_followup
    override val defaultThanks = R.string.lerix_rating_default_thanks
    override val followUpHint = R.string.lerix_survey_answer_hint
    override val dismissLabel = R.string.lerix_rating_dismiss

    private val choices = run.choices
    private val style = resolveStyle(appearance.style, choices.size)
    private val byOption = appearance.colorMode == "options"
    private val icons = style == "stars" || style == "hearts"
    private val cells = ArrayList<View>(choices.size)
    private var selected = -1

    /** The colour for a choice: its own (colour by option), else the survey accent, else the style's default. */
    private fun colorOf(choice: LerixSurveyChoice?): Int? =
        (if (byOption) LerixUi.parseHex(choice?.color) else null)
            ?: surveyAccent
            ?: ICON_FILL[style]

    override fun renderChoices(container: LinearLayout) {
        val ctx = activity
        // A horizontal LinearLayout lays out from the start edge, so it mirrors in RTL by itself.
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            setPadding(0, dp(16), 0, dp(6))
            // The picked emoji grows a little; let it draw over the padding.
            clipChildren = false
            clipToPadding = false
        }
        val n = choices.size
        val slot = rowWidth() / n
        choices.forEachIndexed { index, choice ->
            val view = when (style) {
                "stars", "hearts" -> iconChoice(min(dp(40), slot))
                "emoji" -> emojiChoice(choice, min(dp(48), slot - dp(8)))
                "thumbs" -> thumbChoice(choice)
                else -> numberChoice(choice)
            }
            view.contentDescription = if (icons) {
                listOfNotNull(ctx.getString(R.string.lerix_rating_icon, index + 1, n), choice.label).joinToString(", ")
            } else {
                choice.label ?: ctx.getString(R.string.lerix_rating_choice, choice.value)
            }
            view.isClickable = true
            view.isFocusable = true
            LerixUi.radio(view) { selected == index }
            view.setOnClickListener { pick(index) }
            cells += view

            val labelled = appearance.showLabels && (style == "emoji" || style == "thumbs") && choice.label != null
            when {
                labelled -> {
                    val width = min(dp(76), slot)
                    val cell = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        clipChildren = false
                    }
                    cell.addView(view, view.layoutParams ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    cell.addView(
                        LerixUi.body(ctx, choice.label!!, secondary = true).apply {
                            textSize = 12f
                            gravity = Gravity.CENTER
                            textAlignment = View.TEXT_ALIGNMENT_CENTER
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            setPadding(dp(2), dp(4), dp(2), 0)
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    )
                    row.addView(cell, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                style == "numbers" -> {
                    val size = if (shape == "circle") min(dp(40), slot - dp(4)) else 0
                    row.addView(
                        view,
                        LinearLayout.LayoutParams(size, if (shape == "circle") size else dp(40), if (shape == "circle") 0f else 1f).apply {
                            if (index < n - 1) marginEnd = dp(4)
                        },
                    )
                }
                else -> {
                    val lp = (view.layoutParams as? LinearLayout.LayoutParams)
                        ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    val gap = if (icons) dp(2) else dp(8)
                    if (index < n - 1) lp.marginEnd = min(gap, (slot - lp.width).coerceAtLeast(0))
                    row.addView(view, lp)
                }
            }
        }
        if (style == "numbers" && shape != "circle") {
            row.gravity = Gravity.CENTER_VERTICAL
        }
        paintAll()
        container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Stars, hearts and numbers get a low / high line, from the appearance or the end choices.
        if (!appearance.showLabels || !(icons || style == "numbers")) return
        val low = appearance.lowLabel ?: choices.first().label
        val high = appearance.highLabel ?: choices.last().label
        if (low == null && high == null) return
        val legend = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val rtl = isRtl()
        legend.addView(endLabel(low ?: "", end = false, rtl = rtl), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        legend.addView(endLabel(high ?: "", end = true, rtl = rtl), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (icons) {
            // Under the icons only, not the whole sheet width.
            val width = cells.sumOf { (it.layoutParams as LinearLayout.LayoutParams).let { lp -> lp.width + lp.marginEnd } }
            container.addView(legend, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
        } else {
            container.addView(legend)
        }
    }

    private fun pick(index: Int) {
        if (hasAnswer) return
        val choice = choices[index]
        if (!run.answer(choice.id, null)) return
        selected = index
        paintAll()
        picked(choice.group)
    }

    private fun iconChoice(size: Int): View = ImageView(activity).apply {
        val pad = size / 8 // a 30dp icon in a 40dp target
        setPadding(pad, pad, pad, pad)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

    private fun emojiChoice(choice: LerixSurveyChoice, size: Int): View = TextView(activity).apply {
        text = choice.emoji ?: choice.value.toString()
        textSize = 28f * size / dp(48).coerceAtLeast(1)
        gravity = Gravity.CENTER
        includeFontPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

    private fun thumbChoice(choice: LerixSurveyChoice): View = FrameLayout(activity).apply {
        val icon = ImageView(activity).apply {
            setImageResource(if (choice.group == "negative") R.drawable.lerix_ic_thumb_down else R.drawable.lerix_ic_thumb_up)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(icon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(44))
    }

    private fun numberChoice(choice: LerixSurveyChoice): View = TextView(activity).apply {
        text = choice.value.toString()
        textSize = 14f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
    }

    private fun paintAll() {
        val ctx = activity
        val bg = LerixUi.color(ctx, R.color.lerix_background)
        val fg = LerixUi.color(ctx, R.color.lerix_text_primary)
        val surface = LerixUi.color(ctx, R.color.lerix_surface)
        val border = LerixUi.color(ctx, R.color.lerix_border)
        val muted = LerixUi.color(ctx, R.color.lerix_text_secondary)
        // Stars / hearts fill up to the picked one, in that one's colour.
        val fill = colorOf(choices.getOrNull(selected)) ?: ICON_FILL.getValue(style.takeIf { icons } ?: "stars")
        cells.forEachIndexed { i, view ->
            val choice = choices[i]
            val on = i == selected
            when (style) {
                "stars", "hearts" -> (view as ImageView).apply {
                    val lit = selected >= 0 && i <= selected
                    setImageResource(
                        when {
                            style == "stars" && lit -> R.drawable.lerix_ic_star
                            style == "stars" -> R.drawable.lerix_ic_star_outline
                            lit -> R.drawable.lerix_ic_heart
                            else -> R.drawable.lerix_ic_heart_outline
                        },
                    )
                    imageTintList = ColorStateList.valueOf(if (lit) fill else muted)
                }
                "emoji" -> {
                    val ring = colorOf(choice) ?: accent
                    view.alpha = if (on) 1f else 0.6f
                    view.scaleX = if (on) 1.12f else 1f
                    view.scaleY = view.scaleX
                    view.background = LerixUi.ripple(
                        ctx,
                        LerixUi.shaped(ctx, shape, surface, dp(10).toFloat(), if (on) ring else null, dp(2)),
                    )
                }
                "thumbs" -> {
                    val c = colorOf(choice) ?: accent
                    view.background = LerixUi.ripple(
                        ctx,
                        if (on) LerixUi.shaped(ctx, shape, c, dp(10).toFloat(), c, dp(1))
                        else LerixUi.shaped(ctx, shape, bg, dp(10).toFloat(), border, dp(1)),
                    )
                    ((view as FrameLayout).getChildAt(0) as ImageView).imageTintList =
                        ColorStateList.valueOf(if (on) LerixUi.onColor(c) else muted)
                }
                else -> {
                    val own = if (byOption) LerixUi.parseHex(choice.color) else null
                    val (f, s, t) = when {
                        own != null && on -> Triple(own, own, LerixUi.onColor(own))
                        own != null -> Triple(LerixUi.mix(bg, own, 0.14f), LerixUi.mix(bg, own, 0.45f), LerixUi.mix(fg, own, 0.80f))
                        on -> Triple(accent, accent, LerixUi.onColor(accent))
                        else -> Triple(bg, border, fg)
                    }
                    (view as TextView).setTextColor(t)
                    view.background = LerixUi.ripple(ctx, LerixUi.shaped(ctx, shape, f, dp(8).toFloat(), s, dp(1)))
                }
            }
        }
    }

    companion object {
        val STYLES = setOf("stars", "hearts", "emoji", "thumbs", "numbers")

        /** Fill of stars / hearts when the dashboard sets no accent. */
        val ICON_FILL = mapOf("stars" to 0xFFF5B301.toInt(), "hearts" to 0xFFE5484D.toInt())

        /** `thumbs` needs exactly two choices; anything unknown falls back to stars. */
        fun resolveStyle(requested: String?, choiceCount: Int): String =
            if (requested in STYLES && (requested != "thumbs" || choiceCount == 2)) requested!! else "stars"
    }
}
