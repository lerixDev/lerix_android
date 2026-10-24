package com.lerix.sdk.feedback

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.lerix.sdk.R
import com.lerix.sdk.core.LerixLifecycle
import com.lerix.sdk.ui.LerixUi
import java.lang.ref.WeakReference
import kotlin.math.sqrt

/**
 * While the user picks another screen to capture, a small "Capture / Cancel"
 * bar floats over whichever host activity is resumed. It is removed before
 * the screenshot is taken, so it never appears in it.
 */
internal object LerixCaptureOverlay : LerixLifecycle.Listener {
    private var onCapture: ((Activity) -> Unit)? = null
    private var onCancel: ((Activity) -> Unit)? = null
    private var attached: WeakReference<View>? = null
    private var active = false

    fun show(onCapture: (Activity) -> Unit, onCancel: (Activity) -> Unit) {
        this.onCapture = onCapture
        this.onCancel = onCancel
        active = true
        LerixLifecycle.addListener(this)
        LerixLifecycle.currentActivity()?.let { attach(it) }
    }

    fun hide() {
        active = false
        detach()
        LerixLifecycle.removeListener(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (!active || LerixLifecycle.isInternal(activity)) return
        attach(activity)
    }

    private fun attach(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        val existing = attached?.get()
        if (existing != null && existing.parent === decor) return
        detach()
        val bar = build(activity)
        decor.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = LerixUi.dp(activity, 48) },
        )
        attached = WeakReference(bar)
    }

    private fun detach() {
        val view = attached?.get()
        (view?.parent as? ViewGroup)?.removeView(view)
        attached = null
    }

    private fun build(activity: Activity): View {
        val draft = LerixFeedbackFlow.draft
        val accent = draft?.accent ?: LerixUi.resolveAccent(activity)
        val ctx: Context = activity
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = LerixUi.dp(ctx, 12)
            setPadding(pad, pad, pad, pad)
            background = LerixUi.rounded(
                LerixUi.color(ctx, R.color.lerix_background),
                LerixUi.dp(ctx, 20).toFloat(),
                LerixUi.color(ctx, R.color.lerix_border),
                LerixUi.dp(ctx, 1),
            )
            elevation = LerixUi.dp(ctx, 8).toFloat()
            isClickable = true
        }
        bar.addView(
            LerixUi.body(ctx, ctx.getString(R.string.lerix_feedback_capture_hint), secondary = true).apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(0, 0, 0, LerixUi.dp(ctx, 8))
            },
        )
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val cancel = LerixUi.button(ctx, ctx.getString(R.string.lerix_cancel), primary = false, accent = accent)
        val capture = LerixUi.button(ctx, ctx.getString(R.string.lerix_feedback_capture), primary = true, accent = accent)
        cancel.setOnClickListener {
            hide()
            onCancel?.invoke(activity)
        }
        capture.setOnClickListener {
            hide()
            val decor = activity.window.decorView
            // Wait two frames so the bar is gone from the window before capturing.
            decor.postOnAnimation { decor.postOnAnimation { onCapture?.invoke(activity) } }
        }
        row.addView(cancel)
        row.addView(View(ctx), LinearLayout.LayoutParams(LerixUi.dp(ctx, 8), 1))
        row.addView(capture)
        bar.addView(row)
        return bar
    }
}

/** Opt-in shake-to-report: accelerometer listener registered only while the app is in the foreground. */
internal object LerixShakeDetector : SensorEventListener, LerixLifecycle.Listener {
    private const val THRESHOLD_G = 2.7f
    private const val WINDOW_MS = 600L
    private const val COOLDOWN_MS = 2_000L

    private var sensorManager: SensorManager? = null
    private var enabled = false
    private var listening = false
    private var options = LerixFeedbackOptions()
    private var firstShakeAt = 0L
    private var lastTriggerAt = 0L

    fun enable(context: Context, options: LerixFeedbackOptions) {
        this.options = options
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        enabled = true
        LerixLifecycle.addListener(this)
        if (LerixLifecycle.inForeground) startListening()
    }

    fun disable() {
        enabled = false
        stopListening()
        LerixLifecycle.removeListener(this)
    }

    override fun onForeground() {
        if (enabled) startListening()
    }

    override fun onBackground() = stopListening()

    private fun startListening() {
        if (listening) return
        val sm = sensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        listening = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopListening() {
        if (!listening) return
        sensorManager?.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gx = event.values[0] / SensorManager.GRAVITY_EARTH
        val gy = event.values[1] / SensorManager.GRAVITY_EARTH
        val gz = event.values[2] / SensorManager.GRAVITY_EARTH
        val g = sqrt(gx * gx + gy * gy + gz * gz)
        if (g < THRESHOLD_G) return
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < COOLDOWN_MS) return
        // Two strong movements within the window count as a shake.
        if (firstShakeAt == 0L || now - firstShakeAt > WINDOW_MS) {
            firstShakeAt = now
            return
        }
        if (now - firstShakeAt < 150) return // same jolt, not a second movement
        firstShakeAt = 0L
        lastTriggerAt = now
        if (LerixFeedbackFlow.isActive()) return
        val activity = LerixLifecycle.currentActivity() ?: return
        LerixFeedback.show(activity, options)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
