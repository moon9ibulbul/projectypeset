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

    // Motion Shadow
    var isMotionShadow: Boolean = false
    var motionShadowAngle: Int = 0
    var motionShadowDistance: Float = 0f

    // Gradient
    var isGradient: Boolean = false
    var gradientStartColor: Int = Color.RED
    var gradientEndColor: Int = Color.BLUE
    var gradientAngle: Int = 0 // Degrees 0-360

    // Gradient Toggles
    var isGradientText: Boolean = true
    var isGradientStroke: Boolean = false
    var isGradientShadow: Boolean = false

    // Stroke
    var strokeColor: Int = Color.BLACK
    var strokeWidth: Float = 0f
    var doubleStrokeColor: Int = Color.WHITE
    var doubleStrokeWidth: Float = 0f

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var cachedLayout: StaticLayout? = null

    // Custom width for wrapping. If null or <=0, it wraps at maxWidth or fits content
    var boxWidth: Float? = null

    init {
        name = "Text Layer"
    }

    override fun clone(): Layer {
        val newLayer = TextLayer(this.text.toString(), this.color)
        newLayer.text = SpannableStringBuilder(this.text) // Deep copy text spans?
        // Note: SpannableStringBuilder(other) copies spans, but might need careful verification for custom spans.
        newLayer.fontSize = this.fontSize
        newLayer.typeface = this.typeface
        newLayer.textAlign = this.textAlign
        newLayer.isJustified = this.isJustified
        newLayer.opacity = this.opacity
        newLayer.shadowColor = this.shadowColor
        newLayer.shadowRadius = this.shadowRadius
        newLayer.shadowDx = this.shadowDx
        newLayer.shadowDy = this.shadowDy
        newLayer.isMotionShadow = this.isMotionShadow
        newLayer.motionShadowAngle = this.motionShadowAngle
        newLayer.motionShadowDistance = this.motionShadowDistance
        newLayer.isGradient = this.isGradient
        newLayer.gradientStartColor = this.gradientStartColor
        newLayer.gradientEndColor = this.gradientEndColor
        newLayer.gradientAngle = this.gradientAngle
        newLayer.isGradientText = this.isGradientText
        newLayer.isGradientStroke = this.isGradientStroke
        newLayer.isGradientShadow = this.isGradientShadow
        newLayer.strokeColor = this.strokeColor
        newLayer.strokeWidth = this.strokeWidth
        newLayer.doubleStrokeColor = this.doubleStrokeColor
        newLayer.doubleStrokeWidth = this.doubleStrokeWidth
        newLayer.boxWidth = this.boxWidth

        newLayer.x = this.x
        newLayer.y = this.y
        newLayer.rotation = this.rotation
        newLayer.scaleX = this.scaleX
        newLayer.scaleY = this.scaleY
        newLayer.isVisible = this.isVisible
        newLayer.isLocked = this.isLocked
        newLayer.name = this.name

        return newLayer
    }

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

        // Gradient logic for text measurement is not critical, but we set it later for drawing.
        // If we set shader here, it might affect measurement if measurement depends on it (unlikely).
        textPaint.shader = null

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

    private fun getGradientShader(w: Float, h: Float): android.graphics.Shader? {
        if (!isGradient) return null

        val cx = w / 2f
        val cy = h / 2f
        val angleRad = Math.toRadians(gradientAngle.toDouble())
        val cos = Math.cos(angleRad).toFloat()
        val sin = Math.sin(angleRad).toFloat()

        // Calculate gradient extents projected onto the angle vector
        // Corners relative to center
        val corners = listOf(
            Pair(-cx, -cy), Pair(cx, -cy), Pair(-cx, cy), Pair(cx, cy)
        )

        var minP = Float.MAX_VALUE
        var maxP = -Float.MAX_VALUE

        for ((px, py) in corners) {
            val p = px * cos + py * sin
            if (p < minP) minP = p
            if (p > maxP) maxP = p
        }

        val halfLen = (maxP - minP) / 2f
        val x0 = cx - halfLen * cos
        val y0 = cy - halfLen * sin
        val x1 = cx + halfLen * cos
        val y1 = cy + halfLen * sin

        return android.graphics.LinearGradient(
            x0, y0, x1, y1,
            gradientStartColor, gradientEndColor,
            android.graphics.Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        if (!isVisible) return

        ensureLayout()
        val layout = cachedLayout ?: return
        val paint = layout.paint

        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rotation)
        canvas.scale(scaleX, scaleY)

        // Draw centered
        val w = getWidth()
        val h = getHeight()
        canvas.translate(-w / 2f, -h / 2f)

        // Prepare Gradient Shader
        val gradientShader = if (isGradient) getGradientShader(w, h) else null

        // Motion Shadow (Drawn before everything else)
        if (isMotionShadow && motionShadowDistance > 0) {
            paint.style = Paint.Style.FILL
            paint.shader = if (isGradient && isGradientShadow) gradientShader else null

            val originalAlpha = paint.alpha
            val iterations = 20
            val step = motionShadowDistance / iterations
            val angleRad = Math.toRadians(motionShadowAngle.toDouble())
            val cos = Math.cos(angleRad).toFloat()
            val sin = Math.sin(angleRad).toFloat()

            if (!isGradient || !isGradientShadow) {
                 paint.color = shadowColor
            }
            // Very low alpha per iteration
            paint.alpha = (30 * (opacity / 255f)).toInt().coerceAtLeast(1)

            if (shadowRadius > 0) {
                paint.setShadowLayer(shadowRadius, 0f, 0f, shadowColor) // Shadow color used for blur even if gradient?
                // If gradient is active on shadow, the blur color usually takes the paint color,
                // but setShadowLayer forces a color.
                // We'll keep using shadowColor for the blur effect itself.
            } else {
                paint.clearShadowLayer()
            }

            for (i in 1..iterations) {
                val dist = step * i
                val dx = dist * cos
                val dy = dist * sin

                // Draw in positive direction
                canvas.save()
                canvas.translate(dx, dy)
                layout.draw(canvas)
                canvas.restore()

                // Draw in negative direction
                canvas.save()
                canvas.translate(-dx, -dy)
                layout.draw(canvas)
                canvas.restore()
            }

            // Restore paint
            paint.alpha = originalAlpha
            paint.color = color
            paint.clearShadowLayer()
        }

        // 1. Double Stroke (Outer)
        if (doubleStrokeWidth > 0f && strokeWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth + doubleStrokeWidth * 2
            paint.shader = null // Typically stroke doesn't use the main gradient unless specified?
            // The prompt says "toggle stroke", presumably applying the same gradient.
            // But Double Stroke is usually a solid backing. I'll leave double stroke solid for now unless asked.
            // Or maybe "Stroke" applies to both? Let's assume just the main stroke.

            paint.color = doubleStrokeColor
            paint.clearShadowLayer()
            layout.draw(canvas)
        }

        // 2. Stroke (Inner)
        if (strokeWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth

            if (isGradient && isGradientStroke) {
                paint.shader = gradientShader
                paint.color = Color.WHITE // Needed for shader to show
            } else {
                paint.shader = null
                paint.color = strokeColor
            }

            paint.clearShadowLayer()
            layout.draw(canvas)
        }

        // 3. Fill
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f

        if (isGradient && isGradientText) {
            paint.shader = gradientShader
            paint.color = Color.WHITE
        } else {
            paint.shader = null
            paint.color = color
        }

        if (!isMotionShadow && shadowRadius > 0) {
             // For drop shadow, if gradient is active on Shadow, we can't easily gradient the blur.
             // setShadowLayer takes a single color.
             // We can draw the text with gradient (if text toggle is on) and a shadow.
             // If "Shadow Toggle" is on for gradient, it implies the *Text Itself* drawn as shadow has gradient?
             // Or the shadow color is a gradient? (Impossible with standard Paint shadow).
             // We will assume Drop Shadow ignores gradient toggle or just uses shadowColor.
            paint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
        } else {
            paint.clearShadowLayer()
        }

        layout.draw(canvas)

        canvas.restore()
    }
}
