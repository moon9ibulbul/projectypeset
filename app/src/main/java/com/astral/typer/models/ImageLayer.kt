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

class ImageLayer(
    var bitmap: Bitmap,
    var imagePath: String? = null
) : Layer(), StylableLayer {

    override var color: Int = Color.BLACK

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

    @Transient
    var denseRenderMesh: FloatArray? = null

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
    override var motionBlurKernelSize: Int = 5
    override var motionBlurOffset: Float = 0f
    override var motionBlurVelocityX: Float = 0f
    override var motionBlurVelocityY: Float = 0f
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
    override var radialBlurCenterX: Float = 0.5f
    override var radialBlurCenterY: Float = 0.5f
    override var decayIntensity: Float = 0.5f
    override var decayFadingLevel: Float = 0.5f

    // Twist
    override var twistAngle: Float = 4.0f
    override var twistOffsetX: Float = 0.0f
    override var twistOffsetY: Float = 0.0f
    override var twistRadius: Float = 200.0f

    // Bulge & Pinch
    override var bulgeCenterX: Float = 0.5f
    override var bulgeCenterY: Float = 0.5f
    override var bulgeRadius: Float = 100.0f
    override var bulgeStrength: Float = 1.0f

    // Reflection
    override var reflectionAlphaStart: Float = 1.0f
    override var reflectionAlphaEnd: Float = 1.0f
    override var reflectionAmplitudeStart: Float = 0.0f
    override var reflectionAmplitudeEnd: Float = 20.0f
    override var reflectionBoundary: Float = 0.5f
    override var reflectionMirror: Boolean = true
    override var reflectionTime: Float = 0.0f
    override var reflectionWavelengthStart: Float = 30.0f
    override var reflectionWavelengthEnd: Float = 100.0f

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
        name = "Image Layer"
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

        // We use dummy padding since ImageLayer currently doesn't support effects like neon/stroke etc. in its drawContent,
        // but StylableLayer interface implies it could. For now focus on Perspective/Warp/Erase.
        val pad = calculatePadding()

        val bounds = if (isWarp && warpMesh != null) {
            val b = RectF()
            val steps = 10
            val out = FloatArray(2)
            for (i in 0..steps) {
                val v = i / steps.toFloat()
                for (j in 0..steps) {
                    val u = j / steps.toFloat()
                    evaluateBezierSurface(u, v, out)
                    if (i == 0 && j == 0) b.set(out[0], out[1], out[0], out[1]) else b.union(out[0], out[1])
                }
            }
            b.inset(-pad - 50f, -pad - 50f)
            b
        } else if (isPerspective && perspectivePoints != null) {
            val srcRect = RectF(-w / 2f, -h / 2f, w / 2f, h / 2f)
            val matrix = calculatePerspectiveMatrix(srcRect, perspectivePoints!!)
            val b = RectF()
            val pts = floatArrayOf(
                -w / 2f, -h / 2f,
                w / 2f, -h / 2f,
                w / 2f, h / 2f,
                -w / 2f, h / 2f
            )
            matrix.mapPoints(pts)
            for (i in 0 until 4) {
                if (i == 0) b.set(pts[i * 2], pts[i * 2 + 1], pts[i * 2], pts[i * 2 + 1]) else b.union(pts[i * 2], pts[i * 2 + 1])
            }
            b.inset(-pad - 50f, -pad - 50f)
            b
        } else {
            RectF(-w / 2f - pad, -h / 2f - pad, w / 2f + pad, h / 2f + pad)
        }
        val saveCount = canvas.saveLayer(bounds, layerPaint)

        if (isWarp && warpMesh != null) {
            val qualityScale = Math.max(1f, Math.max(Math.abs(scaleX), Math.abs(scaleY))).coerceAtMost(3f)
            drawWarped(canvas, w, h, warpRows, warpCols, warpMesh!!, qualityScale)
        } else if (isPerspective && perspectivePoints != null) {
             drawPerspective(canvas, w, h)
        } else {
             canvas.translate(dx, dy)
             drawContent(canvas, w, h)
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

    private fun drawPerspective(canvas: Canvas, w: Float, h: Float) {
        val srcRect = RectF(-w / 2f, -h / 2f, w / 2f, h / 2f)
        val matrix = calculatePerspectiveMatrix(srcRect, perspectivePoints!!)
        canvas.save()
        canvas.concat(matrix)
        canvas.translate(-w / 2f, -h / 2f)
        drawContent(canvas, w, h)
        canvas.restore()
    }

    private fun drawWarped(canvas: Canvas, w: Float, h: Float, rows: Int, cols: Int, mesh: FloatArray, qualityScale: Float = 1.0f) {
        val pad = calculatePadding()
        val bmpW = ceil((w + pad * 2) * qualityScale).toInt()
        val bmpH = ceil((h + pad * 2) * qualityScale).toInt()

        if (bmpW > 0 && bmpH > 0) {
            val intermediateBitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val c = Canvas(intermediateBitmap)
            c.scale(qualityScale, qualityScale)
            c.translate(pad, pad)
            drawContent(c, w, h)

            val meshW = 20
            val meshH = 20
            val paddedVerts = FloatArray((meshW + 1) * (meshH + 1) * 2)
            val outPoint = FloatArray(2)
            var idx = 0
            for (i in 0..meshH) {
                val v = (i.toFloat() / meshH) * ((h + pad * 2) / h) - (pad / h)
                for (j in 0..meshW) {
                    val u = (j.toFloat() / meshW) * ((w + pad * 2) / w) - (pad / w)
                    evaluateBezierSurface(u, v, outPoint)
                    paddedVerts[idx++] = outPoint[0]
                    paddedVerts[idx++] = outPoint[1]
                }
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
            canvas.drawBitmapMesh(intermediateBitmap, meshW, meshH, paddedVerts, 0, null, 0, paint)
            intermediateBitmap.recycle()
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

    override fun evaluateBezierSurface(u: Float, v: Float, outPoint: FloatArray) {
        val mesh = warpMesh ?: return
        val rows = warpRows
        val cols = warpCols
        var x = 0f
        var y = 0f

        for (i in 0..rows) {
            for (j in 0..cols) {
                val b_i = bernstein(rows, i, v)
                val b_j = bernstein(cols, j, u)
                val basis = b_i * b_j
                val idx = (i * (cols + 1) + j) * 2
                x += mesh[idx] * basis
                y += mesh[idx + 1] * basis
            }
        }
        outPoint[0] = x
        outPoint[1] = y
    }

    private fun bernstein(n: Int, i: Int, t: Float): Float {
        var coeff = 1f
        for (k in 1..i) {
            coeff = coeff * (n - k + 1) / k
        }
        return coeff * Math.pow(t.toDouble(), i.toDouble()).toFloat() * Math.pow((1f - t).toDouble(), (n - i).toDouble()).toFloat()
    }

    override fun updateDenseWarpMesh() {
        if (warpMesh == null) return
        val denseCols = 20
        val denseRows = 20
        val size = (denseCols + 1) * (denseRows + 1) * 2
        if (denseRenderMesh == null || denseRenderMesh!!.size != size) {
            denseRenderMesh = FloatArray(size)
        }
        val outPoint = FloatArray(2)
        var idx = 0
        for (i in 0..denseRows) {
            val v = i.toFloat() / denseRows
            for (j in 0..denseCols) {
                val u = j.toFloat() / denseCols
                evaluateBezierSurface(u, v, outPoint)
                denseRenderMesh!![idx++] = outPoint[0]
                denseRenderMesh!![idx++] = outPoint[1]
            }
        }
    }

    override fun calculatePadding(): Float {
        // ImageLayer currently doesn't support effects that expand bounds, but we keep this for interface compatibility
        return 20f
    }

    override fun addErasePath(path: Path, size: Float, opacity: Int, hardness: Float) {
        erasePaths.add(ErasePathData(Path(path), size, opacity, hardness))
        if (eraseMask == null) {
            // Should be initialized in AstralCanvasView onTouch
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

    override fun clone(): Layer {
        val newLayer = ImageLayer(bitmap, imagePath)
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

        newLayer.isPerspective = isPerspective
        newLayer.perspectivePoints = perspectivePoints?.clone()
        newLayer.isWarp = isWarp
        newLayer.warpRows = warpRows
        newLayer.warpCols = warpCols
        newLayer.warpMesh = warpMesh?.clone()

        newLayer.motionShadowThickness = this.motionShadowThickness

        if (this.eraseMask != null) {
            newLayer.eraseMask = this.eraseMask!!.copy(this.eraseMask!!.config, true)
        }
        for (p in this.erasePaths) {
            newLayer.erasePaths.add(ErasePathData(Path(p.path), p.size, p.opacity, p.hardness))
        }

        return newLayer
    }
}
