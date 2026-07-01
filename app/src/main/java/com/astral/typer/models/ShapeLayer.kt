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
import com.caverock.androidsvg.SVG
import java.util.Random
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

class ShapeLayer(
    var shapeName: String, // e.g. "shapes/circle.svg"
    var color: Int = Color.BLACK
) : Layer() {

    // Shadow
    var shadowColor: Int = Color.GRAY
    var shadowRadius: Float = 0f
    var shadowDx: Float = 0f
    var shadowDy: Float = 0f

    // Motion Shadow
    var isMotionShadow: Boolean = false
    var isMotionShadowIncludeStroke: Boolean = false
    var motionShadowAngle: Int = 0
    var motionShadowDistance: Float = 0f

    // Gradient
    var isGradient: Boolean = false
    var gradientStartColor: Int = Color.RED
    var gradientEndColor: Int = Color.BLUE
    var gradientAngle: Int = 0
    var isGradientText: Boolean = true // Repurposed for Shape Fill
    var isGradientStroke: Boolean = false
    var isGradientShadow: Boolean = false

    var isGlobalGradient: Boolean = false
    var globalP1: PointF = PointF()
    var globalP2: PointF = PointF()

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

    @Transient
    var denseRenderMesh: FloatArray? = null

    // Texture
    var textureBitmap: Bitmap? = null
    var textureOffsetX: Float = 0f
    var textureOffsetY: Float = 0f

    // Built-in Pattern
    var patternName: String? = null // Asset path
    var patternColor: Int = Color.BLACK
    var patternAlpha: Int = 255
    var patternScale: Float = 1.0f
    var patternRotation: Float = 0f

    // Erase
    var eraseMask: Bitmap? = null
    data class ErasePathData(val path: Path, val size: Float, val opacity: Int, val hardness: Float)
    val erasePaths = mutableListOf<ErasePathData>()

    @Transient
    var activeErasePath: Path? = null
    @Transient
    var activeEraseSize: Float = 0f
    @Transient
    var activeEraseOpacity: Int = 0
    @Transient
    var activeEraseHardness: Float = 0f

    // Effect
    var currentEffect: TextEffectType = TextEffectType.NONE
    var secondaryEffect: TextEffectType = TextEffectType.NONE

    // Gaussian Blur
    var blurRadius: Float = 0f

    // Long Shadow
    var longShadowLength: Float = 30f
    var longShadowColor: Int = Color.DKGRAY
    var longShadowAngle: Float = 45f

    // Motion Blur
    var motionBlurLength: Float = 0f
    var motionBlurAngle: Int = 0

    // Halftone
    var halftoneDotSize: Float = 10f
    var halftoneDotColor: Int = Color.BLACK
    var halftoneThreshold: Float = 0.5f

    // Neon
    var neonRadius: Float = 30f
    var neonColor: Int = Color.CYAN

    // Glitch
    var glitchIntensity: Float = 1.0f

    // Pixelation
    var pixelBlockSize: Float = 10f

    // Chromatic Aberration
    var chromaticShift: Float = 5f
    var chromaticColors: IntArray = intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF00FF00.toInt())

    // Fiery
    var fieryColor: Int = Color.rgb(255, 100, 0)
    var fieryIntensity: Float = 0.5f

    // Wavy
    var wavyIntensity: Float = 0.5f
    var wavyFrequency: Float = 5f

    // Particle Dissolve
    var particleSize: Float = 5f
    var particleSpread: Float = 0.5f
    var particleDissolveAngle: Float = 0f

    // Multi Gradient
    var multiGradientColors: IntArray = intArrayOf(0xFFFF0000.toInt(), 0xFFFF7F00.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF4B0082.toInt(), 0xFF9400D3.toInt())
    var multiGradientAngle: Float = 0f

    // Radial Blur
    var radialBlurInnerRadius: Float = 0f
    var radialBlurMotionStrength: Float = 0f

    var effectSeed: Long = System.currentTimeMillis()

    @Transient
    private var cachedPixelBitmap: Bitmap? = null
    @Transient
    private var cachedPixelHash: Int = 0
    @Transient
    private var cachedWavyBitmap: Bitmap? = null
    @Transient
    private var cachedWavyHash: Int = 0
    @Transient
    private var cachedPatternShader: Shader? = null
    @Transient
    private var cachedPatternHash: Int = 0
    @Transient
    private var cachedPatternXfermode: PorterDuffXfermode? = null

    @Transient
    private var svg: SVG? = null
    @Transient
    private var svgString: String? = null

    private val commonPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        name = "Shape Layer"
    }

    override fun getWidth(): Float {
        ensureShapeLoaded()
        return svg?.documentWidth ?: 100f
    }

    override fun getHeight(): Float {
        ensureShapeLoaded()
        return svg?.documentHeight ?: 100f
    }

    private fun ensureShapeLoaded() {
        if (svg == null) {
            val context = com.astral.typer.TyperApplication.instance
            if (context != null) {
                try {
                    val inputStream = context.assets.open(shapeName)
                    svgString = inputStream.bufferedReader().use { it.readText() }
                    svg = SVG.getFromString(svgString)
                    inputStream.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun draw(canvas: Canvas) {
        if (!isVisible) return
        ensureShapeLoaded()
        if (svg == null) return

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
            val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val c = Canvas(bitmap)
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
            canvas.drawBitmapMesh(bitmap, meshW, meshH, paddedVerts, 0, null, 0, paint)
            bitmap.recycle()
        }
    }

    private fun drawContent(canvas: Canvas, w: Float, h: Float) {
        val gradientShader = getGradientShader(w, h)
        var silhouetteColor: Int? = null
        var isDrawingShadowPass = false

        val drawMain = { targetCanvas: Canvas ->
            commonPaint.reset()
            commonPaint.isAntiAlias = true

            // 1. Double Stroke
            if (doubleStrokeWidth > 0f && strokeWidth > 0f) {
                val colorToUse = (silhouetteColor ?: doubleStrokeColor)
                renderSvgManipulated(targetCanvas, fill = null, stroke = colorToUse, strokeW = strokeWidth + doubleStrokeWidth * 2)
            }

            // 2. Stroke
            if (strokeWidth > 0f) {
                val colorToUse = if (silhouetteColor != null) silhouetteColor!! else if (isGradient && isGradientStroke) Color.WHITE else strokeColor
                val shaderToUse = if (silhouetteColor == null && isGradient && isGradientStroke) gradientShader else null
                renderSvgManipulated(targetCanvas, fill = null, stroke = colorToUse, strokeW = strokeWidth, strokeShader = shaderToUse)
            }

            // 3. Fill
            if (silhouetteColor != null) {
                renderSvgManipulated(targetCanvas, fill = silhouetteColor!!, stroke = null)
            } else if (isDrawingShadowPass) {
                val colorToUse = shadowColor
                val shaderToUse = if (isGradient && isGradientShadow) gradientShader else null
                renderSvgManipulated(targetCanvas, fill = colorToUse, stroke = null, fillShader = shaderToUse)
            } else {
                val hasMultiGradient = currentEffect == TextEffectType.MULTI_GRADIENT || secondaryEffect == TextEffectType.MULTI_GRADIENT
                val fillShaderToUse = if (hasMultiGradient) getMultiGradientShader(w, h)
                                  else if (isGradient && isGradientText) gradientShader
                                  else if (textureBitmap != null) {
                                      val shader = android.graphics.BitmapShader(textureBitmap!!, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                                      val matrix = Matrix()
                                      matrix.postTranslate(textureOffsetX, textureOffsetY)
                                      shader.setLocalMatrix(matrix)
                                      shader
                                  } else null

                val colorToUse = if (fillShaderToUse != null) Color.WHITE else color
                renderSvgManipulated(targetCanvas, fill = colorToUse, stroke = null, fillShader = fillShaderToUse)

                // 4. Built-in Pattern Overlay
                if (patternName != null) {
                    val context = com.astral.typer.TyperApplication.instance
                    if (context != null) {
                        val currentPatternHash = listOf(patternName, patternColor, patternScale, patternRotation, patternAlpha, textureOffsetX, textureOffsetY).hashCode()
                        if (cachedPatternShader == null || cachedPatternHash != currentPatternHash) {
                            val patternBmp = com.astral.typer.utils.PatternManager.getPatternBitmap(context, patternName!!, patternColor)
                            if (patternBmp != null) {
                                val shader = android.graphics.BitmapShader(patternBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                                val matrix = Matrix()
                                matrix.postScale(patternScale, patternScale)
                                matrix.postRotate(patternRotation, patternBmp.width * patternScale / 2f, patternBmp.height * patternScale / 2f)
                                matrix.postTranslate(textureOffsetX, textureOffsetY)
                                shader.setLocalMatrix(matrix)
                                cachedPatternShader = shader
                                cachedPatternHash = currentPatternHash
                                cachedPatternXfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
                            }
                        }

                        if (cachedPatternShader != null) {
                            // Pattern overlay using SRC_ATOP over the fill
                            targetCanvas.saveLayer(null, null)
                            renderSvgManipulated(targetCanvas, fill = Color.WHITE, stroke = null)

                            val p = Paint(Paint.ANTI_ALIAS_FLAG)
                            p.shader = cachedPatternShader
                            p.alpha = patternAlpha
                            p.xfermode = cachedPatternXfermode
                            targetCanvas.drawRect(0f, 0f, w, h, p)
                            targetCanvas.restore()
                        }
                    }
                }
            }
        }

        val drawShadows = { targetCanvas: Canvas ->
            if (isMotionShadow && motionShadowDistance > 0) {
                val effectiveDistance = motionShadowDistance
                val iterations = kotlin.math.max(30, effectiveDistance.toInt())
                val angleRad = Math.toRadians(motionShadowAngle.toDouble())
                val cos = Math.cos(angleRad).toFloat()
                val sin = Math.sin(angleRad).toFloat()
                val maxBlur = 4f
                val initialShadowAlpha = 30f

                for (i in 1..iterations) {
                    val t = i / iterations.toFloat()
                    val d = t * effectiveDistance
                    val shadowAlpha = (initialShadowAlpha * (1f - t)).toInt().coerceIn(0, 255)
                    val blur = t * maxBlur

                    targetCanvas.save()
                    targetCanvas.translate(d * cos, d * sin)
                    if (isMotionShadowIncludeStroke) {
                        isDrawingShadowPass = true
                        // Manually applying alpha/blur since we don't have a unified paint here
                        // For simplicity in motion shadow, we'll just draw the fill silhouette with alpha
                        val c = (shadowColor and 0x00FFFFFF) or (shadowAlpha shl 24)
                        renderSvgManipulated(targetCanvas, fill = c, stroke = null)
                        isDrawingShadowPass = false
                    } else {
                        val c = (shadowColor and 0x00FFFFFF) or (shadowAlpha shl 24)
                        renderSvgManipulated(targetCanvas, fill = c, stroke = null)
                    }
                    targetCanvas.restore()

                    targetCanvas.save()
                    targetCanvas.translate(-d * cos, -d * sin)
                    val c = (shadowColor and 0x00FFFFFF) or (shadowAlpha shl 24)
                    renderSvgManipulated(targetCanvas, fill = c, stroke = null)
                    targetCanvas.restore()
                }
            }

            if (!isMotionShadow && shadowRadius > 0) {
                targetCanvas.save()
                targetCanvas.translate(shadowDx, shadowDy)
                // Use a shadow paint for simple shadow
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                if (isGradient && isGradientShadow) {
                     p.shader = gradientShader
                     p.maskFilter = BlurMaskFilter(shadowRadius, BlurMaskFilter.Blur.NORMAL)
                } else {
                     p.color = shadowColor
                     p.maskFilter = BlurMaskFilter(shadowRadius, BlurMaskFilter.Blur.NORMAL)
                }

                targetCanvas.saveLayer(null, p)
                renderSvgManipulated(targetCanvas, fill = Color.BLACK, stroke = null)
                targetCanvas.restore()
                targetCanvas.restore()
            }
        }

        drawShadows(canvas)
        val drawBase = { innerCanvas: Canvas -> drawMain(innerCanvas) }

        val activeEffects = mutableListOf<TextEffectType>()
        if (currentEffect != TextEffectType.NONE && currentEffect != TextEffectType.MULTI_GRADIENT) activeEffects.add(currentEffect)
        if (secondaryEffect != TextEffectType.NONE && secondaryEffect != TextEffectType.MULTI_GRADIENT) activeEffects.add(secondaryEffect)

        fun applyEffect(effect: TextEffectType, targetCanvas: Canvas, drawInner: (Canvas) -> Unit) {
             when (effect) {
                TextEffectType.CHROMATIC_ABERRATION -> {
                    val prevSilhouette = silhouetteColor
                    silhouetteColor = chromaticColors[0]
                    targetCanvas.save()
                    targetCanvas.translate(-chromaticShift, 0f)
                    drawInner(targetCanvas)
                    targetCanvas.restore()

                    silhouetteColor = chromaticColors[1]
                    targetCanvas.save()
                    targetCanvas.translate(chromaticShift, 0f)
                    drawInner(targetCanvas)
                    targetCanvas.restore()

                    silhouetteColor = chromaticColors[2]
                    drawInner(targetCanvas)
                    silhouetteColor = prevSilhouette
                }
                TextEffectType.PIXELATION -> {
                    val pad = calculatePadding()
                    val safeBlockSize = pixelBlockSize.coerceAtLeast(1f)
                    val scaleFactor = 1f / safeBlockSize
                    val scaledW = ((w + pad * 2) * scaleFactor).toInt().coerceAtLeast(1)
                    val scaledH = ((h + pad * 2) * scaleFactor).toInt().coerceAtLeast(1)
                    val currentHash = listOf(shapeName, w, h, color, safeBlockSize, strokeWidth, strokeColor, doubleStrokeWidth, doubleStrokeColor, currentEffect, secondaryEffect, pad).hashCode()
                    if (cachedPixelBitmap == null || cachedPixelBitmap!!.width != scaledW || cachedPixelBitmap!!.height != scaledH || cachedPixelHash != currentHash) {
                        cachedPixelBitmap?.recycle()
                        val tempBitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
                        val tempCanvas = Canvas(tempBitmap)
                        tempCanvas.scale(scaleFactor, scaleFactor)
                        tempCanvas.translate(pad, pad)
                        drawInner(tempCanvas)
                        cachedPixelBitmap = tempBitmap
                        cachedPixelHash = currentHash
                    }
                    if (cachedPixelBitmap != null && !cachedPixelBitmap!!.isRecycled) {
                        val pixelPaint = Paint().apply { isFilterBitmap = false }
                        targetCanvas.drawBitmap(cachedPixelBitmap!!, null, RectF(-pad, -pad, w + pad, h + pad), pixelPaint)
                    }
                }
                TextEffectType.GLITCH -> {
                    val pad = calculatePadding()
                    val random = Random(effectSeed)
                    var currentY = -pad
                    while (currentY < h + pad) {
                        var stripHeight = (h * 0.02f) + (random.nextFloat() * (h * 0.13f))
                        if (stripHeight < 1f) stripHeight = 1f
                        val bottom = kotlin.math.min(currentY + stripHeight, h + pad)
                        val xOffset = if (random.nextFloat() < 0.5f) (random.nextFloat() - 0.5f) * 100f * glitchIntensity else 0f
                        targetCanvas.save()
                        targetCanvas.clipRect(-pad, currentY, w + pad, bottom)
                        targetCanvas.translate(xOffset, 0f)
                        drawInner(targetCanvas)
                        targetCanvas.restore()
                        if (bottom <= currentY) break
                        currentY = bottom
                    }
                }
                TextEffectType.NEON -> {
                    val prevSilhouette = silhouetteColor
                    silhouetteColor = if (neonColor != Color.CYAN) neonColor else color
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(neonRadius.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL) }
                    targetCanvas.saveLayer(null, p)
                    drawInner(targetCanvas)
                    targetCanvas.restore()
                    silhouetteColor = prevSilhouette
                    drawInner(targetCanvas)
                }
                TextEffectType.LONG_SHADOW -> {
                    val prevSilhouette = silhouetteColor
                    silhouetteColor = longShadowColor
                    val shadowLen = longShadowLength.toInt().coerceAtLeast(1)
                    val rad = Math.toRadians(longShadowAngle.toDouble())
                    val xStep = cos(rad).toFloat()
                    val yStep = sin(rad).toFloat()
                    for (i in 1..shadowLen) {
                        targetCanvas.save(); targetCanvas.translate(i * xStep, i * yStep); drawInner(targetCanvas); targetCanvas.restore()
                    }
                    silhouetteColor = prevSilhouette
                    drawInner(targetCanvas)
                }
                TextEffectType.GAUSSIAN_BLUR -> {
                    val pad = calculatePadding()
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("GaussianBlurNode")
                            node.setPosition(0, 0, (w + pad * 2).toInt(), (h + pad * 2).toInt())
                            val rc = node.beginRecording(); rc.translate(pad, pad); drawInner(rc); node.endRecording()
                            val r = blurRadius.coerceAtLeast(0.1f)
                            node.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
                            targetCanvas.save(); targetCanvas.translate(-pad, -pad); targetCanvas.drawRenderNode(node); targetCanvas.restore()
                            useRenderEffect = true
                        } catch (e: Exception) {}
                    }
                    if (!useRenderEffect) {
                         val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { if (blurRadius > 0) maskFilter = BlurMaskFilter(blurRadius.coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL) }
                         targetCanvas.saveLayer(null, p)
                         drawInner(targetCanvas)
                         targetCanvas.restore()
                    }
                }
                else -> drawInner(targetCanvas)
             }
        }

        if (activeEffects.size == 2) {
            applyEffect(activeEffects[1], canvas) { innerCanvas -> applyEffect(activeEffects[0], innerCanvas, drawBase) }
        } else if (activeEffects.size == 1) {
            applyEffect(activeEffects[0], canvas, drawBase)
        } else {
            drawBase(canvas)
        }

        if (eraseMask != null) {
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
            canvas.drawBitmap(eraseMask!!, 0f, 0f, maskPaint)
        }
        if (activeErasePath != null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = activeEraseSize; alpha = activeEraseOpacity; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                if (activeEraseHardness < 100) {
                    val r = activeEraseSize / 2f
                    val b = r * (1f - (activeEraseHardness / 100f))
                    if (b > 0.5f) maskFilter = BlurMaskFilter(b, BlurMaskFilter.Blur.NORMAL)
                }
            }
            canvas.drawPath(activeErasePath!!, p)
        }
    }

    private fun renderSvgManipulated(canvas: Canvas, fill: Int?, stroke: Int?, strokeW: Float = 0f, fillShader: Shader? = null, strokeShader: Shader? = null) {
        if (svgString == null) return

        var manipulated = svgString!!

        // Simple regex-based manipulation for circle and path elements in assets
        if (fill != null || fillShader != null) {
            val hex = String.format("#%06X", 0xFFFFFF and (fill ?: Color.WHITE))
            manipulated = manipulated.replace(Regex("fill='[^']*'"), "fill='$hex'")
            manipulated = manipulated.replace(Regex("fill=\"[^\"]*\""), "fill=\"$hex\"")
        } else {
            manipulated = manipulated.replace(Regex("fill='[^']*'"), "fill='none'")
            manipulated = manipulated.replace(Regex("fill=\"[^\"]*\""), "fill=\"none\"")
        }

        if (stroke != null || strokeShader != null) {
            val hex = String.format("#%06X", 0xFFFFFF and (stroke ?: Color.WHITE))
            val sw = strokeW
            // Insert stroke attributes if not present, or replace
            if (!manipulated.contains("stroke=")) {
                 manipulated = manipulated.replace("<path ", "<path stroke='$hex' stroke-width='$sw' ")
                 manipulated = manipulated.replace("<circle ", "<circle stroke='$hex' stroke-width='$sw' ")
                 manipulated = manipulated.replace("<ellipse ", "<ellipse stroke='$hex' stroke-width='$sw' ")
                 manipulated = manipulated.replace("<rect ", "<rect stroke='$hex' stroke-width='$sw' ")
                 manipulated = manipulated.replace("<polygon ", "<polygon stroke='$hex' stroke-width='$sw' ")
            } else {
                 manipulated = manipulated.replace(Regex("stroke='[^']*'"), "stroke='$hex'")
                 manipulated = manipulated.replace(Regex("stroke-width='[^']*'"), "stroke-width='$sw'")
            }
        }

        try {
            val mSvg = SVG.getFromString(manipulated)

            if (fillShader != null || strokeShader != null) {
                // If shader is present, we render to a layer and apply shader via SRC_IN
                canvas.saveLayer(null, null)
                mSvg.renderToCanvas(canvas)

                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                if (fillShader != null) {
                    p.shader = fillShader
                    canvas.drawRect(0f, 0f, getWidth(), getHeight(), p)
                }
                // Stroke shader is trickier with this approach as it would fill the whole shape.
                // But for now this covers main cases.
                canvas.restore()
            } else {
                mSvg.renderToCanvas(canvas)
            }
        } catch (e: Exception) {}
    }

    private fun calculatePadding(): Float {
        var p = strokeWidth + doubleStrokeWidth
        p = Math.max(p, shadowRadius + Math.max(Math.abs(shadowDx), Math.abs(shadowDy)))
        if (isMotionShadow) p = Math.max(p, motionShadowDistance + 20f)
        return (p + blurRadius * 2.5f + longShadowLength + 20f).coerceAtLeast(0f)
    }

    private fun getGradientShader(w: Float, h: Float): Shader? {
        if (!isGradient) return null
        if (isGlobalGradient) {
            val inverse = Matrix()
            val matrix = Matrix()
            matrix.setTranslate(x, y); matrix.preRotate(rotation); matrix.preScale(scaleX, scaleY)
            if (matrix.invert(inverse)) {
                val pts = floatArrayOf(globalP1.x, globalP1.y, globalP2.x, globalP2.y); inverse.mapPoints(pts)
                val x0 = pts[0] + w/2f; val y0 = pts[1] + h/2f; val x1 = pts[2] + w/2f; val y1 = pts[3] + h/2f
                return LinearGradient(x0, y0, x1, y1, gradientStartColor, gradientEndColor, Shader.TileMode.CLAMP)
            }
        }
        return createGradient(w, h, gradientAngle, gradientStartColor, gradientEndColor)
    }

    private fun createGradient(w: Float, h: Float, angle: Int, startColor: Int, endColor: Int): Shader {
        val cx = w / 2f; val cy = h / 2f; val angleRad = Math.toRadians(angle.toDouble())
        val cos = Math.cos(angleRad).toFloat(); val sin = Math.sin(angleRad).toFloat()
        val corners = listOf(Pair(-cx, -cy), Pair(cx, -cy), Pair(-cx, cy), Pair(cx, cy))
        var minP = Float.MAX_VALUE; var maxP = -Float.MAX_VALUE
        for ((px, py) in corners) {
            val p = px * cos + py * sin
            if (p < minP) minP = p
            if (p > maxP) maxP = p
        }
        val halfLen = (maxP - minP) / 2f
        return LinearGradient(cx - halfLen * cos, cy - halfLen * sin, cx + halfLen * cos, cy + halfLen * sin, startColor, endColor, Shader.TileMode.CLAMP)
    }

    private fun getMultiGradientShader(w: Float, h: Float): Shader {
        val cx = w / 2f; val cy = h / 2f; val angleRad = Math.toRadians(multiGradientAngle.toDouble())
        val cos = Math.cos(angleRad).toFloat(); val sin = Math.sin(angleRad).toFloat()
        val corners = listOf(Pair(-cx, -cy), Pair(cx, -cy), Pair(-cx, cy), Pair(cx, cy))
        var minP = Float.MAX_VALUE; var maxP = -Float.MAX_VALUE
        for ((px, py) in corners) {
            val p = px * cos + py * sin
            if (p < minP) minP = p
            if (p > maxP) maxP = p
        }
        val halfLen = (maxP - minP) / 2f
        val positions = FloatArray(multiGradientColors.size) { i -> i.toFloat() / (multiGradientColors.size - 1) }
        return LinearGradient(cx - halfLen * cos, cy - halfLen * sin, cx + halfLen * cos, cy + halfLen * sin, multiGradientColors, positions, Shader.TileMode.CLAMP)
    }

    private fun getOpacityGradientShader(w: Float, h: Float): Shader {
        val startColor = (opacityStart shl 24) or 0x000000; val endColor = (opacityEnd shl 24) or 0x000000
        return createGradient(w, h, opacityAngle, startColor, endColor)
    }

    fun evaluateBezierSurface(u: Float, v: Float, outPoint: FloatArray) {
        val mesh = warpMesh ?: return; val rows = warpRows; val cols = warpCols; var x = 0f; var y = 0f
        for (i in 0..rows) {
            for (j in 0..cols) {
                val basis = bernstein(rows, i, v) * bernstein(cols, j, u)
                val idx = (i * (cols + 1) + j) * 2
                x += mesh[idx] * basis; y += mesh[idx + 1] * basis
            }
        }
        outPoint[0] = x; outPoint[1] = y
    }

    private fun bernstein(n: Int, i: Int, t: Float): Float {
        var coeff = 1f; for (k in 1..i) coeff = coeff * (n - k + 1) / k
        return coeff * Math.pow(t.toDouble(), i.toDouble()).toFloat() * Math.pow((1f - t).toDouble(), (n - i).toDouble()).toFloat()
    }

    private fun calculatePerspectiveMatrix(src: RectF, dst: FloatArray): Matrix {
        val matrix = Matrix(); val srcPts = floatArrayOf(src.left, src.top, src.right, src.top, src.right, src.bottom, src.left, src.bottom)
        matrix.setPolyToPoly(srcPts, 0, dst, 0, 4); return matrix
    }

    fun addErasePath(path: Path, size: Float, opacity: Int, hardness: Float) {
        erasePaths.add(ErasePathData(Path(path), size, opacity, hardness))
        if (eraseMask != null) {
             val c = Canvas(eraseMask!!); val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                 color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = size; this.alpha = opacity; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                 if (hardness < 100) {
                     val r = size / 2f; val b = r * (1f - (hardness / 100f))
                     if (b > 0.5f) maskFilter = BlurMaskFilter(b, BlurMaskFilter.Blur.NORMAL)
                 }
             }
             c.drawPath(path, p)
        }
    }

    fun undoLastErasePath(baseMask: Bitmap?) {
        if (erasePaths.isNotEmpty()) { erasePaths.removeAt(erasePaths.size - 1); rebuildEraseMask(baseMask) }
    }

    fun rebuildEraseMask(baseMask: Bitmap?) {
        val w = eraseMask?.width ?: baseMask?.width ?: getWidth().toInt().coerceAtLeast(1)
        val h = eraseMask?.height ?: baseMask?.height ?: getHeight().toInt().coerceAtLeast(1)
        val newMask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(newMask)
        if (baseMask != null) c.drawBitmap(baseMask, 0f, 0f, null)
        for (data in erasePaths) {
             val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                 color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = data.size; this.alpha = data.opacity; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                 if (data.hardness < 100) {
                     val r = data.size / 2f; val b = r * (1f - (data.hardness / 100f))
                     if (b > 0.5f) maskFilter = BlurMaskFilter(b, BlurMaskFilter.Blur.NORMAL)
                 }
             }
             c.drawPath(data.path, p)
        }
        eraseMask = newMask
    }

    override fun clone(): Layer {
        val newLayer = ShapeLayer(shapeName, color)
        newLayer.x = x; newLayer.y = y; newLayer.rotation = rotation; newLayer.scaleX = scaleX; newLayer.scaleY = scaleY
        newLayer.isVisible = isVisible; newLayer.isLocked = isLocked; newLayer.name = name
        newLayer.opacity = opacity; newLayer.blendMode = blendMode; newLayer.isOpacityGradient = isOpacityGradient; newLayer.opacityStart = opacityStart; newLayer.opacityEnd = opacityEnd; newLayer.opacityAngle = opacityAngle
        newLayer.shadowColor = shadowColor; newLayer.shadowRadius = shadowRadius; newLayer.shadowDx = shadowDx; newLayer.shadowDy = shadowDy
        newLayer.isMotionShadow = isMotionShadow; newLayer.isMotionShadowIncludeStroke = isMotionShadowIncludeStroke; newLayer.motionShadowAngle = motionShadowAngle; newLayer.motionShadowDistance = motionShadowDistance
        newLayer.isGradient = isGradient; newLayer.gradientStartColor = gradientStartColor; newLayer.gradientEndColor = gradientEndColor; newLayer.gradientAngle = gradientAngle; newLayer.isGradientText = isGradientText; newLayer.isGradientStroke = isGradientStroke; newLayer.isGradientShadow = isGradientShadow
        newLayer.isGlobalGradient = isGlobalGradient; newLayer.globalP1 = PointF(globalP1.x, globalP1.y); newLayer.globalP2 = PointF(globalP2.x, globalP2.y)
        newLayer.strokeColor = strokeColor; newLayer.strokeWidth = strokeWidth; newLayer.doubleStrokeColor = doubleStrokeColor; newLayer.doubleStrokeWidth = doubleStrokeWidth
        newLayer.isPerspective = isPerspective; newLayer.perspectivePoints = perspectivePoints?.clone()
        newLayer.isWarp = isWarp; newLayer.warpRows = warpRows; newLayer.warpCols = warpCols; newLayer.warpMesh = warpMesh?.clone()
        newLayer.textureBitmap = textureBitmap; newLayer.textureOffsetX = textureOffsetX; newLayer.textureOffsetY = textureOffsetY
        newLayer.patternName = patternName; newLayer.patternColor = patternColor; newLayer.patternAlpha = patternAlpha; newLayer.patternScale = patternScale; newLayer.patternRotation = patternRotation
        if (eraseMask != null) newLayer.eraseMask = eraseMask!!.copy(eraseMask!!.config, true)
        for (p in erasePaths) newLayer.erasePaths.add(ErasePathData(Path(p.path), p.size, p.opacity, p.hardness))
        newLayer.currentEffect = currentEffect; newLayer.secondaryEffect = secondaryEffect; newLayer.blurRadius = blurRadius; newLayer.longShadowLength = longShadowLength; newLayer.longShadowColor = longShadowColor; newLayer.longShadowAngle = longShadowAngle; newLayer.motionBlurLength = motionBlurLength; newLayer.motionBlurAngle = motionBlurAngle; newLayer.halftoneDotSize = halftoneDotSize; newLayer.halftoneDotColor = halftoneDotColor; newLayer.halftoneThreshold = halftoneThreshold; newLayer.neonRadius = neonRadius; newLayer.neonColor = neonColor; newLayer.glitchIntensity = glitchIntensity; newLayer.pixelBlockSize = pixelBlockSize; newLayer.chromaticShift = chromaticShift; newLayer.chromaticColors = chromaticColors.clone(); newLayer.effectSeed = effectSeed; newLayer.fieryColor = fieryColor; newLayer.fieryIntensity = fieryIntensity; newLayer.wavyIntensity = wavyIntensity; newLayer.wavyFrequency = wavyFrequency; newLayer.particleSize = particleSize; newLayer.particleSpread = particleSpread; newLayer.particleDissolveAngle = particleDissolveAngle; newLayer.multiGradientColors = multiGradientColors.clone(); newLayer.multiGradientAngle = multiGradientAngle; newLayer.radialBlurInnerRadius = radialBlurInnerRadius; newLayer.radialBlurMotionStrength = radialBlurMotionStrength
        return newLayer
    }

    fun updateDenseWarpMesh() {
        if (warpMesh == null) return
        val denseCols = 20; val denseRows = 20; val size = (denseCols + 1) * (denseRows + 1) * 2
        if (denseRenderMesh == null || denseRenderMesh!!.size != size) denseRenderMesh = FloatArray(size)
        val outPoint = FloatArray(2); var idx = 0
        for (i in 0..denseRows) {
            val v = i.toFloat() / denseRows
            for (j in 0..denseCols) {
                val u = j.toFloat() / denseCols
                evaluateBezierSurface(u, v, outPoint)
                denseRenderMesh!![idx++] = outPoint[0]; denseRenderMesh!![idx++] = outPoint[1]
            }
        }
    }
}
