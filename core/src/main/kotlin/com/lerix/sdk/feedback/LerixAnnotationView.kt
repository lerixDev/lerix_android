package com.lerix.sdk.feedback

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Draws pen strokes over a screenshot. Strokes are stored in bitmap
 * coordinates, so [render] produces a full-resolution result whatever the
 * on-screen size. Undo/redo: a new stroke clears the redo stack.
 */
@SuppressLint("ViewConstructor")
internal class LerixAnnotationView(context: Context, private val bitmap: Bitmap) : View(context) {
    private class Stroke(val path: Path, val color: Int, val width: Float)

    private val strokes = mutableListOf<Stroke>()
    private val redoStack = ArrayDeque<Stroke>()
    private var current: Stroke? = null
    private var lastX = 0f
    private var lastY = 0f

    private val dst = RectF()
    private var scale = 1f
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    var penColor: Int = 0xFFE5484D.toInt()

    /** Pen width in dp on screen; converted to bitmap pixels when a stroke starts. */
    var penWidthDp: Float = 4f

    var onHistoryChanged: (() -> Unit)? = null

    val canUndo: Boolean get() = strokes.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val hasStrokes: Boolean get() = strokes.isNotEmpty()

    fun undo() {
        if (strokes.isEmpty()) return
        redoStack.addLast(strokes.removeAt(strokes.size - 1))
        invalidate()
        onHistoryChanged?.invoke()
    }

    fun redo() {
        val stroke = redoStack.removeLastOrNull() ?: return
        strokes += stroke
        invalidate()
        onHistoryChanged?.invoke()
    }

    /** The screenshot with every stroke burnt in, at full resolution. */
    fun render(): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        strokes.forEach { drawStroke(canvas, it) }
        return out
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val availW = (w - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
        val availH = (h - paddingTop - paddingBottom).toFloat().coerceAtLeast(1f)
        scale = minOf(availW / bitmap.width, availH / bitmap.height)
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        val left = paddingLeft + (availW - dw) / 2
        val top = paddingTop + (availH - dh) / 2
        dst.set(left, top, left + dw, top + dh)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
        canvas.save()
        canvas.clipRect(dst)
        canvas.translate(dst.left, dst.top)
        canvas.scale(scale, scale)
        strokes.forEach { drawStroke(canvas, it) }
        current?.let { drawStroke(canvas, it) }
        canvas.restore()
    }

    private fun drawStroke(canvas: Canvas, stroke: Stroke) {
        strokePaint.color = stroke.color
        strokePaint.strokeWidth = stroke.width
        canvas.drawPath(stroke.path, strokePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (scale <= 0f) return false
        val x = ((event.x - dst.left) / scale).coerceIn(0f, bitmap.width.toFloat())
        val y = ((event.y - dst.top) / scale).coerceIn(0f, bitmap.height.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val widthPx = penWidthDp * resources.displayMetrics.density / scale
                current = Stroke(Path().apply { moveTo(x, y); lineTo(x + 0.1f, y + 0.1f) }, penColor, widthPx)
                lastX = x
                lastY = y
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val stroke = current ?: return false
                stroke.path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
                lastX = x
                lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val stroke = current ?: return false
                stroke.path.lineTo(x, y)
                strokes += stroke
                redoStack.clear()
                current = null
                invalidate()
                onHistoryChanged?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> {
                current = null
                invalidate()
            }
        }
        return true
    }
}
