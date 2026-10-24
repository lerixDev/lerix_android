package com.lerix.sdk.appcontrol

import android.app.Activity
import android.app.Dialog
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.lerix.sdk.R
import com.lerix.sdk.ui.LerixUi

/**
 * Available / recommended update dialog (contract §2.3): "Update", "Later",
 * and "Skip this version" when allowed. Buttons are stacked full width so
 * the order reads the same in LTR and RTL. Back closes it without saving a
 * reminder (it is shown at most once per app session anyway).
 */
internal class LerixUpdatePrompt(val owner: Activity, private val update: LerixAppUpdate) {
    private val dialog = Dialog(owner, R.style.Lerix_Theme_Dialog)

    fun show() {
        val ctx = owner
        val accent = LerixUi.resolveAccent(ctx)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = LerixUi.rounded(LerixUi.color(ctx, R.color.lerix_background), dp(20).toFloat())
            setPaddingRelative(dp(24), dp(24), dp(24), dp(16))
        }
        card.addView(
            LerixUi.title(ctx, update.title ?: ctx.getString(R.string.lerix_appctl_available_title)).apply {
                textSize = 20f
                textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            },
            matchWidth(),
        )
        card.addView(
            LerixUi.body(ctx, update.message ?: ctx.getString(R.string.lerix_appctl_available_message)).apply {
                textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            },
            matchWidth().apply { topMargin = dp(10) },
        )
        update.releaseNotes?.let { notes ->
            card.addView(
                LerixUi.body(ctx, notes, secondary = true).apply {
                    textAlignment = View.TEXT_ALIGNMENT_TEXT_START
                    maxLines = 8
                    isVerticalScrollBarEnabled = true
                    movementMethod = ScrollingMovementMethod()
                },
                matchWidth().apply { topMargin = dp(12) },
            )
        }

        val buttons = mutableListOf<TextView>()
        if (update.storeUrl != null) {
            buttons += LerixUi.button(ctx, ctx.getString(R.string.lerix_appctl_update), primary = true, accent = accent).apply {
                setOnClickListener {
                    LerixAppControl.openStore(owner)
                    close()
                }
            }
        }
        buttons += LerixUi.button(ctx, ctx.getString(R.string.lerix_appctl_later), primary = update.storeUrl == null, accent = accent).apply {
            setOnClickListener {
                close()
                LerixAppControl.later()
            }
        }
        if (update.allowSkip && update.state == LerixUpdateState.AVAILABLE) {
            buttons += LerixUi.button(ctx, ctx.getString(R.string.lerix_appctl_skip_version), primary = false, accent = accent).apply {
                background = null
                setTextColor(LerixUi.color(ctx, R.color.lerix_text_secondary))
                setOnClickListener {
                    close()
                    LerixAppControl.skip()
                }
            }
        }
        buttons.forEachIndexed { i, b ->
            card.addView(b, matchWidth().apply { topMargin = if (i == 0) dp(20) else dp(8) })
        }

        dialog.setContentView(card)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { LerixAppControlUi.onPromptClosed(this) }
        dialog.window?.let { w ->
            val width = (owner.resources.displayMetrics.widthPixels - dp(48)).coerceAtMost(dp(420))
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    /** Dismissed by the SDK (state changed, activity finishing); no callbacks. */
    fun dismissQuietly() {
        close()
    }

    private fun close() {
        if (dialog.isShowing) runCatching { dialog.dismiss() }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int) = LerixUi.dp(owner, value)
}
