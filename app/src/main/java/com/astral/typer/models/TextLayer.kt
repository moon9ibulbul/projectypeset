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

    // Stroke
    var strokeColor: Int = Color.BLACK
    var strokeWidth: Float = 0f
    var doubleStrokeColor: Int = Color.WHITE
    var doubleStrokeWidth: Float = 0f

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
            // We need height for accurate gradient, but textPaint doesn't know height yet.
            // Approximation: Horizontal gradient for measurement is fine.
            // Real gradient logic is applied during draw or when layout is built?
            // StaticLayout uses the paint to measure. The shader doesn't affect measurement.
            // We can set a dummy shader or keep it simple here.
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

        // Motion Shadow (Drawn before everything else)
        if (isMotionShadow && motionShadowDistance > 0) {
            paint.style = Paint.Style.FILL
            paint.shader = null
            val originalAlpha = paint.alpha
            val iterations = 20
            val step = motionShadowDistance / iterations
            val angleRad = Math.toRadians(motionShadowAngle.toDouble())
            val cos = Math.cos(angleRad).toFloat()
            val sin = Math.sin(angleRad).toFloat()

            paint.color = shadowColor
            // Very low alpha per iteration
            paint.alpha = (30 * (opacity / 255f)).toInt().coerceAtLeast(1)

            if (shadowRadius > 0) {
                paint.setShadowLayer(shadowRadius, 0f, 0f, shadowColor)
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
            paint.color = doubleStrokeColor
            paint.shader = null
            paint.clearShadowLayer()
            layout.draw(canvas)
        }

        // 2. Stroke (Inner)
        if (strokeWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = strokeColor
            paint.shader = null
            paint.clearShadowLayer()
            layout.draw(canvas)
        }

        // 3. Fill
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.strokeWidth = 0f

        if (isGradient) {
            val w = layout.width.toFloat()
            val h = layout.height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val angleRad = Math.toRadians(gradientAngle.toDouble())

            // Calculate gradient vector based on box bounds
            // A simple approach is finding the length of the projection of the box onto the angle vector
            val diag = kotlin.math.sqrt(w*w + h*h)
            val r = diag / 2f

            // Calculate start and end points relative to 0,0 (top-left of text)
            // This is a simplification. For perfect gradients, we project corners.
            // But rotating around center is usually good enough.

            val cos = Math.cos(angleRad).toFloat()
            val sin = Math.sin(angleRad).toFloat()

            // Find extreme points
            // Corners: (0,0), (w,0), (0,h), (w,h)
            // Project v . (cos, sin)
            // p0 = 0
            // p1 = w * cos
            // p2 = h * sin
            // p3 = w * cos + h * sin

            val p0 = 0f
            val p1 = w * cos
            val p2 = h * sin
            val p3 = w * cos + h * sin

            val minP = minOf(p0, p1, p2, p3)
            val maxP = maxOf(p0, p1, p2, p3)

            // Reconstruct points on the line passing through center?
            // Standard LinearGradient definition: (x0, y0) to (x1, y1).
            // Lines perpendicular to this vector at these points have color0 and color1.

            // Center projection
            val cp = cx * cos + cy * sin
            // We want the gradient to cover the range [minP, maxP]
            // The center of that range isn't necessarily cp, but let's assume centered gradient.

            val halfLen = (maxP - minP) / 2f
            val x0 = cx - halfLen * cos
            val y0 = cy - halfLen * sin
            val x1 = cx + halfLen * cos
            val y1 = cy + halfLen * sin

            paint.shader = android.graphics.LinearGradient(
                x0, y0, x1, y1,
                gradientStartColor, gradientEndColor,
                android.graphics.Shader.TileMode.CLAMP
            )
        } else {
            paint.shader = null
        }

        if (!isMotionShadow && shadowRadius > 0) {
            paint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
        } else {
            paint.clearShadowLayer()
        }

        layout.draw(canvas)

        canvas.restore()
    }
}
