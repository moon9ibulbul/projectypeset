package com.astral.typer.models

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint

class TextLayer(
    var text: String = "Double tap to edit",
    var color: Int = Color.BLACK
) : Layer() {

    var fontSize: Float = 100f
    var typeface: Typeface = Typeface.DEFAULT

    // Advanced Properties
    var opacity: Int = 255 // 0-255
    var shadowColor: Int = Color.GRAY
    var shadowRadius: Float = 0f
    var shadowDx: Float = 0f
    var shadowDy: Float = 0f

    // Gradient
    var isGradient: Boolean = false
    var gradientStartColor: Int = Color.RED
    var gradientEndColor: Int = Color.BLUE

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var cachedLayout: StaticLayout? = null

    // We can allow custom width for text wrapping, or wrap content
    // For MVP, let's just use single line or simple wrap
    private var maxWidth: Int = 1000

    // Custom width for wrapping. If null or <=0, it wraps at maxWidth or fits content
    var boxWidth: Float? = null

    override fun getWidth(): Float {
        ensureLayout()
        return cachedLayout?.width?.toFloat() ?: 0f
    }

    override fun getHeight(): Float {
        ensureLayout()
        return cachedLayout?.height?.toFloat() ?: 0f
    }

    private fun ensureLayout() {
        textPaint.textSize = fontSize
        textPaint.color = color
        textPaint.typeface = typeface
        textPaint.alpha = opacity

        if (shadowRadius > 0) {
            textPaint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
        } else {
            textPaint.clearShadowLayer()
        }

        // Gradient
        if (isGradient) {
            val width = textPaint.measureText(text)
            textPaint.shader = android.graphics.LinearGradient(
                0f, 0f, width, 0f,
                gradientStartColor, gradientEndColor,
                android.graphics.Shader.TileMode.CLAMP
            )
        } else {
            textPaint.shader = null
        }

        // Simple measurement for now
        val measuredWidth = textPaint.measureText(text)

        val layoutWidth = if (boxWidth != null && boxWidth!! > 0) {
            boxWidth!!.toInt()
        } else {
            measuredWidth.toInt() + 10
        }

        // TODO: Use StaticLayout.Builder for API 23+ properly
        cachedLayout = android.text.StaticLayout.Builder.obtain(
            text, 0, text.length, textPaint, layoutWidth.coerceAtLeast(10)
        ).build()
    }

    override fun draw(canvas: Canvas) {
        ensureLayout()

        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rotation)
        canvas.scale(scaleX, scaleY)

        // Draw centered
        val w = getWidth()
        val h = getHeight()
        canvas.translate(-w / 2f, -h / 2f)

        cachedLayout?.draw(canvas)

        canvas.restore()
    }
}
