package com.astral.typer.models

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint

class TextLayer(
    initialText: String = "Double tap to edit",
    var color: Int = Color.BLACK
) : Layer() {

    var text: SpannableStringBuilder = SpannableStringBuilder(initialText)
    var fontSize: Float = 100f
    var typeface: Typeface = Typeface.DEFAULT
    var textAlign: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
    var isJustified: Boolean = false

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

        // Gradient (Note: Gradient might behave oddly with Spans that change color)
        if (isGradient) {
            val width = StaticLayout.getDesiredWidth(text, textPaint)
            textPaint.shader = android.graphics.LinearGradient(
                0f, 0f, width, 0f,
                gradientStartColor, gradientEndColor,
                android.graphics.Shader.TileMode.CLAMP
            )
        } else {
            textPaint.shader = null
        }

        val desiredWidth = StaticLayout.getDesiredWidth(text, textPaint)

        val layoutWidth = if (boxWidth != null && boxWidth!! > 0) {
            boxWidth!!.toInt()
        } else {
            desiredWidth.toInt() + 10
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val builder = StaticLayout.Builder.obtain(
                text, 0, text.length, textPaint, layoutWidth.coerceAtLeast(10)
            ).setAlignment(textAlign)

            if (isJustified && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                builder.setJustificationMode(LineBreaker.JUSTIFICATION_MODE_INTER_WORD)
            }

            cachedLayout = builder.build()
        } else {
            cachedLayout = StaticLayout(
                text, textPaint, layoutWidth.coerceAtLeast(10),
                textAlign, 1.0f, 0.0f, false
            )
        }
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
