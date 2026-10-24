package com.lerix.sdk.feedback

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import com.lerix.sdk.core.LerixLog
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference

/** Screenshot capture of the host window, with confidential views masked. */
internal object LerixScreenshot {
    private const val MAX_SIDE = 2048
    private const val MAX_BYTES = 4_500_000 // server limit is 5 MB
    private val confidential = mutableListOf<WeakReference<View>>()

    fun markConfidential(view: View) {
        synchronized(confidential) {
            confidential.removeAll { it.get() == null }
            if (confidential.none { it.get() === view }) confidential += WeakReference(view)
        }
    }

    fun unmarkConfidential(view: View) {
        synchronized(confidential) { confidential.removeAll { it.get() == null || it.get() === view } }
    }

    fun isSecure(activity: Activity): Boolean =
        (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    /**
     * Captures [activity]'s window. Calls back on the main thread with null for
     * a `FLAG_SECURE` window or on failure. PixelCopy (API 26+) also captures
     * SurfaceViews such as maps and video; older devices fall back to View.draw.
     */
    fun capture(activity: Activity, callback: (Bitmap?) -> Unit) {
        try {
            if (isSecure(activity)) {
                callback(null)
                return
            }
            val decor = activity.window.decorView
            val w = decor.width
            val h = decor.height
            if (w <= 0 || h <= 0) {
                callback(null)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                try {
                    PixelCopy.request(
                        activity.window,
                        bitmap,
                        { result ->
                            if (result == PixelCopy.SUCCESS) {
                                callback(finish(decor, bitmap))
                            } else {
                                callback(drawFallback(decor))
                            }
                        },
                        Handler(Looper.getMainLooper()),
                    )
                    return
                } catch (e: Exception) {
                    LerixLog.debug("PixelCopy failed, falling back to View.draw: $e")
                }
            }
            callback(drawFallback(decor))
        } catch (t: Throwable) {
            LerixLog.debug("screenshot failed: $t")
            callback(null)
        }
    }

    private fun drawFallback(decor: View): Bitmap? = try {
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        finish(decor, bitmap)
    } catch (t: Throwable) {
        LerixLog.debug("View.draw capture failed: $t")
        null
    }

    private fun finish(decor: View, bitmap: Bitmap): Bitmap {
        mask(decor, bitmap)
        return downscale(bitmap)
    }

    /** Black rectangle over every attached, visible confidential view in this window. */
    private fun mask(decor: View, bitmap: Bitmap) {
        val views = synchronized(confidential) { confidential.mapNotNull { it.get() } }
        if (views.isEmpty()) return
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.BLACK }
        val decorLoc = IntArray(2).also { decor.getLocationInWindow(it) }
        val loc = IntArray(2)
        for (view in views) {
            if (!view.isAttachedToWindow || !view.isShown || view.rootView !== decor) continue
            view.getLocationInWindow(loc)
            val left = loc[0] - decorLoc[0]
            val top = loc[1] - decorLoc[1]
            canvas.drawRect(Rect(left, top, left + view.width, top + view.height), paint)
        }
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_SIDE) return bitmap
        val scale = MAX_SIDE.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** PNG, or JPEG when the PNG is too large for the 5 MB upload limit. */
    fun encode(bitmap: Bitmap): Pair<ByteArray, String> {
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        if (png.size <= MAX_BYTES) return png to "image/png"
        var quality = 85
        while (true) {
            val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            if (jpeg.size <= MAX_BYTES || quality <= 40) return jpeg to "image/jpeg"
            quality -= 15
        }
    }

    /** Image type from magic bytes (headless submit), or null when unsupported. */
    fun sniffMime(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }
}
