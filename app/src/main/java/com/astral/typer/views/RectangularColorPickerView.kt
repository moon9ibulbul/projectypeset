package com.astral.typer.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class RectangularColorPickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.GRAY
    }
    private val selectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }

    private val rectBox = RectF()
    private val rectHue = RectF()

    private var hue = 0f // 0..360
    private var sat = 1f // 0..1
    private var `val` = 1f // 0..1

    var onColorChangedListener: ((Int) -> Unit)? = null

    // Hue Slider colors (Top to Bottom)
    // Red -> Magenta -> Blue -> Cyan -> Green -> Yellow -> Red
    // Standard HSV progression
    private val hueColors = intArrayOf(
        Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val hueWidth = 80f
        val padding = 20f

        rectHue.set(w - hueWidth - padding, padding, w - padding, h - padding)
        rectBox.set(padding, padding, w - hueWidth - padding * 2, h - padding)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Draw Hue Slider
        val hueShader = LinearGradient(
            rectHue.centerX(), rectHue.top, rectHue.centerX(), rectHue.bottom,
            hueColors, null, Shader.TileMode.CLAMP
        )
        paint.shader = hueShader
        paint.style = Paint.Style.FILL
        canvas.drawRect(rectHue, paint)
        canvas.drawRect(rectHue, borderPaint)

        // Draw Hue Selector
        val hueY = rectHue.top + (rectHue.height() * (hue / 360f))
        canvas.drawRect(rectHue.left - 4, hueY - 5, rectHue.right + 4, hueY + 5, selectorPaint)

        // 2. Draw Sat/Val Box
        // Layer 1: Saturation (Left: White -> Right: Hue Color)
        val hueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        val satShader = LinearGradient(
            rectBox.left, rectBox.top, rectBox.right, rectBox.top,
            Color.WHITE, hueColor, Shader.TileMode.CLAMP
        )
        paint.shader = satShader
        canvas.drawRect(rectBox, paint)

        // Layer 2: Value (Top: Transparent -> Bottom: Black)
        val valShader = LinearGradient(
            rectBox.left, rectBox.top, rectBox.left, rectBox.bottom,
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
        )
        paint.shader = valShader
        canvas.drawRect(rectBox, paint)

        canvas.drawRect(rectBox, borderPaint)

        // Draw Sat/Val Selector
        val sx = rectBox.left + (rectBox.width() * sat)
        val sy = rectBox.top + (rectBox.height() * (1f - `val`))
        canvas.drawCircle(sx, sy, 10f, selectorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (x >= rectHue.left - 30) { // Hit test with padding
                    // Hue Interaction
                    var h = (y - rectHue.top) / rectHue.height()
                    h = h.coerceIn(0f, 1f)
                    hue = h * 360f
                    invalidate()
                } else if (x <= rectBox.right + 20) {
                    // Box Interaction
                    var s = (x - rectBox.left) / rectBox.width()
                    var v = (y - rectBox.top) / rectBox.height()
                    sat = s.coerceIn(0f, 1f)
                    `val` = 1f - v.coerceIn(0f, 1f)
                    invalidate()
                }

                val color = Color.HSVToColor(floatArrayOf(hue, sat, `val`))
                onColorChangedListener?.invoke(color)
            }
        }
        return true
    }
}
