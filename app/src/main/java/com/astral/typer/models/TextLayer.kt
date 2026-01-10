package com.astral.typer.models

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import java.util.Random
import kotlin.math.ceil

class TextLayer(
    initialText: String = "Double tap to edit",
    var color: Int = Color.BLACK
) : Layer() {

    var text: SpannableStringBuilder = SpannableStringBuilder(initialText)
    var fontSize: Float = 100f
    var typeface: Typeface = Typeface.DEFAULT
    var fontPath: String? = null // Identifier for the font (e.g., "Standard:Serif" or "/path/to/font.ttf")
    var textAlign: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
    var isJustified: Boolean = false

    // Spacing
    var letterSpacing: Float = 0f
    var lineSpacing: Float = 0f

    // Shadow
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
    var gradientAngle: Int = 0
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
    var perspectivePoints: FloatArray? = null

    // Warp
    var isWarp: Boolean = false
    var warpRows: Int = 2
    var warpCols: Int = 2
    var warpMesh: FloatArray? = null

    // Texture
    var textureBitmap: Bitmap? = null
    var textureOffsetX: Float = 0f
    var textureOffsetY: Float = 0f

    // Erase
    var eraseMask: Bitmap? = null

    // Erase Paths for Granular Undo (Runtime only, lost on save/load if not serialized, but acceptable for session undo)
    data class ErasePathData(val path: Path, val size: Float, val opacity: Int, val hardness: Float)
    val erasePaths = mutableListOf<ErasePathData>()

    // Effect
    var currentEffect: TextEffectType = TextEffectType.NONE

    // Random Seed for Glitch effect
    var effectSeed: Long = System.currentTimeMillis()

    // Caching for Pixelation
    @Transient
    private var cachedPixelBitmap: Bitmap? = null
    @Transient
    private var cachedPixelHash: Int = 0

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var cachedLayout: StaticLayout? = null

    var boxWidth: Float? = null

    init {
        name = "Text Layer"
    }

    override fun clone(): Layer {
        val newLayer = TextLayer(this.text.toString(), this.color)
        newLayer.text = SpannableStringBuilder(this.text)
        newLayer.fontSize = this.fontSize
        newLayer.typeface = this.typeface
        newLayer.fontPath = this.fontPath
        newLayer.textAlign = this.textAlign
        newLayer.isJustified = this.isJustified
        newLayer.letterSpacing = this.letterSpacing
        newLayer.lineSpacing = this.lineSpacing

        newLayer.opacity = this.opacity
        newLayer.blendMode = this.blendMode
        newLayer.isOpacityGradient = this.isOpacityGradient
        newLayer.opacityStart = this.opacityStart
        newLayer.opacityEnd = this.opacityEnd
        newLayer.opacityAngle = this.opacityAngle

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

        newLayer.isPerspective = this.isPerspective
        newLayer.perspectivePoints = this.perspectivePoints?.clone()

        newLayer.isWarp = this.isWarp
        newLayer.warpRows = this.warpRows
        newLayer.warpCols = this.warpCols
        newLayer.warpMesh = this.warpMesh?.clone()

        newLayer.textureBitmap = this.textureBitmap
        newLayer.textureOffsetX = this.textureOffsetX
        newLayer.textureOffsetY = this.textureOffsetY

        if (this.eraseMask != null) {
            newLayer.eraseMask = this.eraseMask!!.copy(this.eraseMask!!.config, true)
        }

        // Clone paths (Paths are mutable, need deep copy)
        for (p in this.erasePaths) {
            newLayer.erasePaths.add(ErasePathData(Path(p.path), p.size, p.opacity, p.hardness))
        }

        newLayer.currentEffect = this.currentEffect
        newLayer.effectSeed = this.effectSeed

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

    fun addErasePath(path: Path, size: Float, opacity: Int, hardness: Float) {
        erasePaths.add(ErasePathData(Path(path), size, opacity, hardness))
        // We also need to update the bitmap to reflect this change
        if (eraseMask == null) {
            // Need dimensions. Usually passed or existing.
            // If dimensions unknown, we can't draw. But usually eraseMask is initialized in onTouch
        } else {
             val c = Canvas(eraseMask!!)
             val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                 color = Color.BLACK
                 style = Paint.Style.STROKE
                 strokeWidth = size
                 this.alpha = opacity
                 strokeCap = Paint.Cap.ROUND
                 strokeJoin = Paint.Join.ROUND
                 if (hardness < 100) {
                     val radius = size / 2f
                     val blur = radius * (1f - (hardness / 100f))
                     if (blur > 0.5f) {
                        maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                     }
                 }
             }
             c.drawPath(path, p)
        }
    }

    fun undoLastErasePath(baseMask: Bitmap?) {
        if (erasePaths.isNotEmpty()) {
            erasePaths.removeAt(erasePaths.size - 1)
            rebuildEraseMask(baseMask)
        }
    }

    fun rebuildEraseMask(baseMask: Bitmap?) {
        // If we have eraseMask dimensions, reuse or recreate
        val w = eraseMask?.width ?: baseMask?.width ?: 1
        val h = eraseMask?.height ?: baseMask?.height ?: 1

        // New clean bitmap
        val newMask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(newMask)
        c.drawColor(Color.TRANSPARENT)

        // Draw base mask if exists (loaded from file)
        if (baseMask != null) {
             c.drawBitmap(baseMask, 0f, 0f, null)
        }

        // Draw all paths
        for (data in erasePaths) {
             val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                 color = Color.BLACK
                 style = Paint.Style.STROKE
                 strokeWidth = data.size
                 this.alpha = data.opacity
                 strokeCap = Paint.Cap.ROUND
                 strokeJoin = Paint.Join.ROUND
                 if (data.hardness < 100) {
                     val radius = data.size / 2f
                     val blur = radius * (1f - (data.hardness / 100f))
                     if (blur > 0.5f) {
                        maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                     }
                 }
             }
             c.drawPath(data.path, p)
        }
        eraseMask = newMask
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
        textPaint.alpha = 255
        textPaint.letterSpacing = letterSpacing

        if (shadowRadius > 0 && !isMotionShadow) {
            textPaint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
        } else {
            textPaint.clearShadowLayer()
        }

        // Texture Application
        if (textureBitmap != null) {
            val shader = android.graphics.BitmapShader(textureBitmap!!, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            val matrix = Matrix()
            matrix.postTranslate(textureOffsetX, textureOffsetY)
            shader.setLocalMatrix(matrix)
            textPaint.shader = shader
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
             .setLineSpacing(lineSpacing, 1.0f)

            if (isJustified && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
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

    private fun getGradientShader(w: Float, h: Float): Shader? {
        if (!isGradient) return null
        return createGradient(w, h, gradientAngle, gradientStartColor, gradientEndColor)
    }

    private fun getOpacityGradientShader(w: Float, h: Float): Shader {
        val startColor = (opacityStart shl 24) or 0x000000
        val endColor = (opacityEnd shl 24) or 0x000000
        return createGradient(w, h, opacityAngle, startColor, endColor)
    }

    private fun createGradient(w: Float, h: Float, angle: Int, startColor: Int, endColor: Int): Shader {
        val cx = w / 2f
        val cy = h / 2f
        val angleRad = Math.toRadians(angle.toDouble())
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

        return LinearGradient(x0, y0, x1, y1, startColor, endColor, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (!isVisible) return
        ensureLayout()
        val layout = cachedLayout ?: return

        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rotation)
        canvas.scale(scaleX, scaleY)

        val w = getWidth()
        val h = getHeight()
        val dx = -w / 2f
        val dy = -h / 2f

        val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        layerPaint.alpha = if (isOpacityGradient) 255 else opacity

        val mode = when(blendMode) {
             "OVERLAY" -> PorterDuff.Mode.OVERLAY
             "ADD" -> PorterDuff.Mode.ADD
             "MULTIPLY" -> PorterDuff.Mode.MULTIPLY
             "SCREEN" -> PorterDuff.Mode.SCREEN
             "DARKEN" -> PorterDuff.Mode.DARKEN
             "LIGHTEN" -> PorterDuff.Mode.LIGHTEN
             else -> PorterDuff.Mode.SRC_OVER
        }
        if (blendMode != "NORMAL") {
            layerPaint.xfermode = PorterDuffXfermode(mode)
        }

        val saveCount = canvas.saveLayer(null, layerPaint)

        if (isWarp && warpMesh != null) {
            drawWarped(canvas, layout, w, h, warpRows, warpCols, warpMesh!!)
        } else if (isPerspective && perspectivePoints != null) {
             drawPerspective(canvas, layout, w, h)
        } else {
             canvas.translate(dx, dy)
             drawContent(canvas, layout, w, h)
        }

        if (isOpacityGradient) {
            val maskPaint = Paint()
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            val size = Math.max(w, h) * 3
            maskPaint.shader = getOpacityGradientShader(w, h)
            canvas.drawRect(-size, -size, size, size, maskPaint)
        }

        canvas.restoreToCount(saveCount)
        canvas.restore()
    }

    private fun drawPerspective(canvas: Canvas, layout: StaticLayout, w: Float, h: Float) {
        val padding = 100
        val srcRect = RectF(-w/2f, -h/2f, w/2f, h/2f)
        val matrix = calculatePerspectiveMatrix(srcRect, perspectivePoints!!)

        val meshW = 20
        val meshH = 20
        val verts = FloatArray((meshW + 1) * (meshH + 1) * 2)

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

                val pts = floatArrayOf(px, py)
                matrix.mapPoints(pts)
                verts[index++] = pts[0]
                verts[index++] = pts[1]
            }
        }

        val bmpW = ceil(w + padding * 2).toInt()
        val bmpH = ceil(h + padding * 2).toInt()
        if (bmpW > 0 && bmpH > 0) {
            val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val c = Canvas(bitmap)
            c.translate(padding.toFloat(), padding.toFloat())
            drawContent(c, layout, w, h)
            canvas.drawBitmapMesh(bitmap, meshW, meshH, verts, 0, null, 0, null)
        }
    }

    private fun drawWarped(canvas: Canvas, layout: StaticLayout, w: Float, h: Float, rows: Int, cols: Int, mesh: FloatArray) {
        val bmpW = ceil(w).toInt()
        val bmpH = ceil(h).toInt()

        if (bmpW > 0 && bmpH > 0) {
            val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val c = Canvas(bitmap)
            drawContent(c, layout, w, h)
            canvas.drawBitmapMesh(bitmap, cols, rows, mesh, 0, null, 0, null)
        }
    }

    private fun drawContent(canvas: Canvas, layout: StaticLayout, w: Float, h: Float) {
        val paint = layout.paint
        val gradientShader = getGradientShader(w, h)

        if (isMotionShadow && motionShadowDistance > 0) {
            paint.style = Paint.Style.FILL
            paint.shader = if (isGradient && isGradientShadow) gradientShader else null

            val originalAlpha = paint.alpha
            val iterations = 30
            val effectiveDistance = motionShadowDistance
            val angleRad = Math.toRadians(motionShadowAngle.toDouble())
            val cos = Math.cos(angleRad).toFloat()
            val sin = Math.sin(angleRad).toFloat()
            val maxBlur = 4f

            paint.color = shadowColor
            paint.alpha = (30 * (255 / 255f)).toInt().coerceAtLeast(1)

            for (i in 1..iterations) {
                val t = i / iterations.toFloat()
                val d = (t * t + t)/2f * effectiveDistance
                val blur = t * maxBlur
                if (blur > 0.5f) {
                    paint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                } else {
                    paint.maskFilter = null
                }

                val dx = d * cos
                val dy = d * sin

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

        if (doubleStrokeWidth > 0f && strokeWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth + doubleStrokeWidth * 2
            paint.shader = null
            paint.color = doubleStrokeColor
            paint.clearShadowLayer()
            layout.draw(canvas)
        }

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

        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        if (isGradient && isGradientText) {
            paint.shader = gradientShader
            paint.color = Color.WHITE
        } else if (textureBitmap != null) {
             val shader = android.graphics.BitmapShader(textureBitmap!!, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
             val matrix = Matrix()
             matrix.postTranslate(textureOffsetX, textureOffsetY)
             shader.setLocalMatrix(matrix)
             paint.shader = shader
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

        if (currentEffect == TextEffectType.CHROMATIC_ABERRATION) {
             val originalXfermode = paint.xfermode
             val originalColor = paint.color
             val originalShader = paint.shader

             paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
             paint.shader = null

             // Red Layer
             paint.color = 0xFFFF0000.toInt()
             canvas.save()
             canvas.translate(-5f, 0f)
             layout.draw(canvas)
             canvas.restore()

             // Blue Layer
             paint.color = 0xFF0000FF.toInt()
             canvas.save()
             canvas.translate(5f, 0f)
             layout.draw(canvas)
             canvas.restore()

             // Green Layer
             paint.color = 0xFF00FF00.toInt()
             layout.draw(canvas)

             paint.xfermode = originalXfermode
             paint.color = originalColor
             paint.shader = originalShader
        } else if (currentEffect == TextEffectType.PIXELATION) {
             val scaleFactor = 0.1f
             val w = getWidth()
             val h = getHeight()
             val scaledW = (w * scaleFactor).toInt().coerceAtLeast(1)
             val scaledH = (h * scaleFactor).toInt().coerceAtLeast(1)

             val currentHash = text.hashCode() + w.toInt() + h.toInt() + color + fontSize.toInt()

             if (cachedPixelBitmap == null || cachedPixelBitmap!!.width != scaledW || cachedPixelBitmap!!.height != scaledH || cachedPixelHash != currentHash) {
                 cachedPixelBitmap?.recycle()

                 val tempBitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
                 val tempCanvas = Canvas(tempBitmap)
                 tempCanvas.scale(scaleFactor, scaleFactor)
                 layout.draw(tempCanvas)

                 cachedPixelBitmap = tempBitmap
                 cachedPixelHash = currentHash
             }

             if (cachedPixelBitmap != null && !cachedPixelBitmap!!.isRecycled) {
                 val pixelPaint = Paint()
                 pixelPaint.isFilterBitmap = false
                 val destRect = RectF(0f, 0f, w, h)
                 canvas.drawBitmap(cachedPixelBitmap!!, null, destRect, pixelPaint)
             }

        } else if (currentEffect == TextEffectType.GLITCH) {
            val random = Random(effectSeed)
            val w = getWidth()
            val h = getHeight()

            // 1. Draw Base Layer (Modified Algorithm: Aggressive Slicing)
            // Divide height into many small strips for more uniform distribution
            val numStrips = 50
            val stripHeight = h / numStrips.toFloat()

            data class Slice(val top: Float, val bottom: Float, val xOffset: Float, val rgbOffset: Float)
            val slices = mutableListOf<Slice>()

            for (i in 0 until numStrips) {
                val top = i * stripHeight
                val bottom = top + stripHeight

                // 50% chance to glitch a strip (more aggressive)
                if (random.nextFloat() < 0.5f) {
                     val offset = (random.nextFloat() - 0.5f) * 100f // -50 to 50px (More aggressive)

                     // RGB Split offset - randomized per slice
                     val rgbOff = if (random.nextBoolean()) (random.nextFloat() * 20f + 5f) else -(random.nextFloat() * 20f + 5f)

                     slices.add(Slice(top, bottom, offset, rgbOff))
                } else {
                     slices.add(Slice(top, bottom, 0f, 0f))
                }
            }

            // Draw Slices
            val savedColor = paint.color
            val savedXfermode = paint.xfermode

            for (slice in slices) {
                canvas.save()
                canvas.clipRect(0f, slice.top, w, slice.bottom)
                canvas.translate(slice.xOffset, 0f)

                // Draw Base
                layout.draw(canvas)

                // Draw RGB Split if it's a glitch slice
                if (kotlin.math.abs(slice.xOffset) > 0.1f) {
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                    paint.shader = null

                    // Red Channel
                    paint.color = 0xCCFF0000.toInt() // 80% Alpha
                    canvas.save()
                    canvas.translate(-slice.rgbOffset, 0f)
                    layout.draw(canvas)
                    canvas.restore()

                    // Cyan Channel
                    paint.color = 0xCC00FFFF.toInt()
                    canvas.save()
                    canvas.translate(slice.rgbOffset, 0f)
                    layout.draw(canvas)
                    canvas.restore()

                    // Restore paint for next loop
                    paint.color = savedColor
                    paint.xfermode = savedXfermode
                }

                canvas.restore()
            }

        } else if (currentEffect == TextEffectType.NEON) {
             val originalColor = paint.color
             val originalStyle = paint.style
             val originalMaskFilter = paint.maskFilter

             // Step 1: Draw Glow
             paint.style = Paint.Style.FILL
             paint.color = color
             paint.maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.NORMAL)

             layout.draw(canvas)

             // Step 2: Draw Core
             paint.maskFilter = null
             paint.color = Color.WHITE
             layout.draw(canvas)

             // Restore
             paint.color = originalColor
             paint.style = originalStyle
             paint.maskFilter = originalMaskFilter

        } else if (currentEffect == TextEffectType.LONG_SHADOW) {
             val originalColor = paint.color

             // Step 1: Loop for shadow
             paint.color = Color.DKGRAY
             for (i in 1..30) {
                 canvas.save()
                 canvas.translate(i.toFloat(), i.toFloat())
                 layout.draw(canvas)
                 canvas.restore()
             }

             // Step 2: Main Text
             paint.color = color
             layout.draw(canvas)

             // Restore
             paint.color = originalColor

        } else {
            layout.draw(canvas)
        }

        // Apply Erase Mask
        if (eraseMask != null) {
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            canvas.drawBitmap(eraseMask!!, 0f, 0f, maskPaint)
        }
    }

    private fun calculatePerspectiveMatrix(src: RectF, dst: FloatArray): Matrix {
        val matrix = Matrix()
        val srcPts = floatArrayOf(
            src.left, src.top,
            src.right, src.top,
            src.right, src.bottom,
            src.left, src.bottom
        )
        matrix.setPolyToPoly(srcPts, 0, dst, 0, 4)
        return matrix
    }
}
