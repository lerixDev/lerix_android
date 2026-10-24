package com.lerix.sdk.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.lerix.sdk.R
import com.lerix.sdk.core.LerixRuntime

/** Shared look for the feedback and NPS UI: plain Views, light/dark from resources, host accent colour. */
internal object LerixUi {
    @Volatile var accentOverride: Int? = null

    private const val DEFAULT_ACCENT = 0xFF4F46E5.toInt()

    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    fun dp(context: Context, value: Int): Int = dp(context, value.toFloat())

    /** Override, else the host theme's `colorPrimary` (AppCompat/Material, then platform), else `colorAccent`. */
    @SuppressLint("DiscouragedApi") // the host's colorPrimary attr id is only known by name
    fun resolveAccent(activity: Activity, explicit: Int? = null): Int {
        explicit?.let { return it }
        accentOverride?.let { return it }
        val tv = TypedValue()
        val appAttr = activity.resources.getIdentifier("colorPrimary", "attr", activity.packageName)
        val candidates = listOfNotNull(
            appAttr.takeIf { it != 0 },
            android.R.attr.colorPrimary,
            android.R.attr.colorAccent,
        )
        for (attr in candidates) {
            if (activity.theme.resolveAttribute(attr, tv, true) &&
                tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT
            ) {
                // Ignore the platform defaults that are near-black/near-white; they make poor accents.
                val c = tv.data
                if (Color.alpha(c) > 200 && luminance(c) in 0.04..0.9) return c
            }
        }
        return DEFAULT_ACCENT
    }

    fun luminance(color: Int): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color))
    }

    fun onColor(color: Int): Int = if (luminance(color) > 0.45) Color.BLACK else Color.WHITE

    fun color(context: Context, res: Int): Int = context.getColor(res)

    /** `#RRGGBB` → an opaque colour int, or null. */
    fun parseHex(hex: String?): Int? {
        if (hex == null || !Regex("^#[0-9a-fA-F]{6}$").matches(hex)) return null
        return (0xFF000000 or hex.substring(1).toLong(16)).toInt()
    }

    /** [ratio] of [color] over [base] (like CSS `color-mix(in srgb, color ratio, base)`). */
    fun mix(base: Int, color: Int, ratio: Float): Int = ColorUtils.blendARGB(base, color, ratio)

    /**
     * A survey button background in the dashboard's `shape`: `circle` (oval),
     * `square` (2dp), `pill` (fully round ends), else `rounded` ([roundedRadius]).
     */
    fun shaped(
        context: Context,
        shape: String,
        fill: Int,
        roundedRadius: Float,
        strokeColor: Int? = null,
        strokeWidth: Int = 0,
    ): GradientDrawable = GradientDrawable().apply {
        when (shape) {
            "circle" -> this.shape = GradientDrawable.OVAL
            "square" -> cornerRadius = dp(context, 2).toFloat()
            "pill" -> cornerRadius = dp(context, 999).toFloat()
            else -> cornerRadius = roundedRadius
        }
        setColor(fill)
        if (strokeColor != null && strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

    /** Announces [view] to TalkBack as a radio button that is checked when [checked] says so. */
    fun radio(view: View, checked: () -> Boolean) {
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = RadioButton::class.java.name
                info.isCheckable = true
                info.isChecked = checked()
            }
        }
    }

    fun rounded(color: Int, radius: Float, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
            if (strokeColor != null && strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    @Suppress("UNUSED_PARAMETER")
    fun ripple(context: Context, content: Drawable, rippleColor: Int = 0x33808080): Drawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)

    fun button(context: Context, text: CharSequence, primary: Boolean, accent: Int): TextView =
        TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minHeight = dp(context, 48)
            minimumHeight = dp(context, 48)
            setPaddingRelative(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 10))
            isClickable = true
            isFocusable = true
            val radius = dp(context, 12).toFloat()
            if (primary) {
                setTextColor(onColor(accent))
                background = ripple(context, rounded(accent, radius))
            } else {
                setTextColor(color(context, R.color.lerix_text_primary))
                background = ripple(
                    context,
                    rounded(color(context, R.color.lerix_surface), radius, color(context, R.color.lerix_border), dp(context, 1)),
                )
            }
        }

    fun setEnabled(view: TextView, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.4f
    }

    fun title(context: Context, text: CharSequence): TextView = TextView(context).apply {
        this.text = text
        textSize = 22f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(color(context, R.color.lerix_text_primary))
        textAlignment = TextView.TEXT_ALIGNMENT_VIEW_START
    }

    fun body(context: Context, text: CharSequence, secondary: Boolean = false): TextView = TextView(context).apply {
        this.text = text
        textSize = if (secondary) 14f else 16f
        setTextColor(color(context, if (secondary) R.color.lerix_text_secondary else R.color.lerix_text_primary))
        textAlignment = TextView.TEXT_ALIGNMENT_VIEW_START
    }

    /** Language for server-provided texts: the app's current locale language. */
    fun language(): String = try {
        LerixRuntime.locale().language.ifEmpty { "en" }
    } catch (e: Exception) {
        "en"
    }

    /** `map[locale]`, then `map.en`, then [fallback] (contract §1.1). */
    fun localized(map: Map<String, String>?, fallback: String): String {
        if (map.isNullOrEmpty()) return fallback
        val lang = language()
        return map[lang]?.takeIf { it.isNotBlank() }
            ?: map.entries.firstOrNull { it.key.substringBefore('-').equals(lang, true) && it.value.isNotBlank() }?.value
            ?: map["en"]?.takeIf { it.isNotBlank() }
            ?: fallback
    }

    @Suppress("DEPRECATION")
    fun applySystemInsets(root: android.view.View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        root.requestApplyInsets()
    }
}
