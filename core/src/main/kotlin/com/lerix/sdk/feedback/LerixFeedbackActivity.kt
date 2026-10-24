package com.lerix.sdk.feedback

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.lerix.sdk.R
import com.lerix.sdk.analytics.LerixAnalytics
import com.lerix.sdk.core.LerixRuntime
import com.lerix.sdk.ui.LerixUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The feedback flow UI (internal; started by `Lerix.feedback.show`). Plain
 * Views, no AppCompat/Material/Compose. Layouts use start/end so they mirror
 * in RTL (Arabic) when the host app declares `android:supportsRtl="true"`.
 */
class LerixFeedbackActivity : Activity() {
    private lateinit var draft: LerixFeedbackDraft
    private lateinit var header: TextView
    private lateinit var stepLabel: TextView
    private lateinit var content: FrameLayout
    private lateinit var footer: LinearLayout
    private var annotationView: LerixAnnotationView? = null
    private var finishedByUser = false
    private var backCallback: Any? = null
    private val accent get() = draft.accent

    private val penColors = intArrayOf(0xFFE5484D.toInt(), 0xFFFFC53D.toInt(), 0xFF30A46C.toInt(), 0xFF0090FF.toInt())
    private val penSizes = floatArrayOf(3f, 6f, 12f)
    private var colorIndex = 0
    private var sizeIndex = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val current = LerixFeedbackFlow.draft
        if (current == null) {
            // Process was recreated without the flow (e.g. after being killed): nothing to show.
            finishedByUser = true
            finish()
            return
        }
        draft = current
        setupWindow()
        setContentView(buildShell())
        registerBack()
        render()
    }

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
        val ctx: Context = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(LerixUi.color(ctx, R.color.lerix_background))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        LerixUi.applySystemInsets(root)

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(20), dp(8), dp(8), 0)
        }
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        header = TextView(ctx).apply {
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(LerixUi.color(ctx, R.color.lerix_text_primary))
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        stepLabel = TextView(ctx).apply {
            textSize = 12f
            setTextColor(LerixUi.color(ctx, R.color.lerix_text_secondary))
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        titles.addView(header)
        titles.addView(stepLabel)
        top.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = TextView(ctx).apply {
            text = "✕"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(LerixUi.color(ctx, R.color.lerix_text_secondary))
            contentDescription = getString(R.string.lerix_close)
            background = LerixUi.ripple(ctx, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) })
            setOnClickListener { cancelFlow() }
        }
        top.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(top)

        content = FrameLayout(ctx)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(20), dp(12), dp(20), dp(16))
        }
        root.addView(footer)
        return root
    }

    // ---- rendering ----

    private fun render() {
        content.removeAllViews()
        footer.removeAllViews()
        annotationView = null
        val annotate = draft.annotateIndex
        if (annotate != null && draft.shots.getOrNull(annotate) != null) {
            renderAnnotate(annotate)
            return
        }
        draft.annotateIndex = null
        header.text = getString(R.string.lerix_feedback_title)
        val steps = draft.steps
        val index = steps.indexOf(draft.step)
        stepLabel.text = if (index >= 0) getString(R.string.lerix_feedback_step, index + 1, steps.size) else ""
        stepLabel.visibility = if (index >= 0) View.VISIBLE else View.GONE
        when (draft.step) {
            LerixFeedbackStep.MESSAGE -> renderMessage()
            LerixFeedbackStep.LABELS -> renderLabels()
            LerixFeedbackStep.SCREENSHOTS -> renderScreenshots()
            LerixFeedbackStep.EMAIL -> renderEmail()
            LerixFeedbackStep.REVIEW -> renderReview()
            LerixFeedbackStep.SUCCESS -> renderSuccess()
        }
    }

    private fun scrollColumn(): LinearLayout {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(20), dp(16), dp(20), dp(16))
        }
        scroll.addView(column)
        content.addView(scroll)
        return column
    }

    private fun renderMessage() {
        val column = scrollColumn()
        column.addView(LerixUi.title(this, getString(R.string.lerix_feedback_message_title)))
        val max = draft.config.maxMessageLength
        val input = EditText(this).apply {
            setText(draft.message)
            hint = getString(R.string.lerix_feedback_message_hint)
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(LerixUi.color(context, R.color.lerix_text_primary))
            setHintTextColor(LerixUi.color(context, R.color.lerix_text_secondary))
            background = field()
            setPaddingRelative(dp(14), dp(12), dp(14), dp(12))
        }
        column.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        val counter = LerixUi.body(this, "", secondary = true).apply {
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            setPadding(0, dp(6), 0, 0)
        }
        column.addView(counter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val next = addFooter(back = false, nextText = getString(R.string.lerix_next)) { goNext() }
        fun update() {
            val length = draft.message.trim().length
            counter.text = getString(R.string.lerix_feedback_message_count, draft.message.length, max)
            counter.setTextColor(LerixUi.color(this, if (draft.message.length > max) R.color.lerix_error else R.color.lerix_text_secondary))
            LerixUi.setEnabled(next, length > 0 && draft.message.length <= max)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                draft.message = s?.toString() ?: ""
                update()
            }
        })
        update()
        input.requestFocus()
        input.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0) }
    }

    private fun renderLabels() {
        val column = scrollColumn()
        column.addView(LerixUi.title(this, getString(R.string.lerix_feedback_labels_title)))
        column.addView(LerixUi.body(this, getString(R.string.lerix_feedback_labels_subtitle), secondary = true).apply { setPadding(0, dp(4), 0, dp(12)) })
        for (label in draft.visibleLabels) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(dp(16), dp(14), dp(16), dp(14))
                isClickable = true
            }
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(parseColor(label.color) ?: accent)
                }
            }
            row.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) })
            row.addView(
                LerixUi.body(this, LerixUi.localized(label.title, label.key)),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val check = TextView(this).apply {
                text = "✓"
                textSize = 18f
                setTextColor(accent)
            }
            row.addView(check)
            fun paint() {
                val selected = label.key in draft.labels
                check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
                row.background = LerixUi.ripple(
                    this,
                    LerixUi.rounded(
                        LerixUi.color(this, R.color.lerix_surface),
                        dp(12).toFloat(),
                        if (selected) accent else LerixUi.color(this, R.color.lerix_border),
                        dp(if (selected) 2 else 1),
                    ),
                )
                row.isSelected = selected
            }
            row.setOnClickListener {
                if (!draft.labels.add(label.key)) draft.labels.remove(label.key)
                paint()
            }
            paint()
            column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        addFooter(back = true, nextText = getString(R.string.lerix_next)) { goNext() }
    }

    private fun renderScreenshots() {
        hideKeyboard()
        val column = scrollColumn()
        column.addView(LerixUi.title(this, getString(R.string.lerix_feedback_screenshots_title)))
        column.addView(LerixUi.body(this, getString(R.string.lerix_feedback_screenshots_subtitle), secondary = true).apply { setPadding(0, dp(4), 0, dp(16)) })
        if (draft.secureScreen) {
            column.addView(LerixUi.body(this, getString(R.string.lerix_feedback_screenshots_secure), secondary = true).apply { setPadding(0, 0, 0, dp(12)) })
        }
        if (draft.shots.isEmpty()) {
            column.addView(LerixUi.body(this, getString(R.string.lerix_feedback_screenshots_empty), secondary = true).apply { setPadding(0, 0, 0, dp(12)) })
        } else {
            val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            draft.shots.forEachIndexed { index, shot -> row.addView(thumbnail(index, shot)) }
            scroller.addView(row)
            column.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })
        }
        if (draft.shots.size < draft.config.maxScreenshots) {
            val add = LerixUi.button(this, getString(R.string.lerix_feedback_add_screenshot), primary = false, accent = accent)
            add.setOnClickListener {
                finishedByUser = true
                LerixFeedbackFlow.beginCapture(this)
            }
            column.addView(add, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addFooter(back = true, nextText = getString(R.string.lerix_next)) { goNext() }
    }

    private fun thumbnail(index: Int, shot: LerixShot): View {
        val width = dp(120)
        val height = (width * shot.height.toFloat() / shot.width.coerceAtLeast(1)).toInt().coerceIn(dp(80), dp(240))
        val frame = FrameLayout(this).apply {
            background = LerixUi.rounded(LerixUi.color(context, R.color.lerix_surface), dp(10).toFloat(), LerixUi.color(context, R.color.lerix_border), dp(1))
            setPadding(dp(1), dp(1), dp(1), dp(1))
            clipToOutline = true
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.lerix_feedback_annotate_title)
            setOnClickListener {
                draft.annotateIndex = index
                render()
            }
        }
        frame.addView(image, FrameLayout.LayoutParams(width, height))
        LerixRuntime.scope.launch {
            val bitmap = LerixFeedbackFlow.decode(shot.file, maxSide = 480)
            withContext(Dispatchers.Main) { if (!isDestroyed) image.setImageBitmap(bitmap) }
        }
        val remove = TextView(this).apply {
            text = "✕"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC000000.toInt()) }
            contentDescription = getString(R.string.lerix_feedback_remove_screenshot)
            setOnClickListener {
                draft.shots.removeAt(index).file.delete()
                render()
            }
        }
        frame.addView(remove, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        return frame.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) }
        }
    }

    private fun renderAnnotate(index: Int) {
        hideKeyboard()
        header.text = getString(R.string.lerix_feedback_annotate_title)
        stepLabel.visibility = View.GONE
        val shot = draft.shots[index]
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(column)
        val holder = FrameLayout(this)
        column.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(16), dp(8), dp(16), 0)
        }
        column.addView(toolbar)

        val done = addFooterAnnotate()
        LerixUi.setEnabled(done, false)

        LerixRuntime.scope.launch {
            val bitmap = LerixFeedbackFlow.decode(shot.file)
            withContext(Dispatchers.Main) {
                if (isDestroyed) return@withContext
                if (bitmap == null) {
                    draft.annotateIndex = null
                    render()
                    return@withContext
                }
                val view = LerixAnnotationView(this@LerixFeedbackActivity, bitmap).apply {
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    penColor = penColors[colorIndex]
                    penWidthDp = penSizes[sizeIndex]
                }
                annotationView = view
                holder.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                buildToolbar(toolbar, view)
                LerixUi.setEnabled(done, true)
            }
        }
    }

    private fun buildToolbar(toolbar: LinearLayout, view: LerixAnnotationView) {
        toolbar.removeAllViews()
        val swatches = mutableListOf<View>()
        penColors.forEachIndexed { i, c ->
            val swatch = View(this).apply {
                contentDescription = getString(R.string.lerix_feedback_pen_color, i + 1)
                isClickable = true
            }
            swatches += swatch
            swatch.setOnClickListener {
                colorIndex = i
                view.penColor = c
                paintSwatches(swatches)
            }
            toolbar.addView(swatch, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) })
        }
        paintSwatches(swatches)

        val size = TextView(this).apply {
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.lerix_feedback_pen_size)
            setTextColor(LerixUi.color(context, R.color.lerix_text_primary))
            background = LerixUi.ripple(context, LerixUi.rounded(LerixUi.color(context, R.color.lerix_surface), dp(16).toFloat()))
        }
        fun paintSize() {
            size.text = "●"
            size.textSize = 8f + sizeIndex * 6f
        }
        paintSize()
        size.setOnClickListener {
            sizeIndex = (sizeIndex + 1) % penSizes.size
            view.penWidthDp = penSizes[sizeIndex]
            paintSize()
        }
        toolbar.addView(size, LinearLayout.LayoutParams(dp(40), dp(40)))
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val undo = iconButton("↶", getString(R.string.lerix_undo)) { view.undo() }
        val redo = iconButton("↷", getString(R.string.lerix_redo)) { view.redo() }
        toolbar.addView(undo, LinearLayout.LayoutParams(dp(44), dp(44)))
        toolbar.addView(redo, LinearLayout.LayoutParams(dp(44), dp(44)))
        fun history() {
            LerixUi.setEnabled(undo, view.canUndo)
            LerixUi.setEnabled(redo, view.canRedo)
        }
        view.onHistoryChanged = { history() }
        history()
    }

    private fun paintSwatches(swatches: List<View>) {
        swatches.forEachIndexed { i, s ->
            s.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(penColors[i])
                if (i == colorIndex) setStroke(dp(3), LerixUi.color(this@LerixFeedbackActivity, R.color.lerix_text_primary))
            }
        }
    }

    private fun iconButton(symbol: String, description: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = symbol
        textSize = 22f
        gravity = Gravity.CENTER
        contentDescription = description
        setTextColor(LerixUi.color(context, R.color.lerix_text_primary))
        background = LerixUi.ripple(context, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) })
        setOnClickListener { onClick() }
    }

    private fun addFooterAnnotate(): TextView {
        val cancel = LerixUi.button(this, getString(R.string.lerix_cancel), primary = false, accent = accent)
        val done = LerixUi.button(this, getString(R.string.lerix_done), primary = true, accent = accent)
        cancel.setOnClickListener {
            draft.annotateIndex = null
            render()
        }
        done.setOnClickListener {
            val view = annotationView
            val index = draft.annotateIndex
            if (view == null || index == null || !view.hasStrokes) {
                draft.annotateIndex = null
                render()
                return@setOnClickListener
            }
            LerixUi.setEnabled(done, false)
            val rendered: Bitmap = view.render()
            LerixRuntime.scope.launch {
                LerixFeedbackFlow.replaceShot(draft, index, rendered)
                rendered.recycle()
                withContext(Dispatchers.Main) {
                    draft.annotateIndex = null
                    if (!isDestroyed) render()
                }
            }
        }
        footer.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        footer.addView(done, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return done
    }

    private fun renderEmail() {
        val column = scrollColumn()
        column.addView(LerixUi.title(this, getString(R.string.lerix_feedback_email_title)))
        val required = draft.emailMode == LerixFieldMode.REQUIRED
        column.addView(
            LerixUi.body(
                this,
                getString(if (required) R.string.lerix_feedback_email_required else R.string.lerix_feedback_email_optional),
                secondary = true,
            ).apply { setPadding(0, dp(4), 0, dp(16)) },
        )
        val input = EditText(this).apply {
            setText(draft.email)
            hint = getString(R.string.lerix_feedback_email_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            isSingleLine = true
            textDirection = View.TEXT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTextColor(LerixUi.color(context, R.color.lerix_text_primary))
            setHintTextColor(LerixUi.color(context, R.color.lerix_text_secondary))
            background = field()
            setPaddingRelative(dp(14), dp(12), dp(14), dp(12))
        }
        column.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val error = LerixUi.body(this, getString(R.string.lerix_feedback_email_invalid), secondary = true).apply {
            setTextColor(LerixUi.color(context, R.color.lerix_error))
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        column.addView(error)
        val next = addFooter(back = true, nextText = getString(R.string.lerix_next)) {
            val value = draft.email.trim()
            val ok = if (value.isEmpty()) !required else LerixFeedback.isValidEmail(value)
            if (!ok) {
                error.visibility = View.VISIBLE
            } else {
                draft.email = value
                goNext()
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                draft.email = s?.toString() ?: ""
                error.visibility = View.GONE
                LerixUi.setEnabled(next, !required || draft.email.isNotBlank())
            }
        })
        LerixUi.setEnabled(next, !required || draft.email.isNotBlank())
        if (draft.email.isEmpty()) {
            input.requestFocus()
            input.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0) }
        }
    }

    private fun renderReview() {
        hideKeyboard()
        val column = scrollColumn()
        column.addView(LerixUi.title(this, getString(R.string.lerix_feedback_review_title)))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(16), dp(8), dp(16), dp(8))
            background = LerixUi.rounded(LerixUi.color(context, R.color.lerix_surface), dp(12).toFloat())
        }
        column.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        val none = getString(R.string.lerix_feedback_none)
        card.addView(summaryRow(getString(R.string.lerix_feedback_message_label), draft.message.trim(), maxLines = 6))
        if (draft.visibleLabels.isNotEmpty()) {
            val titles = draft.visibleLabels.filter { it.key in draft.labels }.map { LerixUi.localized(it.title, it.key) }
            card.addView(summaryRow(getString(R.string.lerix_feedback_labels_label), titles.joinToString(", ").ifEmpty { none }))
        }
        if (draft.screenshotsEnabled) {
            card.addView(summaryRow(getString(R.string.lerix_feedback_screenshots_label), draft.shots.size.toString()))
        }
        if (draft.emailMode != LerixFieldMode.HIDDEN) {
            card.addView(summaryRow(getString(R.string.lerix_feedback_email_label), draft.email.trim().ifEmpty { none }))
        }

        val toggle = TextView(this).apply {
            textSize = 15f
            setTextColor(accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(16), 0, dp(8))
            isClickable = true
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        column.addView(toggle)
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(details)
        fun paintDetails() {
            toggle.text = getString(if (draft.detailsOpen) R.string.lerix_feedback_hide_details else R.string.lerix_feedback_show_details)
            details.removeAllViews()
            if (!draft.detailsOpen) return
            details.addView(LerixUi.body(this, getString(R.string.lerix_feedback_details_note), secondary = true).apply { setPadding(0, 0, 0, dp(8)) })
            detailRows().forEach { (k, v) -> details.addView(summaryRow(k, v)) }
        }
        toggle.setOnClickListener {
            draft.detailsOpen = !draft.detailsOpen
            paintDetails()
        }
        paintDetails()

        addFooter(back = true, nextText = getString(R.string.lerix_feedback_submit)) {
            LerixFeedbackFlow.submit(draft)
            draft.step = LerixFeedbackStep.SUCCESS
            render()
        }
    }

    private fun detailRows(): List<Pair<String, String>> {
        val app = LerixRuntime.app()
        val device = LerixRuntime.feedbackDevice()
        val screen = device.optJSONObject("screen")
        val rows = mutableListOf(
            getString(R.string.lerix_feedback_detail_platform) to "android",
            getString(R.string.lerix_feedback_detail_app) to "${app.name} (${app.packageName})",
            getString(R.string.lerix_feedback_detail_app_version) to "${app.version} (${app.buildNo})",
            getString(R.string.lerix_feedback_detail_environment) to LerixRuntime.environment,
            getString(R.string.lerix_feedback_detail_device) to device.optString("model"),
            getString(R.string.lerix_feedback_detail_os) to "Android ${device.optString("osVersion")}",
            getString(R.string.lerix_feedback_detail_locale) to device.optString("locale"),
            getString(R.string.lerix_feedback_detail_timezone) to device.optString("timeZone"),
            getString(R.string.lerix_feedback_detail_screen) to
                "${screen?.optInt("width")}×${screen?.optInt("height")} @${screen?.optDouble("pixelRatio")}",
        )
        LerixAnalytics.currentSessionId()?.let { rows += getString(R.string.lerix_feedback_detail_session) to it }
        draft.options.custom?.takeIf { it.isNotEmpty() }?.let { custom ->
            rows += getString(R.string.lerix_feedback_detail_custom) to custom.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }
        val crumbs = LerixRuntime.breadcrumbs.snapshot()
        rows += getString(R.string.lerix_feedback_detail_breadcrumbs) to
            getString(R.string.lerix_feedback_detail_breadcrumbs_count, crumbs.size) +
            crumbs.takeLast(5).joinToString("") { "\n• ${it.message}" }
        return rows
    }

    private fun summaryRow(label: String, value: String, maxLines: Int = 20): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        addView(LerixUi.body(context, label, secondary = true).apply { textSize = 12f })
        addView(LerixUi.body(context, value).apply { this.maxLines = maxLines; textSize = 15f })
    }

    private fun renderSuccess() {
        hideKeyboard()
        stepLabel.visibility = View.GONE
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        content.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val check = TextView(this).apply {
            text = "✓"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(LerixUi.onColor(accent))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
        }
        column.addView(check, LinearLayout.LayoutParams(dp(72), dp(72)))
        column.addView(LerixUi.title(this, getString(R.string.lerix_feedback_success_title)).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, dp(20), 0, dp(8))
        })
        column.addView(LerixUi.body(this, getString(R.string.lerix_feedback_success_body), secondary = true).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        })
        val close = LerixUi.button(this, getString(R.string.lerix_close), primary = true, accent = accent)
        close.setOnClickListener { completeFlow() }
        footer.addView(close, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.postDelayed({ if (!isFinishing) completeFlow() }, 2_500)
    }

    /** Back (secondary, optional) + primary button. Returns the primary button. */
    private fun addFooter(back: Boolean, nextText: String, onNext: () -> Unit): TextView {
        if (back) {
            val backButton = LerixUi.button(this, getString(R.string.lerix_back), primary = false, accent = accent)
            backButton.setOnClickListener { goBack() }
            footer.addView(backButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            footer.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        }
        val next = LerixUi.button(this, nextText, primary = true, accent = accent)
        next.setOnClickListener { if (next.isEnabled) onNext() }
        footer.addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return next
    }

    // ---- navigation ----

    private fun goNext() {
        val steps = draft.steps
        val i = steps.indexOf(draft.step)
        if (i >= 0 && i < steps.size - 1) {
            draft.step = steps[i + 1]
            render()
        }
    }

    private fun goBack() {
        if (draft.annotateIndex != null) {
            draft.annotateIndex = null
            render()
            return
        }
        if (draft.step == LerixFeedbackStep.SUCCESS) {
            completeFlow()
            return
        }
        val steps = draft.steps
        val i = steps.indexOf(draft.step)
        if (i <= 0) {
            cancelFlow()
        } else {
            draft.step = steps[i - 1]
            render()
        }
    }

    private fun cancelFlow() {
        if (::draft.isInitialized && draft.step == LerixFeedbackStep.SUCCESS) {
            completeFlow()
            return
        }
        finishedByUser = true
        hideKeyboard()
        LerixFeedbackFlow.finish(submitted = false)
        finish()
    }

    private fun completeFlow() {
        if (finishedByUser) return
        finishedByUser = true
        LerixFeedbackFlow.finish(submitted = true)
        finish()
    }

    private fun registerBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = OnBackInvokedCallback { goBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goBack()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) {
            (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        // Closed by the system (task removed) rather than through our buttons or a capture.
        if (isFinishing && !finishedByUser && ::draft.isInitialized && !draft.capturing) {
            LerixFeedbackFlow.finish(submitted = draft.step == LerixFeedbackStep.SUCCESS)
        }
        super.onDestroy()
    }

    // ---- helpers ----

    private fun field() = LerixUi.rounded(
        LerixUi.color(this, R.color.lerix_surface),
        dp(12).toFloat(),
        LerixUi.color(this, R.color.lerix_border),
        dp(1),
    )

    private fun hideKeyboard() {
        val view = currentFocus ?: return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun parseColor(value: String?): Int? = try {
        value?.let { Color.parseColor(it) }
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun dp(value: Int) = LerixUi.dp(this, value)
}
