package com.astral.typer.models

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader

class BrushLayer(val canvasWidth: Int, val canvasHeight: Int) : Layer(), StylableLayer {

    init {
        name = "Brush Layer"
        x = canvasWidth / 2f
        y = canvasHeight / 2f
    }

    var bitmap: Bitmap = Bitmap.createBitmap(canvasWidth.coerceAtLeast(1), canvasHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    private var drawCanvas: Canvas = Canvas(bitmap)

    // Brush State Properties
    var brushName: String = "pencil"
    var brushColor: Int = Color.BLACK
    var brushSize: Float = 20f
    var brushHardness: Float = 0.5f
    var brushOpacity: Int = 255

    // Extra .myb parameters
    var brushDabsPerActualRadius: Float = 4.0f
    var brushDabsPerBasicRadius: Float = 0.0f
    var brushDabsPerSecond: Float = 0.0f
    var brushOffsetByRandom: Float = 0.0f
    var brushRadiusByRandom: Float = 0.0f
    var brushEllipticalDabRatio: Float = 1.0f
    var brushEllipticalDabAngle: Float = 90.0f
    var brushSmudge: Float = 0.0f
    var brushSmudgeLength: Float = 0.5f
    var brushSlowTracking: Float = 1.0f

    // Dynamic Smudge color state
    private var currentSmudgeColor: Int = Color.BLACK

    // Standard touch stroke points
    private var lastX: Float = 0f
    private var lastY: Float = 0f

    // Implement StylableLayer overrides
    override var color: Int
        get() = brushColor
        set(value) { brushColor = value }

    // Dummy styling properties to satisfy StylableLayer interface
    override var shadowColor: Int = Color.TRANSPARENT
    override var shadowRadius: Float = 0f
    override var shadowDx: Float = 0f
    override var shadowDy: Float = 0f
    override var isMotionShadow: Boolean = false
    override var isMotionShadowIncludeStroke: Boolean = false
    override var motionShadowAngle: Int = 0
    override var motionShadowDistance: Float = 0f
    override var motionShadowThickness: Float = 4f

    override var isGradient: Boolean = false
    override var gradientStartColor: Int = Color.TRANSPARENT
    override var gradientEndColor: Int = Color.TRANSPARENT
    override var gradientAngle: Int = 0
    override var isGradientText: Boolean = false
    override var isGradientStroke: Boolean = false
    override var isGradientShadow: Boolean = false
    override var isGlobalGradient: Boolean = false
    override var globalP1: PointF = PointF()
    override var globalP2: PointF = PointF()

    override var strokeColor: Int = Color.TRANSPARENT
    override var strokeWidth: Float = 0f
    override var doubleStrokeColor: Int = Color.TRANSPARENT
    override var doubleStrokeWidth: Float = 0f

    override var isPerspective: Boolean = false
    override var perspectivePoints: FloatArray? = null
    override var isWarp: Boolean = false
    override var warpRows: Int = 2
    override var warpCols: Int = 2
    override var warpMesh: FloatArray? = null
    override var selectedWarpIndex: Int = -1
    override fun updateDenseWarpMesh() {}

    override var textureBitmap: Bitmap? = null
    override var textureOffsetX: Float = 0f
    override var textureOffsetY: Float = 0f
    override var patternName: String? = null
    override var patternColor: Int = Color.BLACK
    override var patternAlpha: Int = 255
    override var patternScale: Float = 1f
    override var patternRotation: Float = 0f

    override var currentEffect: TextEffectType = TextEffectType.NONE
    override var secondaryEffect: TextEffectType = TextEffectType.NONE
    override var effectSeed: Long = 0L
    override var blurRadius: Float = 0f
    override var longShadowLength: Float = 0f
    override var longShadowColor: Int = Color.TRANSPARENT
    override var longShadowAngle: Float = 0f
    override var motionBlurLength: Float = 0f
    override var motionBlurAngle: Int = 0
    override var halftoneDotSize: Float = 0f
    override var halftoneDotColor: Int = Color.TRANSPARENT
    override var halftoneThreshold: Float = 0f
    override var neonRadius: Float = 0f
    override var neonColor: Int = Color.TRANSPARENT
    override var glitchIntensity: Float = 0f
    override var pixelBlockSize: Float = 0f
    override var chromaticShift: Float = 0f
    override var chromaticColors: IntArray = intArrayOf()
    override var fieryColor: Int = Color.TRANSPARENT
    override var fieryIntensity: Float = 0f
    override var wavyIntensity: Float = 0f
    override var wavyFrequency: Float = 0f
    override var particleSize: Float = 0f
    override var particleSpread: Float = 0f
    override var particleDissolveAngle: Float = 0f
    override var multiGradientColors: IntArray = intArrayOf()
    override var multiGradientAngle: Float = 0f
    override var radialBlurInnerRadius: Float = 0f
    override var radialBlurMotionStrength: Float = 0f
    override var decayIntensity: Float = 0f
    override var decayFadingLevel: Float = 0f

    // Erase support
    override var eraseMask: Bitmap? = null
    override val erasePaths: MutableList<ErasePathData> = mutableListOf()
    override var activeErasePath: Path? = null
    override var activeEraseSize: Float = 20f
    override var activeEraseOpacity: Int = 255
    override var activeEraseHardness: Float = 0.5f
    override var eraseDragRevision: Int = 0

    override fun addErasePath(path: Path, size: Float, opacity: Int, hardness: Float) {
        if (eraseMask == null) {
            eraseMask = Bitmap.createBitmap(canvasWidth.coerceAtLeast(1), canvasHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }
        erasePaths.add(ErasePathData(path, size, opacity, hardness))
        rebuildEraseMask(eraseMask)
    }

    override fun undoLastErasePath(baseMask: Bitmap?) {
        if (erasePaths.isNotEmpty()) {
            erasePaths.removeAt(erasePaths.size - 1)
            rebuildEraseMask(baseMask)
        }
    }

    override fun rebuildEraseMask(baseMask: Bitmap?) {
        val mask = eraseMask ?: return
        mask.eraseColor(Color.TRANSPARENT)
        val c = Canvas(mask)
        if (baseMask != null) {
            c.drawBitmap(baseMask, 0f, 0f, null)
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (ep in erasePaths) {
            p.strokeWidth = ep.size
            p.alpha = ep.opacity
            // Use blur for hardness
            if (ep.hardness < 1f) {
                val radius = ep.size * (1f - ep.hardness) * 0.5f
                if (radius > 0.1f) {
                    p.maskFilter = android.graphics.BlurMaskFilter(radius, android.graphics.BlurMaskFilter.Blur.NORMAL)
                } else {
                    p.maskFilter = null
                }
            } else {
                p.maskFilter = null
            }
            c.drawPath(ep.path, p)
        }
    }

    override fun calculatePadding(): Float = 0f

    // Layer Overrides
    override fun getWidth(): Float = canvasWidth.toFloat()
    override fun getHeight(): Float = canvasHeight.toFloat()

    override fun contains(px: Float, py: Float): Boolean {
        // Standard tap-selection is bypassed by returning false
        return false
    }

    override fun clone(): Layer {
        val copy = BrushLayer(canvasWidth, canvasHeight).apply {
            x = this@BrushLayer.x
            y = this@BrushLayer.y
            rotation = this@BrushLayer.rotation
            scaleX = this@BrushLayer.scaleX
            scaleY = this@BrushLayer.scaleY
            isVisible = this@BrushLayer.isVisible
            isLocked = this@BrushLayer.isLocked
            name = this@BrushLayer.name
            opacity = this@BrushLayer.opacity
            blendMode = this@BrushLayer.blendMode

            brushName = this@BrushLayer.brushName
            brushColor = this@BrushLayer.brushColor
            brushSize = this@BrushLayer.brushSize
            brushHardness = this@BrushLayer.brushHardness
            brushOpacity = this@BrushLayer.brushOpacity

            brushDabsPerActualRadius = this@BrushLayer.brushDabsPerActualRadius
            brushDabsPerBasicRadius = this@BrushLayer.brushDabsPerBasicRadius
            brushDabsPerSecond = this@BrushLayer.brushDabsPerSecond
            brushOffsetByRandom = this@BrushLayer.brushOffsetByRandom
            brushRadiusByRandom = this@BrushLayer.brushRadiusByRandom
            brushEllipticalDabRatio = this@BrushLayer.brushEllipticalDabRatio
            brushEllipticalDabAngle = this@BrushLayer.brushEllipticalDabAngle
            brushSmudge = this@BrushLayer.brushSmudge
            brushSmudgeLength = this@BrushLayer.brushSmudgeLength
            brushSlowTracking = this@BrushLayer.brushSlowTracking
        }
        // Deep copy drawing bitmap
        copy.bitmap = this.bitmap.copy(this.bitmap.config, true)
        copy.drawCanvas = Canvas(copy.bitmap)

        // Deep copy erase mask
        if (this.eraseMask != null) {
            copy.eraseMask = this.eraseMask!!.copy(this.eraseMask!!.config, true)
        }
        // Copy erase paths
        copy.erasePaths.addAll(this.erasePaths)
        return copy
    }

    override fun draw(canvas: Canvas) {
        if (!isVisible) return

        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rotation)
        canvas.scale(scaleX, scaleY)

        val w = getWidth()
        val h = getHeight()
        val dx = -w / 2f
        val dy = -h / 2f

        // Draw with blending and opacity
        val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = opacity
            xfermode = when (this@BrushLayer.blendMode) {
                "OVERLAY" -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                "ADD" -> PorterDuffXfermode(PorterDuff.Mode.ADD)
                "MULTIPLY" -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                "SCREEN" -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                "DARKEN" -> PorterDuffXfermode(PorterDuff.Mode.DARKEN)
                "LIGHTEN" -> PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
                else -> null
            }
        }

        // We draw the layer's content onto a temporary canvas if there is an erase mask to apply DST_OUT correctly
        if (eraseMask != null || activeErasePath != null) {
            // Draw to a temp layer to apply non-destructive erase masking
            val saveCount = canvas.saveLayer(dx, dy, dx + w, dy + h, null)
            canvas.drawBitmap(bitmap, dx, dy, layerPaint)

            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
            if (eraseMask != null) {
                canvas.drawBitmap(eraseMask!!, dx, dy, maskPaint)
            }
            if (activeErasePath != null) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    style = Paint.Style.STROKE
                    strokeWidth = activeEraseSize
                    alpha = activeEraseOpacity
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    if (activeEraseHardness < 1f) {
                        val radius = activeEraseSize * (1f - activeEraseHardness) * 0.5f
                        if (radius > 0.1f) {
                            maskFilter = android.graphics.BlurMaskFilter(radius, android.graphics.BlurMaskFilter.Blur.NORMAL)
                        }
                    }
                }
                canvas.drawPath(activeErasePath!!, p)
            }
            canvas.restoreToCount(saveCount)
        } else {
            canvas.drawBitmap(bitmap, dx, dy, layerPaint)
        }

        canvas.restore()
    }

    // Touch Stroke Processing
    private var strokeHasMoved = false

    fun startStroke(x: Float, y: Float) {
        lastX = x
        lastY = y
        strokeHasMoved = false
        currentSmudgeColor = brushColor
    }

    fun continueStroke(x: Float, y: Float) {
        // Apply slow tracking EMA stabilizer if > 1.0
        val factor = if (brushSlowTracking > 1f) (1f / brushSlowTracking).coerceIn(0.01f, 1f) else 1f
        val targetX = lastX + (x - lastX) * factor
        val targetY = lastY + (y - lastY) * factor

        val dx = targetX - lastX
        val dy = targetY - lastY
        val distance = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

        if (distance > 2f) {
            strokeHasMoved = true
        }

        // Calculate spacing based on brush size and dabs_per_actual_radius.
        val radius = brushSize / 2f
        val dabsCount = if (brushDabsPerActualRadius <= 0f) 4.0f else brushDabsPerActualRadius
        val spacing = (radius / dabsCount).coerceAtLeast(1f)

        if (distance > spacing) {
            val steps = (distance / spacing).toInt()
            for (i in 1..steps) {
                val interpolatedX = lastX + dx * (i.toFloat() / steps)
                val interpolatedY = lastY + dy * (i.toFloat() / steps)
                drawDab(interpolatedX, interpolatedY)
            }
            lastX = targetX
            lastY = targetY
        }
    }

    fun endStroke() {
        if (!strokeHasMoved) {
            drawDab(lastX, lastY)
        }
    }

    private fun drawDab(cx: Float, cy: Float) {
        val radius = brushSize / 2f
        if (radius <= 0.1f) return

        // 1. Handle offset_by_random
        var dabX = cx
        var dabY = cy
        if (brushOffsetByRandom > 0f) {
            val maxOffset = brushOffsetByRandom * radius
            val angle = Math.random() * 2.0 * Math.PI
            val dist = Math.random() * maxOffset
            dabX += (dist * Math.cos(angle)).toFloat()
            dabY += (dist * Math.sin(angle)).toFloat()
        }

        // 2. Handle radius_by_random
        var dabRadius = radius
        if (brushRadiusByRandom > 0f) {
            val randFactor = (Math.random() * 2.0 - 1.0).toFloat()
            dabRadius *= (1f + randFactor * brushRadiusByRandom).coerceAtLeast(0.1f)
        }

        // 3. Handle smudge and smudge decay
        var finalColor = brushColor
        if (brushSmudge > 0f && dabX >= 0 && dabX < canvasWidth && dabY >= 0 && dabY < canvasHeight) {
            val px = dabX.toInt()
            val py = dabY.toInt()
            val sampledColor = bitmap.getPixel(px, py)
            if (Color.alpha(sampledColor) > 0) {
                // Mix current smudge color with sampled canvas color
                val r = (Color.red(currentSmudgeColor) * (1f - brushSmudge) + Color.red(sampledColor) * brushSmudge).toInt().coerceIn(0, 255)
                val g = (Color.green(currentSmudgeColor) * (1f - brushSmudge) + Color.green(sampledColor) * brushSmudge).toInt().coerceIn(0, 255)
                val b = (Color.blue(currentSmudgeColor) * (1f - brushSmudge) + Color.blue(sampledColor) * brushSmudge).toInt().coerceIn(0, 255)
                val a = (Color.alpha(currentSmudgeColor) * (1f - brushSmudge) + Color.alpha(sampledColor) * brushSmudge).toInt().coerceIn(0, 255)
                val blended = Color.argb(a, r, g, b)

                finalColor = blended

                // Decay currentSmudgeColor back to brushColor based on smudge_length
                val decay = (1f - brushSmudgeLength).coerceIn(0f, 1f)
                val dr = (Color.red(blended) * (1f - decay) + Color.red(brushColor) * decay).toInt().coerceIn(0, 255)
                val dg = (Color.green(blended) * (1f - decay) + Color.green(brushColor) * decay).toInt().coerceIn(0, 255)
                val db = (Color.blue(blended) * (1f - decay) + Color.blue(brushColor) * decay).toInt().coerceIn(0, 255)
                val da = (Color.alpha(blended) * (1f - decay) + Color.alpha(brushColor) * decay).toInt().coerceIn(0, 255)
                currentSmudgeColor = Color.argb(da, dr, dg, db)
            } else {
                // Decay currentSmudgeColor back to brushColor when transparent is sampled
                val decay = (1f - brushSmudgeLength).coerceIn(0f, 1f)
                val dr = (Color.red(currentSmudgeColor) * (1f - decay) + Color.red(brushColor) * decay).toInt().coerceIn(0, 255)
                val dg = (Color.green(currentSmudgeColor) * (1f - decay) + Color.green(brushColor) * decay).toInt().coerceIn(0, 255)
                val db = (Color.blue(currentSmudgeColor) * (1f - decay) + Color.blue(brushColor) * decay).toInt().coerceIn(0, 255)
                val da = (Color.alpha(currentSmudgeColor) * (1f - decay) + Color.alpha(brushColor) * decay).toInt().coerceIn(0, 255)
                currentSmudgeColor = Color.argb(da, dr, dg, db)
                finalColor = currentSmudgeColor
            }
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            alpha = brushOpacity
        }

        // 4. Setup shader with RadialGradient centered at local origin (0, 0)
        if (brushHardness < 1f) {
            val colors = intArrayOf(finalColor, finalColor, Color.TRANSPARENT)
            val stops = floatArrayOf(0f, brushHardness.coerceIn(0f, 0.99f), 1f)
            paint.shader = RadialGradient(0f, 0f, dabRadius, colors, stops, Shader.TileMode.CLAMP)
        } else {
            paint.color = finalColor
        }

        // Support soft eraser when eraser is selected
        val isEraser = brushName.contains("eraser", ignoreCase = true)
        if (isEraser) {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            if (brushHardness < 1f) {
                val colors = intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT)
                val stops = floatArrayOf(0f, brushHardness.coerceIn(0f, 0.99f), 1f)
                paint.shader = RadialGradient(0f, 0f, dabRadius, colors, stops, Shader.TileMode.CLAMP)
            } else {
                paint.color = Color.BLACK
            }
        }

        // 5. Draw with elliptical transformations on canvas
        drawCanvas.save()
        drawCanvas.translate(dabX, dabY)
        drawCanvas.rotate(brushEllipticalDabAngle)
        val scaleY = if (brushEllipticalDabRatio <= 0.01f) 1f else (1f / brushEllipticalDabRatio)
        drawCanvas.scale(1f, scaleY)
        drawCanvas.drawCircle(0f, 0f, dabRadius, paint)
        drawCanvas.restore()
    }
}
