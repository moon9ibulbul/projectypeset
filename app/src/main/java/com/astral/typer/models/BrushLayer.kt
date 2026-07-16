package com.astral.typer.models

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.ceil

class BrushLayer(
    var bitmap: Bitmap
) : Layer(), StylableLayer {

    // Brush Settings
    var brushName: String = "classic/brush"
    var brushColor: Int = Color.BLACK
    var brushSize: Float = 20f
    var brushHardness: Float = 0.8f
    var brushOpacity: Int = 255

    // StylableLayer interface overrides (mostly stubs or defaults to satisfy interface)
    override var color: Int
        get() = brushColor
        set(value) { brushColor = value }

    // Shadow
    override var shadowColor: Int = Color.GRAY
    override var shadowRadius: Float = 0f
    override var shadowDx: Float = 0f
    override var shadowDy: Float = 0f
    override var isMotionShadow: Boolean = false
    override var isMotionShadowIncludeStroke: Boolean = false
    override var motionShadowAngle: Int = 0
    override var motionShadowDistance: Float = 0f
    override var motionShadowThickness: Float = 4f

    // Gradient
    override var isGradient: Boolean = false
    override var gradientStartColor: Int = Color.RED
    override var gradientEndColor: Int = Color.BLUE
    override var gradientAngle: Int = 0
    override var isGradientText: Boolean = true
    override var isGradientStroke: Boolean = false
    override var isGradientShadow: Boolean = false
    override var isGlobalGradient: Boolean = false
    override var globalP1: PointF = PointF()
    override var globalP2: PointF = PointF()

    // Stroke
    override var strokeColor: Int = Color.BLACK
    override var strokeWidth: Float = 0f
    override var doubleStrokeColor: Int = Color.WHITE
    override var doubleStrokeWidth: Float = 0f

    // Perspective & Warp
    override var isPerspective: Boolean = false
    override var perspectivePoints: FloatArray? = null
    override var isWarp: Boolean = false
    override var warpRows: Int = 2
    override var warpCols: Int = 2
    override var warpMesh: FloatArray? = null
    override var selectedWarpIndex: Int
        get() = -1
        set(value) {}

    // Texture & Pattern
    override var textureBitmap: Bitmap? = null
    override var textureOffsetX: Float = 0f
    override var textureOffsetY: Float = 0f
    override var patternName: String? = null
    override var patternColor: Int = Color.BLACK
    override var patternAlpha: Int = 255
    override var patternScale: Float = 1.0f
    override var patternRotation: Float = 0f

    // Effects
    override var currentEffect: TextEffectType = TextEffectType.NONE
    override var secondaryEffect: TextEffectType = TextEffectType.NONE
    override var effectSeed: Long = System.currentTimeMillis()
    override var blurRadius: Float = 0f
    override var longShadowLength: Float = 30f
    override var longShadowColor: Int = Color.DKGRAY
    override var longShadowAngle: Float = 45f
    override var motionBlurLength: Float = 0f
    override var motionBlurAngle: Int = 0
    override var halftoneDotSize: Float = 10f
    override var halftoneDotColor: Int = Color.BLACK
    override var halftoneThreshold: Float = 0.5f
    override var neonRadius: Float = 30f
    override var neonColor: Int = Color.CYAN
    override var glitchIntensity: Float = 1.0f
    override var pixelBlockSize: Float = 10f
    override var chromaticShift: Float = 5f
    override var chromaticColors: IntArray = intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF00FF00.toInt())
    override var fieryColor: Int = Color.rgb(255, 100, 0)
    override var fieryIntensity: Float = 0.5f
    override var wavyIntensity: Float = 0.5f
    override var wavyFrequency: Float = 5f
    override var particleSize: Float = 5f
    override var particleSpread: Float = 0.5f
    override var particleDissolveAngle: Float = 0f
    override var multiGradientColors: IntArray = intArrayOf(0xFFFF0000.toInt(), 0xFFFF7F00.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF4B0082.toInt(), 0xFF9400D3.toInt())
    override var multiGradientAngle: Float = 0f
    override var radialBlurInnerRadius: Float = 0f
    override var radialBlurMotionStrength: Float = 0f
    override var decayIntensity: Float = 0.5f
    override var decayFadingLevel: Float = 0.5f

    // Erase
    override var eraseMask: Bitmap? = null
    override val erasePaths = mutableListOf<ErasePathData>()

    @Transient
    override var activeErasePath: Path? = null
    @Transient
    override var activeEraseSize: Float = 0f
    @Transient
    override var activeEraseOpacity: Int = 0
    @Transient
    override var activeEraseHardness: Float = 0f
    override var eraseDragRevision: Int = 0

    init {
        name = "Brush Layer"
    }

    override fun getWidth(): Float {
        return bitmap.width.toFloat()
    }

    override fun getHeight(): Float {
        return bitmap.height.toFloat()
    }

    override fun draw(canvas: Canvas) {
        if (!isVisible) return

        canvas.save()
        // No translation/rotation/scale transformations applied for drawing (since BrushLayer does not support transformations, coordinates align with Canvas)
        val w = getWidth()
        val h = getHeight()

        val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        layerPaint.alpha = opacity

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

        val pad = calculatePadding()
        val bounds = RectF(-pad, -pad, w + pad, h + pad)

        val saveCount = canvas.saveLayer(bounds, layerPaint)
        drawContent(canvas, w, h)
        canvas.restoreToCount(saveCount)
        canvas.restore()
    }

    private fun drawContent(canvas: Canvas, w: Float, h: Float) {
        val dest = RectF(0f, 0f, w, h)
        canvas.drawBitmap(bitmap, null, dest, null)

        val pad = calculatePadding()
        // Apply Erase Mask
        if (eraseMask != null) {
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            canvas.drawBitmap(eraseMask!!, -pad, -pad, maskPaint)
        }

        // Apply active erase path preview
        if (activeErasePath != null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = activeEraseSize
                alpha = activeEraseOpacity
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                if (activeEraseHardness < 100) {
                    val radius = activeEraseSize / 2f
                    val blur = radius * (1f - (activeEraseHardness / 100f))
                    if (blur > 0.5f) {
                        maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                    }
                }
            }
            canvas.save()
            canvas.translate(-pad, -pad)
            canvas.drawPath(activeErasePath!!, p)
            canvas.restore()
        }
    }

    override fun updateDenseWarpMesh() {}

    override fun calculatePadding(): Float {
        return 20f
    }

    override fun addErasePath(path: Path, size: Float, opacity: Int, hardness: Float) {
        erasePaths.add(ErasePathData(Path(path), size, opacity, hardness))
        if (eraseMask == null) {
            val baseW = getWidth().toInt().coerceAtLeast(1)
            val baseH = getHeight().toInt().coerceAtLeast(1)
            val pad = calculatePadding()
            val maskW = (baseW + pad * 2).toInt().coerceAtLeast(1)
            val maskH = (baseH + pad * 2).toInt().coerceAtLeast(1)
            eraseMask = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888)
        }
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

    override fun undoLastErasePath(baseMask: Bitmap?) {
        if (erasePaths.isNotEmpty()) {
            erasePaths.removeAt(erasePaths.size - 1)
            rebuildEraseMask(baseMask)
        }
    }

    override fun rebuildEraseMask(baseMask: Bitmap?) {
        val pad = calculatePadding()
        val baseW = getWidth().toInt().coerceAtLeast(1)
        val baseH = getHeight().toInt().coerceAtLeast(1)
        val maskW = (baseW + pad * 2).toInt().coerceAtLeast(1)
        val maskH = (baseH + pad * 2).toInt().coerceAtLeast(1)

        val newMask = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888)
        val c = Canvas(newMask)
        c.drawColor(Color.TRANSPARENT)

        if (baseMask != null) {
             c.drawBitmap(baseMask, 0f, 0f, null)
        }

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

    override fun contains(px: Float, py: Float): Boolean {
        // BrushLayer doesn't have a bounding box and is not selectable via tapping on empty space
        return false
    }

    override fun clone(): Layer {
        val newLayer = BrushLayer(bitmap.copy(bitmap.config, true))
        newLayer.x = x
        newLayer.y = y
        newLayer.rotation = rotation
        newLayer.scaleX = scaleX
        newLayer.scaleY = scaleY
        newLayer.isVisible = isVisible
        newLayer.isLocked = isLocked
        newLayer.name = name

        newLayer.opacity = opacity
        newLayer.blendMode = blendMode
        newLayer.isOpacityGradient = isOpacityGradient
        newLayer.opacityStart = opacityStart
        newLayer.opacityEnd = opacityEnd
        newLayer.opacityAngle = opacityAngle

        newLayer.brushName = brushName
        newLayer.brushColor = brushColor
        newLayer.brushSize = brushSize
        newLayer.brushHardness = brushHardness
        newLayer.brushOpacity = brushOpacity

        if (this.eraseMask != null) {
            newLayer.eraseMask = this.eraseMask!!.copy(this.eraseMask!!.config, true)
        }
        for (p in this.erasePaths) {
            newLayer.erasePaths.add(ErasePathData(Path(p.path), p.size, p.opacity, p.hardness))
        }

        return newLayer
    }
}
