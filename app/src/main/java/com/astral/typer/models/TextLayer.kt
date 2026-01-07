package com.astral.typer.models

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.BlurMaskFilter
import android.graphics.RectF
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

    // Spacing
    var letterSpacing: Float = 0f
    var lineSpacing: Float = 0f // Additive (extra)

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
    var motionBlurStrength: Float = 0f // New: Blur for motion shadow

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

    // Perspective
    var isPerspective: Boolean = false
    var perspectivePoints: FloatArray? = null // [x0, y0, x1, y1, x2, y2, x3, y3] relative to center

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var cachedLayout: StaticLayout? = null

    // Custom width for wrapping. If null or <=0, it wraps at maxWidth or fits content
    var boxWidth: Float? = null

    init {
        name = "Text Layer"
    }

    override fun clone(): Layer {
        val newLayer = TextLayer(this.text.toString(), this.color)
        newLayer.text = SpannableStringBuilder(this.text)
        newLayer.fontSize = this.fontSize
        newLayer.typeface = this.typeface
        newLayer.textAlign = this.textAlign
        newLayer.isJustified = this.isJustified
        newLayer.letterSpacing = this.letterSpacing
        newLayer.lineSpacing = this.lineSpacing
        newLayer.opacity = this.opacity
        newLayer.shadowColor = this.shadowColor
        newLayer.shadowRadius = this.shadowRadius
        newLayer.shadowDx = this.shadowDx
        newLayer.shadowDy = this.shadowDy
        newLayer.isMotionShadow = this.isMotionShadow
        newLayer.motionShadowAngle = this.motionShadowAngle
        newLayer.motionShadowDistance = this.motionShadowDistance
        newLayer.motionBlurStrength = this.motionBlurStrength
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

        newLayer.isPerspective = this.isPerspective
        newLayer.perspectivePoints = this.perspectivePoints?.clone()

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
        textPaint.letterSpacing = letterSpacing

        if (shadowRadius > 0 && !isMotionShadow) {
            textPaint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
        } else {
            textPaint.clearShadowLayer()
        }

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
             .setLineSpacing(lineSpacing, 1.0f)

            if (isJustified && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // builder.setJustificationMode(LineBreaker.JUSTIFICATION_MODE_INTER_WORD) // Requires API 26 (LineBreaker) or just Int
                // Using hardcoded int 1 for JUSTIFICATION_MODE_INTER_WORD to avoid import issues if not available in classpath
                builder.setJustificationMode(1)
            }

            cachedLayout = builder.build()
        } else {
            cachedLayout = StaticLayout(
                text, textPaint, layoutWidth.coerceAtLeast(10),
                textAlign, 1.0f, lineSpacing, false
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

        canvas.save()
        canvas.translate(x, y)
        // If Perspective is active, we might disable standard Rotation/Scale or apply them before perspective?
        // Usually perspective replaces the affine transform, but here the user toggles it.
        // Let's keep the base transform, and apply perspective on top (or as the final step).
        canvas.rotate(rotation)
        canvas.scale(scaleX, scaleY)

        val w = getWidth()
        val h = getHeight()

        // Center alignment for standard drawing
        val dx = -w / 2f
        val dy = -h / 2f

        if (isPerspective && perspectivePoints != null) {
            // Draw to a Bitmap first
            // We need to capture everything (Shadow, Stroke, Text) into a bitmap
            // Then distort it.
            // Problem: Motion Shadow is large.
            // To simplify, let's render the standard layer content into a bitmap.
            // But we need to define the bounds.

            // For now, let's handle Perspective by drawing the layout into a bitmap
            // and using drawBitmapMesh.

            // Calculate necessary bitmap size (including strokes/shadows)
            val padding = 100 // Extra padding
            val bmpW = w.toInt() + padding * 2
            val bmpH = h.toInt() + padding * 2

            if (bmpW > 0 && bmpH > 0) {
                val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                val bmpCanvas = Canvas(bitmap)

                // Draw content centered in bitmap
                bmpCanvas.translate(padding.toFloat(), padding.toFloat())

                // Draw the actual content (recursive call logic essentially)
                drawContent(bmpCanvas, layout, w, h, false) // false = don't apply gradient shader yet?
                // Wait, if we draw to bitmap, we should apply gradient on the bitmap content.
                // Yes.

                // Now warp this bitmap
                // The perspectivePoints are relative to Center (0,0) of the layer.
                // The bitmap content (0,0) corresponds to (-w/2 - padding, -h/2 - padding) in layer space?
                // No, inside drawContent we translated.

                // Source Verts: Rectangle of the bitmap content
                // Left: -w/2 - padding ?? No.
                // In the bitmap, the TopLeft is (0,0).
                // The Content TopLeft (Text start) is at (padding, padding).
                // The Content BottomRight is at (padding+w, padding+h).

                // The perspectivePoints correspond to the corners of the content box (-w/2, -h/2) to (w/2, h/2).
                // We need to map the Bitmap texture coordinates to the Screen coordinates.
                // But `drawBitmapMesh` maps the *entire* bitmap to a mesh.

                // Let's define the mesh points.
                // We have 4 corner points for the *Content Box*.
                // We need to interpolate where the *Bitmap Corners* (including padding) would land.
                // Or simpler: Just render the content box without padding? (Risk of clipping strokes).
                // Let's use padding.

                // Perspective Points are: TL, TR, BR, BL (assuming standard order)
                // P0(x,y), P1(x,y), P2(x,y), P3(x,y)

                // If we assume the transformation is a Perspective Projection, we can calculate the matrix
                // that maps the Source Rect (Content) to Destination Quad (Perspective Points).
                // Then apply that matrix to the Source Rect (Bitmap Bounds) to get Destination Quad (Bitmap).
                // BUT `drawBitmapMesh` just takes points.

                // Simpler approach for this task:
                // Only warp the Content Box. Padding might be clipped if we don't extend the mesh.
                // But the user manipulates the corners of the text box.
                // If we warp just the text box, strokes outside might look weird?
                // Actually, `drawBitmapMesh` is linear interpolation between points.
                // Perspective is non-linear. `drawBitmapMesh` with 1x1 grid is affine (skew).
                // We need a grid (e.g. 20x20) and calculate the perspective mapping for each point.

                // Given the complexity constraints and "no external libs",
                // I will use `drawBitmapMesh` with a finer grid and manually calculate the perspective projection.

                val pts = perspectivePoints!!
                // TL, TR, BR, BL order?
                // Usually: 0:TL, 1:TR, 2:BR, 3:BL

                val srcRect = RectF(-w/2f, -h/2f, w/2f, h/2f)
                val dstQuad = pts // [x0,y0, x1,y1, x2,y2, x3,y3]

                // Calculate Homography Matrix (Perspective Matrix) mapping srcRect to dstQuad
                val matrix = calculatePerspectiveMatrix(srcRect, dstQuad)

                // Create Mesh
                val meshW = 20
                val meshH = 20
                val verts = FloatArray((meshW + 1) * (meshH + 1) * 2)

                // Bitmap Bounds (0,0 to bmpW, bmpH)
                // These correspond to Layer Space: (-padding, -padding) to (w+padding, h+padding) relative to Content TopLeft?
                // In Layer Space (where srcRect is centered at 0,0):
                // Bitmap TopLeft is (-w/2 - padding, -h/2 - padding)
                // Bitmap BottomRight is (w/2 + padding, h/2 + padding)

                val bLeft = -w/2f - padding
                val bTop = -h/2f - padding
                val bRight = w/2f + padding
                val bBottom = h/2f + padding

                var index = 0
                for (y in 0..meshH) {
                    val fy = y.toFloat() / meshH
                    val py = bTop + (bBottom - bTop) * fy

                    for (x in 0..meshW) {
                        val fx = x.toFloat() / meshW
                        val px = bLeft + (bRight - bLeft) * fx

                        // Map (px, py) using matrix
                        val mapped = mapPoint(matrix, px, py)

                        verts[index++] = mapped[0]
                        verts[index++] = mapped[1]
                    }
                }

                canvas.drawBitmapMesh(bitmap, meshW, meshH, verts, 0, null, 0, null)
                // bitmap.recycle() // Ideally recycle, but in draw() loop be careful creating too many.
                // For a prototype, GC will handle it, but it might stutter.
            }

        } else {
            canvas.translate(dx, dy)
            drawContent(canvas, layout, w, h, true)
        }

        canvas.restore()
    }

    private fun drawContent(canvas: Canvas, layout: StaticLayout, w: Float, h: Float, usePositioning: Boolean) {
        val paint = layout.paint

        // Prepare Gradient Shader
        val gradientShader = if (isGradient) getGradientShader(w, h) else null

        // Motion Shadow
        if (isMotionShadow && motionShadowDistance > 0) {
            paint.style = Paint.Style.FILL
            paint.shader = if (isGradient && isGradientShadow) gradientShader else null

            val originalAlpha = paint.alpha
            val iterations = 20
            val step = motionShadowDistance / iterations
            val angleRad = Math.toRadians(motionShadowAngle.toDouble())
            val cos = Math.cos(angleRad).toFloat()
            val sin = Math.sin(angleRad).toFloat()

            paint.color = shadowColor
            paint.alpha = (30 * (opacity / 255f)).toInt().coerceAtLeast(1)

            // Blur for Motion Shadow
            if (motionBlurStrength > 0) {
                paint.maskFilter = BlurMaskFilter(motionBlurStrength.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
            } else {
                paint.maskFilter = null
            }

            // Standard Shadow for Motion? Usually disabled or replaced by the motion itself.
            paint.clearShadowLayer()

            for (i in 1..iterations) {
                val dist = step * i
                val dx = dist * cos
                val dy = dist * sin

                canvas.save()
                canvas.translate(dx, dy)
                layout.draw(canvas)
                canvas.restore()

                canvas.save()
                canvas.translate(-dx, -dy)
                layout.draw(canvas)
                canvas.restore()
            }
            paint.maskFilter = null
            paint.alpha = originalAlpha
            paint.color = color
        }

        // 1. Double Stroke
        if (doubleStrokeWidth > 0f && strokeWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth + doubleStrokeWidth * 2
            paint.shader = null
            paint.color = doubleStrokeColor
            paint.clearShadowLayer()
            layout.draw(canvas)
        }

        // 2. Stroke
        if (strokeWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth

            if (isGradient && isGradientStroke) {
                paint.shader = gradientShader
                paint.color = Color.WHITE
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
            paint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
        } else {
            paint.clearShadowLayer()
        }

        layout.draw(canvas)
    }

    // Matrix Helper for Perspective
    private fun calculatePerspectiveMatrix(src: RectF, dst: FloatArray): android.graphics.Matrix {
        val matrix = android.graphics.Matrix()
        // Map Rect to Poly (4 points)
        // src points: TL, TR, BR, BL
        val srcPts = floatArrayOf(
            src.left, src.top,
            src.right, src.top,
            src.right, src.bottom,
            src.left, src.bottom
        )

        matrix.setPolyToPoly(srcPts, 0, dst, 0, 4)
        return matrix
    }

    private fun mapPoint(matrix: android.graphics.Matrix, x: Float, y: Float): FloatArray {
        val pts = floatArrayOf(x, y)
        matrix.mapPoints(pts)
        return pts
    }
}
