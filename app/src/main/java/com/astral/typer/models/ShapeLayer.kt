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
    override var color: Int = Color.BLACK
) : Layer(), StylableLayer {

    // Shadow
    override var shadowColor: Int = Color.GRAY
    override var shadowRadius: Float = 0f
    override var shadowDx: Float = 0f
    override var shadowDy: Float = 0f

    // Motion Shadow
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
    override var hasMiddleColor: Boolean = false
    override var gradientMiddleColor: Int = Color.GREEN
    override var gradientStartPos: Float = 0.0f
    override var gradientMiddlePos: Float = 0.5f
    override var gradientEndPos: Float = 1.0f
    override var isGradientText: Boolean = true // Repurposed for Shape Fill
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
    override var tripleStrokeColor: Int = Color.WHITE
    override var tripleStrokeWidth: Float = 0f

    // Perspective
    override var isPerspective: Boolean = false
    override var perspectivePoints: FloatArray? = null

    // Warp
    override var isWarp: Boolean = false
    override var warpRows: Int = 2
    override var warpCols: Int = 2
    override var warpMesh: FloatArray? = null
    override var selectedWarpIndex: Int
        get() = -1
        set(value) {}

    @Transient
    var denseRenderMesh: FloatArray? = null

    // Texture
    override var textureBitmap: Bitmap? = null
    override var textureOffsetX: Float = 0f
    override var textureOffsetY: Float = 0f

    // Built-in Pattern
    override var patternName: String? = null // Asset path
    override var patternColor: Int = Color.BLACK
    override var patternAlpha: Int = 255
    override var patternScale: Float = 1.0f
    override var patternRotation: Float = 0f

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

    private var silhouetteColor: Int? = null

    var customWidth: Float? = null
    var customHeight: Float? = null

    // Effect
    override var currentEffect: TextEffectType = TextEffectType.NONE
    override var secondaryEffect: TextEffectType = TextEffectType.NONE
    override var tertiaryEffect: TextEffectType = TextEffectType.NONE

    // Gaussian Blur
    override var blurRadius: Float = 0f

    // Long Shadow
    override var longShadowLength: Float = 30f
    override var longShadowColor: Int = Color.DKGRAY
    override var longShadowAngle: Float = 45f

    // Motion Blur
    override var motionBlurLength: Float = 0f
    override var motionBlurAngle: Int = 0
    override var motionBlurKernelSize: Int = 5
    override var motionBlurOffset: Float = 0f
    override var motionBlurVelocityX: Float = 0f
    override var motionBlurVelocityY: Float = 0f

    // Halftone
    override var halftoneDotSize: Float = 10f
    override var halftoneDotColor: Int = Color.BLACK
    override var halftoneThreshold: Float = 0.5f
    override var halftoneType: String = "INNER"
    override var halftoneAlpha: Float = 1.0f
    override var halftoneRange: Float = 20f
    override var halftoneDensity: Float = 10f
    override var halftoneFadingIntensity: Float = 1.0f
    override var halftoneShape: String = "DOT"

    // Neon
    override var neonRadius: Float = 30f
    override var neonColor: Int = Color.CYAN
    override var neonAlpha: Float = 1.0f
    override var neonInnerStrength: Float = 0.0f
    override var neonOuterStrength: Float = 4.0f
    override var neonKnockout: Boolean = false
    override var neonQuality: Float = 0.1f

    // Glitch
    override var glitchIntensity: Float = 1.0f
    override var glitchSeed: Long = System.currentTimeMillis()
    override var decaySeed: Long = System.currentTimeMillis()
    override var glitch2Seed: Long = System.currentTimeMillis()
    override var glitch2Intensity: Float = 0.5f
    override var glitch2Slices: Float = 20f
    override var glitch2RgbSplitIntensity: Float = 2f
    override var glitch2NoiseIntensity: Float = 0.5f

    // Pixelation
    override var pixelBlockSize: Float = 10f

    // Chromatic Aberration
    override var chromaticShift: Float = 5f
    override var chromaticColors: IntArray = intArrayOf(0xFF00FFFF.toInt(), 0xFFFFFF00.toInt(), 0xFFFF00FF.toInt())
    override var chromaticAngle: Float = 0f

    // Fiery
    override var fieryColor: Int = Color.rgb(255, 100, 0)
    override var fieryIntensity: Float = 0.5f

    // Wavy
    override var wavyIntensity: Float = 0.5f
    override var wavyFrequency: Float = 5f

    // Particle Dissolve
    override var particleSize: Float = 5f
    override var particleSpread: Float = 0.5f
    override var particleDissolveAngle: Float = 0f

    // Multi Gradient
    override var multiGradientColors: IntArray = intArrayOf(0xFFFF0000.toInt(), 0xFFFF7F00.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF4B0082.toInt(), 0xFF9400D3.toInt())
    override var multiGradientAngle: Float = 0f

    // Radial Blur
    override var radialBlurInnerRadius: Float = 0f
    override var radialBlurMotionStrength: Float = 0f
    override var radialBlurCenterX: Float = 0.5f
    override var radialBlurCenterY: Float = 0.5f

    // Text Decay
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

    // Zoom Blur
    override var zoomBlurCenterX: Float = 0.5f
    override var zoomBlurCenterY: Float = 0.5f
    override var zoomBlurInnerRadius: Float = 0f
    override var zoomBlurRadius: Float = -1f
    override var zoomBlurStrength: Float = 0.1f

    override var effectSeed: Long = System.currentTimeMillis()

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
        if (customWidth != null) return customWidth!!
        ensureShapeLoaded()
        val w = svg?.documentWidth ?: -1f
        if (w > 0) return w
        return svg?.documentViewBox?.width() ?: 100f
    }

    override fun getHeight(): Float {
        if (customHeight != null) return customHeight!!
        ensureShapeLoaded()
        val h = svg?.documentHeight ?: -1f
        if (h > 0) return h
        return svg?.documentViewBox?.height() ?: 100f
    }

    private fun ensureShapeLoaded() {
        if (svg == null) {
            val context = com.astral.typer.TyperApplication.instance
            if (context != null) {
                try {
                    val inputStream = context.assets.open(shapeName)
                    var raw = inputStream.bufferedReader().use { it.readText() }
                    // Strip potential BOM
                    if (raw.startsWith("\uFEFF")) raw = raw.substring(1)
                    svgString = raw
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

        svg!!.documentWidth = getWidth()
        svg!!.documentHeight = getHeight()

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

        val activeEffects = mutableListOf<TextEffectType>()
        if (currentEffect != TextEffectType.NONE && currentEffect != TextEffectType.MULTI_GRADIENT) activeEffects.add(currentEffect)
        if (secondaryEffect != TextEffectType.NONE && secondaryEffect != TextEffectType.MULTI_GRADIENT) activeEffects.add(secondaryEffect)
        if (tertiaryEffect != TextEffectType.NONE && tertiaryEffect != TextEffectType.MULTI_GRADIENT) activeEffects.add(tertiaryEffect)

        val hasTransform = (isWarp && warpMesh != null) || (isPerspective && perspectivePoints != null)
        val hasHardwareShaderEffect = activeEffects.any {
            it == TextEffectType.GLITCH_2 ||
            it == TextEffectType.FIERY ||
            it == TextEffectType.WAVY ||
            it == TextEffectType.PARTICLE_DISSOLVE ||
            it == TextEffectType.MOTION_BLUR ||
            it == TextEffectType.RADIAL_BLUR ||
            it == TextEffectType.HALFTONE ||
            it == TextEffectType.TEXT_DECAY ||
            it == TextEffectType.TWIST ||
            it == TextEffectType.BULGE_PINCH ||
            it == TextEffectType.REFLECTION ||
            it == TextEffectType.ZOOM_BLUR ||
            it == TextEffectType.GAUSSIAN_BLUR ||
            it == TextEffectType.NEON
        }
        val useHardwareTransformEffects = hasTransform && hasHardwareShaderEffect && canvas.isHardwareAccelerated

        if (useHardwareTransformEffects) {
            val drawTransformed = { targetCanvas: Canvas ->
                if (isWarp && warpMesh != null) {
                    val qualityScale = Math.max(1f, Math.max(Math.abs(scaleX), Math.abs(scaleY))).coerceAtMost(3f)
                    drawWarped(targetCanvas, w, h, warpRows, warpCols, warpMesh!!, qualityScale, skipEffects = true)
                } else if (isPerspective && perspectivePoints != null) {
                    drawPerspective(targetCanvas, w, h, skipEffects = true)
                }
            }

            fun renderChain(index: Int, targetCanvas: Canvas) {
                if (index < 0) {
                    drawTransformed(targetCanvas)
                } else {
                    applyEffect(activeEffects[index], targetCanvas, w, h, bounds) { innerCanvas ->
                        renderChain(index - 1, innerCanvas)
                    }
                }
            }
            renderChain(activeEffects.size - 1, canvas)
        } else {
            if (isWarp && warpMesh != null) {
                val qualityScale = Math.max(1f, Math.max(Math.abs(scaleX), Math.abs(scaleY))).coerceAtMost(3f)
                drawWarped(canvas, w, h, warpRows, warpCols, warpMesh!!, qualityScale, skipEffects = false)
            } else if (isPerspective && perspectivePoints != null) {
                 drawPerspective(canvas, w, h, skipEffects = false)
            } else {
                 canvas.translate(dx, dy)
                 drawContent(canvas, w, h, skipEffects = false)
            }
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

    private fun drawPerspective(canvas: Canvas, w: Float, h: Float, skipEffects: Boolean = false) {
        val srcRect = RectF(-w / 2f, -h / 2f, w / 2f, h / 2f)
        val matrix = calculatePerspectiveMatrix(srcRect, perspectivePoints!!)
        canvas.save()
        canvas.concat(matrix)
        canvas.translate(-w / 2f, -h / 2f)
        drawContent(canvas, w, h, skipEffects = skipEffects)
        canvas.restore()
    }

    private fun drawWarped(canvas: Canvas, w: Float, h: Float, rows: Int, cols: Int, mesh: FloatArray, qualityScale: Float = 1.0f, skipEffects: Boolean = false) {
        val pad = calculatePadding()
        val bmpW = ceil((w + pad * 2) * qualityScale).toInt()
        val bmpH = ceil((h + pad * 2) * qualityScale).toInt()

        if (bmpW > 0 && bmpH > 0) {
            val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val c = Canvas(bitmap)
            c.scale(qualityScale, qualityScale)
            c.translate(pad, pad)
            drawContent(c, w, h, skipEffects = skipEffects)

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

    private fun drawContent(canvas: Canvas, w: Float, h: Float, skipEffects: Boolean = false) {
        val gradientShader = getGradientShader(w, h)
        silhouetteColor = null
        var isDrawingShadowPass = false

        val drawMain = { targetCanvas: Canvas ->
            commonPaint.reset()
            commonPaint.isAntiAlias = true

            // 0. Triple Stroke
            if (tripleStrokeWidth > 0f && doubleStrokeWidth > 0f && strokeWidth > 0f) {
                val colorToUse = (silhouetteColor ?: tripleStrokeColor)
                renderSvgManipulated(targetCanvas, fill = null, stroke = colorToUse, strokeW = strokeWidth + doubleStrokeWidth * 2 + tripleStrokeWidth * 2)
            }

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
                val hasMultiGradient = currentEffect == TextEffectType.MULTI_GRADIENT || secondaryEffect == TextEffectType.MULTI_GRADIENT || tertiaryEffect == TextEffectType.MULTI_GRADIENT
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
                val maxBlur = motionShadowThickness
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
        if (tertiaryEffect != TextEffectType.NONE && tertiaryEffect != TextEffectType.MULTI_GRADIENT) activeEffects.add(tertiaryEffect)

        val hasEffects = activeEffects.isNotEmpty() && !skipEffects
        if (hasEffects) {
            fun renderChain(index: Int, targetCanvas: Canvas) {
                if (index < 0) {
                    drawBase(targetCanvas)
                } else {
                    applyEffect(activeEffects[index], targetCanvas, w, h, bounds = null) { innerCanvas ->
                        renderChain(index - 1, innerCanvas)
                    }
                }
            }
            renderChain(activeEffects.size - 1, canvas)
        } else {
            drawBase(canvas)
        }

        val pad = calculatePadding()
        if (eraseMask != null) {
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
            canvas.drawBitmap(eraseMask!!, -pad, -pad, maskPaint)
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
            canvas.save()
            canvas.translate(-pad, -pad)
            canvas.drawPath(activeErasePath!!, p)
            canvas.restore()
        }
    }

    private fun applyEffect(effect: TextEffectType, targetCanvas: Canvas, w: Float, h: Float, bounds: RectF? = null, drawInner: (Canvas) -> Unit) {
        val pad = calculatePadding()
        val hasBounds = bounds != null
        val nodeW = if (hasBounds) bounds!!.width().toInt().coerceAtLeast(1) else (w + pad * 2).toInt().coerceAtLeast(1)
        val nodeH = if (hasBounds) bounds!!.height().toInt().coerceAtLeast(1) else (h + pad * 2).toInt().coerceAtLeast(1)

        val recordTranslateX = if (hasBounds) -bounds!!.left else pad
        val recordTranslateY = if (hasBounds) -bounds!!.top else pad

        val drawTranslateX = if (hasBounds) bounds!!.left else -pad
        val drawTranslateY = if (hasBounds) bounds!!.top else -pad

        when (effect) {
                TextEffectType.GLITCH_2 -> {
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("Glitch2Node")
                            node.setPosition(0, 0, nodeW, nodeH)

                            val recordingCanvas = node.beginRecording()
                            recordingCanvas.translate(recordTranslateX, recordTranslateY)
                            drawInner(recordingCanvas)
                            node.endRecording()

                            val shader = android.graphics.RuntimeShader(TextLayer.GLITCH_2_SHADER)
                            shader.setFloatUniform("imageSize", nodeW.toFloat(), nodeH.toFloat())
                            shader.setFloatUniform("time", (System.currentTimeMillis() % 100000) / 1000f)
                            shader.setFloatUniform("intensity", glitch2Intensity)
                            shader.setFloatUniform("realRandom", (glitch2Seed % 10000).toFloat() / 10000f)
                            shader.setFloatUniform("slices", glitch2Slices.coerceAtLeast(1f))
                            shader.setFloatUniform("noiseIntensity", glitch2NoiseIntensity)
                            shader.setFloatUniform("colorBarsEnabled", 1f)
                            shader.setFloatUniform("rgbSplitIntensity", glitch2RgbSplitIntensity)

                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.save()
                            targetCanvas.translate(drawTranslateX, drawTranslateY)
                            targetCanvas.drawRenderNode(node)
                            targetCanvas.restore()
                            useRenderEffect = true
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    if (!useRenderEffect) {
                        drawGlitch2Software(targetCanvas, w, h, drawInner)
                    }
                }
                TextEffectType.CHROMATIC_ABERRATION -> {
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("ChromaticNode")
                            node.setPosition(0, 0, nodeW, nodeH)

                            val recordingCanvas = node.beginRecording()
                            recordingCanvas.translate(recordTranslateX, recordTranslateY)
                            drawInner(recordingCanvas)
                            node.endRecording()

                            val shader = android.graphics.RuntimeShader(TextLayer.CHROMATIC_ABERRATION_SHADER)
                            val angleRad = Math.toRadians(chromaticAngle.toDouble())
                            val dx = (chromaticShift * Math.cos(angleRad)).toFloat()
                            val dy = (chromaticShift * Math.sin(angleRad)).toFloat()
                            shader.setFloatUniform("offset", dx, dy)

                            val rL = Color.red(chromaticColors[0]) / 255f
                            val gL = Color.green(chromaticColors[0]) / 255f
                            val bL = Color.blue(chromaticColors[0]) / 255f
                            shader.setFloatUniform("colorL", rL, gL, bL)

                            val rR = Color.red(chromaticColors[1]) / 255f
                            val gR = Color.green(chromaticColors[1]) / 255f
                            val bR = Color.blue(chromaticColors[1]) / 255f
                            shader.setFloatUniform("colorR", rR, gR, bR)

                            val rC = Color.red(chromaticColors[2]) / 255f
                            val gC = Color.green(chromaticColors[2]) / 255f
                            val bC = Color.blue(chromaticColors[2]) / 255f
                            shader.setFloatUniform("colorC", rC, gC, bC)

                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.save()
                            targetCanvas.translate(drawTranslateX, drawTranslateY)
                            targetCanvas.drawRenderNode(node)
                            targetCanvas.restore()
                            useRenderEffect = true
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    if (!useRenderEffect) {
                        val angleRad = Math.toRadians(chromaticAngle.toDouble())
                        val dx = (chromaticShift * Math.cos(angleRad)).toFloat()
                        val dy = (chromaticShift * Math.sin(angleRad)).toFloat()

                        val bmpW = nodeW
                        val bmpH = nodeH
                        if (bmpW > 0 && bmpH > 0) {
                            val srcBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                            val srcCanvas = Canvas(srcBmp)
                            srcCanvas.translate(recordTranslateX, recordTranslateY)
                            drawInner(srcCanvas)

                            val outBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                            val pixels = IntArray(bmpW * bmpH)
                            srcBmp.getPixels(pixels, 0, bmpW, 0, 0, bmpW, bmpH)

                            val outPixels = IntArray(bmpW * bmpH)

                            val rL = Color.red(chromaticColors[0]) / 255f
                            val gL = Color.green(chromaticColors[0]) / 255f
                            val bL = Color.blue(chromaticColors[0]) / 255f

                            val rR = Color.red(chromaticColors[1]) / 255f
                            val gR = Color.green(chromaticColors[1]) / 255f
                            val bR = Color.blue(chromaticColors[1]) / 255f

                            val rC = Color.red(chromaticColors[2]) / 255f
                            val gC = Color.green(chromaticColors[2]) / 255f
                            val bC = Color.blue(chromaticColors[2]) / 255f

                            for (y in 0 until bmpH) {
                                for (x in 0 until bmpW) {
                                    val xL = Math.round(x - dx)
                                    val yL = Math.round(y - dy)
                                    val hasL = xL in 0 until bmpW && yL in 0 until bmpH
                                    val colorL = if (hasL) pixels[yL * bmpW + xL] else 0
                                    val aL = if (hasL) Color.alpha(colorL) / 255f else 0f

                                    val xR = Math.round(x + dx)
                                    val yR = Math.round(y + dy)
                                    val hasR = xR in 0 until bmpW && yR in 0 until bmpH
                                    val colorR = if (hasR) pixels[yR * bmpW + xR] else 0
                                    val aR = if (hasR) Color.alpha(colorR) / 255f else 0f

                                    val colorC = pixels[y * bmpW + x]
                                    val aC = Color.alpha(colorC) / 255f

                                    if (aL == 0f && aR == 0f && aC == 0f) {
                                        continue
                                    }

                                    val tL_r = 1f - aL * (1f - rL)
                                    val tL_g = 1f - aL * (1f - gL)
                                    val tL_b = 1f - aL * (1f - bL)

                                    val tR_r = 1f - aR * (1f - rR)
                                    val tR_g = 1f - aR * (1f - gR)
                                    val tR_b = 1f - aR * (1f - bR)

                                    val tC_r = 1f - aC * (1f - rC)
                                    val tC_g = 1f - aC * (1f - gC)
                                    val tC_b = 1f - aC * (1f - bC)

                                    val cSub_r = tL_r * tR_r * tC_r
                                    val cSub_g = tL_g * tR_g * tC_g
                                    val cSub_b = tL_b * tR_b * tC_b

                                    val wTriple = aL * aR * aC

                                    val base_r = Color.red(colorC) / 255f
                                    val base_g = Color.green(colorC) / 255f
                                    val base_b = Color.blue(colorC) / 255f

                                    val final_r = cSub_r * (1f - wTriple) + base_r * wTriple
                                    val final_g = cSub_g * (1f - wTriple) + base_g * wTriple
                                    val final_b = cSub_b * (1f - wTriple) + base_b * wTriple

                                    val finalAlpha = Math.max(aL, Math.max(aR, aC))

                                    val outColor = Color.argb(
                                        (finalAlpha * 255f).toInt().coerceIn(0, 255),
                                        (final_r * 255f).toInt().coerceIn(0, 255),
                                        (final_g * 255f).toInt().coerceIn(0, 255),
                                        (final_b * 255f).toInt().coerceIn(0, 255)
                                    )
                                    outPixels[y * bmpW + x] = outColor
                                }
                            }

                            outBmp.setPixels(outPixels, 0, bmpW, 0, 0, bmpW, bmpH)

                            targetCanvas.save()
                            targetCanvas.translate(drawTranslateX, drawTranslateY)
                            targetCanvas.drawBitmap(outBmp, 0f, 0f, null)
                            targetCanvas.restore()

                            srcBmp.recycle()
                            outBmp.recycle()
                        } else {
                            drawInner(targetCanvas)
                        }
                    }
                }
                TextEffectType.PIXELATION -> {
                    val safeBlockSize = pixelBlockSize.coerceAtLeast(1f)
                    val scaleFactor = 1f / safeBlockSize
                    val scaledW = (nodeW * scaleFactor).toInt().coerceAtLeast(1)
                    val scaledH = (nodeH * scaleFactor).toInt().coerceAtLeast(1)
                    val currentHash = listOf(shapeName, w, h, color, safeBlockSize, strokeWidth, strokeColor, doubleStrokeWidth, doubleStrokeColor, tripleStrokeWidth, tripleStrokeColor, currentEffect, secondaryEffect, pad, bounds).hashCode()
                    if (cachedPixelBitmap == null || cachedPixelBitmap!!.width != scaledW || cachedPixelBitmap!!.height != scaledH || cachedPixelHash != currentHash) {
                        cachedPixelBitmap?.recycle()
                        val tempBitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
                        val tempCanvas = Canvas(tempBitmap)
                        tempCanvas.scale(scaleFactor, scaleFactor)
                        tempCanvas.translate(recordTranslateX, recordTranslateY)
                        drawInner(tempCanvas)
                        cachedPixelBitmap = tempBitmap
                        cachedPixelHash = currentHash
                    }
                    if (cachedPixelBitmap != null && !cachedPixelBitmap!!.isRecycled) {
                        val pixelPaint = Paint().apply { isFilterBitmap = false }
                        targetCanvas.drawBitmap(cachedPixelBitmap!!, null, RectF(drawTranslateX, drawTranslateY, drawTranslateX + nodeW, drawTranslateY + nodeH), pixelPaint)
                    }
                }
                TextEffectType.GLITCH -> {
                    val random = Random(glitchSeed)
                    val currentYStart = if (hasBounds) bounds!!.top else -pad
                    val currentYEnd = if (hasBounds) bounds!!.bottom else h + pad
                    val currentXStart = if (hasBounds) bounds!!.left else -pad
                    val currentXEnd = if (hasBounds) bounds!!.right else w + pad

                    var currentY = currentYStart
                    val maxStripHeight = (currentYEnd - currentYStart) * 0.15f
                    val minStripHeight = (currentYEnd - currentYStart) * 0.02f

                    while (currentY < currentYEnd) {
                        var stripHeight = minStripHeight + (random.nextFloat() * (maxStripHeight - minStripHeight))
                        if (stripHeight < 1f) stripHeight = 1f
                        val bottom = kotlin.math.min(currentY + stripHeight, currentYEnd)
                        val xOffset = if (random.nextFloat() < 0.5f) (random.nextFloat() - 0.5f) * 100f * glitchIntensity else 0f
                        targetCanvas.save()
                        targetCanvas.clipRect(currentXStart, currentY, currentXEnd, bottom)
                        targetCanvas.translate(xOffset, 0f)
                        drawInner(targetCanvas)
                        targetCanvas.restore()
                        if (bottom <= currentY) break
                        currentY = bottom
                    }
                }
                TextEffectType.NEON -> {
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("NeonGlowNode")
                            node.setPosition(0, 0, nodeW, nodeH)

                            val recordingCanvas = node.beginRecording()
                            recordingCanvas.translate(recordTranslateX, recordTranslateY)
                            drawInner(recordingCanvas)
                            node.endRecording()

                            val shader = android.graphics.RuntimeShader(TextLayer.NEON_GLOW_SHADER)
                            shader.setFloatUniform("outerStrength", neonOuterStrength)
                            shader.setFloatUniform("innerStrength", neonInnerStrength)
                            val r = Color.red(neonColor) / 255f
                            val g = Color.green(neonColor) / 255f
                            val b = Color.blue(neonColor) / 255f
                            val a = Color.alpha(neonColor) / 255f
                            shader.setFloatUniform("glowColor", r, g, b, a)
                            shader.setFloatUniform("glowDistance", neonRadius.coerceAtLeast(1f))
                            shader.setFloatUniform("quality", neonQuality.coerceIn(0.01f, 1.0f))
                            shader.setIntUniform("knockout", if (neonKnockout) 1 else 0)
                            shader.setFloatUniform("alpha", neonAlpha)

                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.save()
                            targetCanvas.translate(drawTranslateX, drawTranslateY)
                            targetCanvas.drawRenderNode(node)
                            targetCanvas.restore()
                            useRenderEffect = true
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    if (!useRenderEffect) {
                        val prevSilhouette = silhouetteColor
                        silhouetteColor = if (neonColor != Color.CYAN) neonColor else color
                        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(neonRadius.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL) }
                        targetCanvas.saveLayer(null, p)
                        drawInner(targetCanvas)
                        targetCanvas.restore()
                        silhouetteColor = prevSilhouette
                        drawInner(targetCanvas)
                    }
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
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("GaussianBlurNode")
                            node.setPosition(0, 0, nodeW, nodeH)
                            val rc = node.beginRecording(); rc.translate(recordTranslateX, recordTranslateY); drawInner(rc); node.endRecording()
                            val r = blurRadius.coerceAtLeast(0.1f)
                            node.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
                            targetCanvas.save(); targetCanvas.translate(drawTranslateX, drawTranslateY); targetCanvas.drawRenderNode(node); targetCanvas.restore()
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
                TextEffectType.FIERY -> {
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("FieryNode")
                            node.setPosition(0, 0, nodeW, nodeH)
                            val rc = node.beginRecording(); rc.translate(recordTranslateX, recordTranslateY); drawInner(rc); node.endRecording()
                            val shader = android.graphics.RuntimeShader(TextLayer.FIERY_SHADER)
                            shader.setFloatUniform("time", (System.currentTimeMillis() % 100000) / 1000f)
                            shader.setFloatUniform("intensity", fieryIntensity)
                            shader.setFloatUniform("color", Color.red(fieryColor)/255f, Color.green(fieryColor)/255f, Color.blue(fieryColor)/255f)

                            targetCanvas.save()
                            targetCanvas.translate(drawTranslateX, drawTranslateY)
                            val pts = floatArrayOf(0f, 0f)
                            targetCanvas.matrix.mapPoints(pts)
                            shader.setFloatUniform("offsetX", pts[0])
                            shader.setFloatUniform("offsetY", pts[1])

                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.drawRenderNode(node)
                            targetCanvas.restore()
                            useRenderEffect = true
                        } catch (e: Exception) {}
                    }
                    if (!useRenderEffect) drawInner(targetCanvas)
                }
                TextEffectType.WAVY -> {
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("WavyNode")
                            node.setPosition(0, 0, nodeW, nodeH)
                            val rc = node.beginRecording(); rc.translate(recordTranslateX, recordTranslateY); drawInner(rc); node.endRecording()
                            val shader = android.graphics.RuntimeShader(TextLayer.WAVY_SHADER)
                            shader.setFloatUniform("time", (System.currentTimeMillis() % 100000) / 1000f)
                            shader.setFloatUniform("intensity", wavyIntensity); shader.setFloatUniform("frequency", wavyFrequency)

                            targetCanvas.save()
                            targetCanvas.translate(drawTranslateX, drawTranslateY)
                            val pts = floatArrayOf(0f, 0f)
                            targetCanvas.matrix.mapPoints(pts)
                            shader.setFloatUniform("offsetY", pts[1])

                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.drawRenderNode(node)
                            targetCanvas.restore()
                            useRenderEffect = true
                        } catch (e: Exception) {}
                    }
                    if (!useRenderEffect) drawInner(targetCanvas)
                }
                TextEffectType.PARTICLE_DISSOLVE -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("ParticleNode")
                            node.setPosition(0, 0, nodeW, nodeH)
                            val rc = node.beginRecording(); rc.translate(recordTranslateX, recordTranslateY); drawInner(rc); node.endRecording()
                            val shader = android.graphics.RuntimeShader(TextLayer.PARTICLE_SHADER)
                            shader.setFloatUniform("particleSize", particleSize); shader.setFloatUniform("spread", particleSpread); shader.setFloatUniform("seed", effectSeed.toFloat())
                            shader.setFloatUniform("angle", particleDissolveAngle); shader.setFloatUniform("size", nodeW.toFloat(), nodeH.toFloat())
                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.save(); targetCanvas.translate(drawTranslateX, drawTranslateY); targetCanvas.drawRenderNode(node); targetCanvas.restore()
                        } catch (e: Exception) {
                            drawDecaySoftware(targetCanvas, w, h, drawInner)
                        }
                    } else {
                        drawDecaySoftware(targetCanvas, w, h, drawInner)
                    }
                }
                TextEffectType.MOTION_BLUR -> {
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("MotionBlurNode")
                            node.setPosition(0, 0, nodeW, nodeH)
                            val rc = node.beginRecording(); rc.translate(recordTranslateX, recordTranslateY); drawInner(rc); node.endRecording()
                            val shader = android.graphics.RuntimeShader(TextLayer.MOTION_BLUR_SHADER)
                            shader.setFloatUniform("uVelocity", motionBlurVelocityX, motionBlurVelocityY)
                            shader.setIntUniform("uKernelSize", motionBlurKernelSize)
                            shader.setFloatUniform("uOffset", motionBlurOffset)
                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.save(); targetCanvas.translate(drawTranslateX, drawTranslateY); targetCanvas.drawRenderNode(node); targetCanvas.restore()
                            useRenderEffect = true
                        } catch (e: Exception) {}
                    }
                    if (!useRenderEffect) drawInner(targetCanvas)
                }
                TextEffectType.RADIAL_BLUR -> {
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("RadialBlurNode")
                            node.setPosition(0, 0, nodeW, nodeH)
                            val rc = node.beginRecording(); rc.translate(recordTranslateX, recordTranslateY); drawInner(rc); node.endRecording()
                            val shader = android.graphics.RuntimeShader(TextLayer.RADIAL_BLUR_SHADER)
                            val centerX = (if (hasBounds) nodeW.toFloat() else (w + pad * 2)) * radialBlurCenterX
                            val centerY = (if (hasBounds) nodeH.toFloat() else (h + pad * 2)) * radialBlurCenterY
                            shader.setFloatUniform("center", centerX, centerY); shader.setFloatUniform("innerRadius", radialBlurInnerRadius)
                            shader.setFloatUniform("motionStrength", radialBlurMotionStrength); shader.setFloatUniform("size", nodeW.toFloat(), nodeH.toFloat())
                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.save(); targetCanvas.translate(drawTranslateX, drawTranslateY); targetCanvas.drawRenderNode(node); targetCanvas.restore()
                            useRenderEffect = true
                        } catch (e: Exception) {}
                    }
                    if (!useRenderEffect) drawInner(targetCanvas)
                }
                TextEffectType.HALFTONE -> {
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("HalftoneNode")
                            node.setPosition(0, 0, nodeW, nodeH)

                            val recordingCanvas = node.beginRecording()
                            recordingCanvas.translate(recordTranslateX, recordTranslateY)
                            drawInner(recordingCanvas)
                            node.endRecording()

                            val shader = android.graphics.RuntimeShader(TextLayer.HALFTONE_SHADER)
                            shader.setFloatUniform("threshold", halftoneThreshold)
                            val r = Color.red(halftoneDotColor) / 255f
                            val g = Color.green(halftoneDotColor) / 255f
                            val b = Color.blue(halftoneDotColor) / 255f
                            shader.setFloatUniform("dotColor", r, g, b)
                            shader.setFloatUniform("halftoneType", if (halftoneType == "OUTER") 1f else 0f)
                            shader.setFloatUniform("alpha", halftoneAlpha)
                            shader.setFloatUniform("range", halftoneRange)
                            shader.setFloatUniform("density", halftoneDensity.coerceAtLeast(1f))
                            shader.setFloatUniform("fadingIntensity", halftoneFadingIntensity)
                            val shapeVal = when (halftoneShape) {
                                "SQUARE" -> 1f
                                "LINE" -> 2f
                                else -> 0f // "DOT"
                            }
                            shader.setFloatUniform("shapeType", shapeVal)

                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.save()
                            targetCanvas.translate(drawTranslateX, drawTranslateY)
                            targetCanvas.drawRenderNode(node)
                            targetCanvas.restore()
                            useRenderEffect = true
                        } catch (e: Exception) {}
                    }

                    if (!useRenderEffect) {
                        val bmpW = nodeW
                        val bmpH = nodeH
                        if (bmpW > 0 && bmpH > 0) {
                            val srcBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                            val srcCanvas = Canvas(srcBmp)
                            srcCanvas.translate(recordTranslateX, recordTranslateY)
                            drawInner(srcCanvas)

                            val outBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                            val pixels = IntArray(bmpW * bmpH)
                            srcBmp.getPixels(pixels, 0, bmpW, 0, 0, bmpW, bmpH)
                            val outPixels = IntArray(bmpW * bmpH)

                            val gridSpacing = Math.max(1f, 100f / halftoneDensity)
                            val threshold = halftoneThreshold
                            val r = Color.red(halftoneDotColor)
                            val g = Color.green(halftoneDotColor)
                            val b = Color.blue(halftoneDotColor)

                            for (y in 0 until bmpH) {
                                for (x in 0 until bmpW) {
                                    val cellX = Math.floor(x / gridSpacing.toDouble()).toFloat()
                                    val cellY = Math.floor(y / gridSpacing.toDouble()).toFloat()
                                    val centerX = (cellX + 0.5f) * gridSpacing
                                    val centerY = (cellY + 0.5f) * gridSpacing
                                    val dist = Math.hypot((x - centerX).toDouble(), (y - centerY).toDouble()).toFloat()

                                    val originalPixel = pixels[y * bmpW + x]
                                    val originalAlpha = (originalPixel ushr 24) / 255f

                                    if (halftoneType == "INNER") {
                                        if (originalAlpha == 0f) continue
                                        val radius = gridSpacing * 0.5f * threshold * originalAlpha
                                        val insideShape = when (halftoneShape) {
                                            "SQUARE" -> {
                                                val sqDist = Math.max(Math.abs(x - centerX), Math.abs(y - centerY))
                                                sqDist < radius
                                            }
                                            "LINE" -> {
                                                val lineDist = Math.abs((x - centerX) - (y - centerY)) * 0.707106f
                                                lineDist < radius
                                            }
                                            else -> { // "DOT"
                                                dist < radius
                                            }
                                        }
                                        if (insideShape) {
                                            val finalAlpha = (halftoneAlpha * originalAlpha).coerceIn(0f, 1f)
                                            val outA = (finalAlpha * 255f).toInt()
                                            val outR = (r * finalAlpha).toInt()
                                            val outG = (g * finalAlpha).toInt()
                                            val outB = (b * finalAlpha).toInt()
                                            outPixels[y * bmpW + x] = (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
                                        }
                                    } else { // "OUTER"
                                        val range = halftoneRange
                                        if (range <= 0f) {
                                            outPixels[y * bmpW + x] = originalPixel
                                            continue
                                        }

                                        // Find nearest shape pixel using a multi-directional search in 12 directions, 3 steps each
                                        var minDist = range
                                        val dirSteps = 12
                                        val stepCount = 3
                                        for (d in 0 until dirSteps) {
                                            val angle = d * (2.0 * Math.PI / dirSteps)
                                            val cosA = Math.cos(angle)
                                            val sinA = Math.sin(angle)
                                            for (s in 1..stepCount) {
                                                val stepDist = (s.toFloat() / stepCount) * range
                                                val sx = Math.round(x - cosA * stepDist).toInt()
                                                val sy = Math.round(y - sinA * stepDist).toInt()
                                                if (sx in 0 until bmpW && sy in 0 until bmpH) {
                                                    val samplePixel = pixels[sy * bmpW + sx]
                                                    val sampleAlpha = (samplePixel ushr 24) / 255f
                                                    if (sampleAlpha > 0f) {
                                                        if (stepDist < minDist) {
                                                            minDist = stepDist
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        if (minDist < range) {
                                            val closeness = 1f - (minDist / range)
                                            val fadeWeight = if (halftoneFadingIntensity > 0f) {
                                                Math.pow(closeness.toDouble(), halftoneFadingIntensity.toDouble()).toFloat()
                                            } else {
                                                1f
                                            }

                                            val radius = gridSpacing * 0.5f * threshold * fadeWeight
                                            val insideShape = when (halftoneShape) {
                                                "SQUARE" -> {
                                                    val sqDist = Math.max(Math.abs(x - centerX), Math.abs(y - centerY))
                                                    sqDist < radius
                                                }
                                                "LINE" -> {
                                                    val lineDist = Math.abs((x - centerX) - (y - centerY)) * 0.707106f
                                                    lineDist < radius
                                                }
                                                else -> { // "DOT"
                                                    dist < radius
                                                }
                                            }

                                            if (insideShape) {
                                                val shadowAlpha = (halftoneAlpha * fadeWeight).coerceIn(0f, 1f)
                                                // Blend original text on top of halftone shadow
                                                val outA = (shadowAlpha + originalAlpha * (1f - shadowAlpha)).coerceIn(0f, 1f)
                                                if (outA > 0f) {
                                                    // Blend color: original color on top of shadow color
                                                    val origR = Color.red(originalPixel)
                                                    val origG = Color.green(originalPixel)
                                                    val origB = Color.blue(originalPixel)

                                                    val blendedR = (origR * originalAlpha + r * shadowAlpha * (1f - originalAlpha)) / outA
                                                    val blendedG = (origG * originalAlpha + g * shadowAlpha * (1f - originalAlpha)) / outA
                                                    val blendedB = (origB * originalAlpha + b * shadowAlpha * (1f - originalAlpha)) / outA

                                                    outPixels[y * bmpW + x] = ((outA * 255f).toInt() shl 24) or
                                                            (blendedR.toInt().coerceIn(0, 255) shl 16) or
                                                            (blendedG.toInt().coerceIn(0, 255) shl 8) or
                                                            blendedB.toInt().coerceIn(0, 255)
                                                }
                                            } else {
                                                // No shadow pixel, just use original
                                                outPixels[y * bmpW + x] = originalPixel
                                            }
                                        } else {
                                            outPixels[y * bmpW + x] = originalPixel
                                        }
                                    }
                                }
                            }

                            outBmp.setPixels(outPixels, 0, bmpW, 0, 0, bmpW, bmpH)
                            targetCanvas.save()
                            targetCanvas.translate(drawTranslateX, drawTranslateY)
                            targetCanvas.drawBitmap(outBmp, 0f, 0f, null)
                            targetCanvas.restore()
                            srcBmp.recycle()
                            outBmp.recycle()
                        }
                    }
                }
                TextEffectType.TEXT_DECAY -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("DecayNode")
                            node.setPosition(0, 0, nodeW, nodeH)
                            val rc = node.beginRecording(); rc.translate(recordTranslateX, recordTranslateY); drawInner(rc); node.endRecording()
                            val shader = android.graphics.RuntimeShader(TextLayer.TEXT_DECAY_SHADER)
                            shader.setFloatUniform("intensity", decayIntensity)
                            shader.setFloatUniform("fadingLevel", decayFadingLevel)
                            shader.setFloatUniform("seed", (decaySeed % 10000).toFloat())
                            shader.setFloatUniform("size", w, h)
                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.save(); targetCanvas.translate(drawTranslateX, drawTranslateY); targetCanvas.drawRenderNode(node); targetCanvas.restore()
                        } catch (e: Exception) { drawInner(targetCanvas) }
                    } else drawInner(targetCanvas)
                }
                TextEffectType.TWIST -> {
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("TwistNode")
                            node.setPosition(0, 0, nodeW, nodeH)

                            val recordingCanvas = node.beginRecording()
                            recordingCanvas.translate(recordTranslateX, recordTranslateY)
                            drawInner(recordingCanvas)
                            node.endRecording()

                            val shader = android.graphics.RuntimeShader(TextLayer.TWIST_SHADER)
                            val cx = (if (hasBounds) nodeW / 2f else w / 2f + pad) + twistOffsetX
                            val cy = (if (hasBounds) nodeH / 2f else h / 2f + pad) + twistOffsetY
                            shader.setFloatUniform("offset", cx, cy)
                            shader.setFloatUniform("radius", twistRadius)
                            shader.setFloatUniform("angle", twistAngle)

                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.save()
                            targetCanvas.translate(drawTranslateX, drawTranslateY)
                            targetCanvas.drawRenderNode(node)
                            targetCanvas.restore()
                            useRenderEffect = true
                        } catch (e: Exception) {}
                    }
                    if (!useRenderEffect) {
                        drawInner(targetCanvas)
                    }
                }
                TextEffectType.BULGE_PINCH -> {
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("BulgePinchNode")
                            node.setPosition(0, 0, nodeW, nodeH)

                            val recordingCanvas = node.beginRecording()
                            recordingCanvas.translate(recordTranslateX, recordTranslateY)
                            drawInner(recordingCanvas)
                            node.endRecording()

                            val shader = android.graphics.RuntimeShader(TextLayer.BULGE_PINCH_SHADER)
                            val cx = (if (hasBounds) nodeW.toFloat() else (w + pad * 2)) * bulgeCenterX
                            val cy = (if (hasBounds) nodeH.toFloat() else (h + pad * 2)) * bulgeCenterY
                            shader.setFloatUniform("center", cx, cy)
                            shader.setFloatUniform("radius", bulgeRadius)
                            shader.setFloatUniform("strength", bulgeStrength)

                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.save()
                            targetCanvas.translate(drawTranslateX, drawTranslateY)
                            targetCanvas.drawRenderNode(node)
                            targetCanvas.restore()
                            useRenderEffect = true
                        } catch (e: Exception) {}
                    }
                    if (!useRenderEffect) {
                        drawInner(targetCanvas)
                    }
                }
                TextEffectType.REFLECTION -> {
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("ReflectionNode")
                            node.setPosition(0, 0, nodeW, nodeH)

                            val recordingCanvas = node.beginRecording()
                            recordingCanvas.translate(recordTranslateX, recordTranslateY)
                            drawInner(recordingCanvas)
                            node.endRecording()

                            val shader = android.graphics.RuntimeShader(TextLayer.REFLECTION_SHADER)
                            shader.setFloatUniform("size", nodeW.toFloat(), nodeH.toFloat())
                            shader.setFloatUniform("mirror", if (reflectionMirror) 1.0f else 0.0f)
                            shader.setFloatUniform("boundary", reflectionBoundary)
                            shader.setFloatUniform("amplitude", reflectionAmplitudeStart, reflectionAmplitudeEnd)
                            shader.setFloatUniform("waveLength", reflectionWavelengthStart, reflectionWavelengthEnd)
                            shader.setFloatUniform("alpha", reflectionAlphaStart, reflectionAlphaEnd)
                            shader.setFloatUniform("time", reflectionTime)

                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.save()
                            targetCanvas.translate(drawTranslateX, drawTranslateY)
                            targetCanvas.drawRenderNode(node)
                            targetCanvas.restore()
                            useRenderEffect = true
                        } catch (e: Exception) {}
                    }
                    if (!useRenderEffect) {
                        drawInner(targetCanvas)
                    }
                }
                TextEffectType.ZOOM_BLUR -> {
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("ZoomBlurNode")
                            node.setPosition(0, 0, nodeW, nodeH)

                            val recordingCanvas = node.beginRecording()
                            recordingCanvas.translate(recordTranslateX, recordTranslateY)
                            drawInner(recordingCanvas)
                            node.endRecording()

                            val shader = android.graphics.RuntimeShader(TextLayer.ZOOM_BLUR_SHADER)
                            val cx = (if (hasBounds) nodeW.toFloat() else (w + pad * 2)) * zoomBlurCenterX
                            val cy = (if (hasBounds) nodeH.toFloat() else (h + pad * 2)) * zoomBlurCenterY
                            shader.setFloatUniform("center", cx, cy)
                            shader.setFloatUniform("strength", zoomBlurStrength)
                            shader.setFloatUniform("innerRadius", zoomBlurInnerRadius)
                            shader.setFloatUniform("radius", zoomBlurRadius)

                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.save()
                            targetCanvas.translate(drawTranslateX, drawTranslateY)
                            targetCanvas.drawRenderNode(node)
                            targetCanvas.restore()
                            useRenderEffect = true
                        } catch (e: Exception) {}
                    }
                    if (!useRenderEffect) {
                        drawInner(targetCanvas)
                    }
                }
                else -> drawInner(targetCanvas)
             }
    }

    private fun drawDecaySoftware(targetCanvas: Canvas, w: Float, h: Float, drawInner: (Canvas) -> Unit) {
        val pad = calculatePadding()
        val bmpW = ceil(w + pad * 2).toInt()
        val bmpH = ceil(h + pad * 2).toInt()
        if (bmpW <= 0 || bmpH <= 0) { drawInner(targetCanvas); return }

        val srcBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val srcCanvas = Canvas(srcBmp); srcCanvas.translate(pad, pad); drawInner(srcCanvas)

        val random = Random(decaySeed)
        fun generateNoise(scale: Float): Bitmap {
            val nW = (bmpW * scale).toInt().coerceAtLeast(1)
            val nH = (bmpH * scale).toInt().coerceAtLeast(1)
            val b = Bitmap.createBitmap(nW, nH, Bitmap.Config.ARGB_8888)
            for (y in 0 until nH) for (x in 0 until nW) {
                val n = random.nextInt(256); b.setPixel(x, y, Color.rgb(n, n, n))
            }
            val s = Bitmap.createScaledBitmap(b, bmpW, bmpH, true); b.recycle(); return s
        }

        val noise1 = generateNoise(0.1f); val noise2 = generateNoise(0.2f); val noise3 = generateNoise(0.4f)
        val pixels = IntArray(bmpW * bmpH); val n1 = IntArray(bmpW * bmpH); val n2 = IntArray(bmpW * bmpH); val n3 = IntArray(bmpW * bmpH)

        srcBmp.getPixels(pixels, 0, bmpW, 0, 0, bmpW, bmpH)
        noise1.getPixels(n1, 0, bmpW, 0, 0, bmpW, bmpH); noise2.getPixels(n2, 0, bmpW, 0, 0, bmpW, bmpH); noise3.getPixels(n3, 0, bmpW, 0, 0, bmpW, bmpH)

        val threshold = 1.1f - (decayIntensity * 1.1f)
        val softness = decayFadingLevel * 0.4f + 0.01f

        fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        for (i in pixels.indices) {
            val color = pixels[i]; val alpha = Color.alpha(color) / 255f
            if (alpha <= 0f) continue
            var n = (Color.red(n1[i]) / 255f); n += (Color.red(n2[i]) / 255f) * 0.5f; n += (Color.red(n3[i]) / 255f) * 0.25f; n /= 1.75f
            val valCombined = n + (1.0f - alpha) * 0.5f
            val mask = smoothstep(threshold - softness, threshold + softness, valCombined)
            pixels[i] = (color and 0x00FFFFFF) or ((alpha * (1.0f - mask) * 255f).toInt().coerceIn(0, 255) shl 24)
        }
        srcBmp.setPixels(pixels, 0, bmpW, 0, 0, bmpW, bmpH)
        targetCanvas.save(); targetCanvas.translate(-pad, -pad); targetCanvas.drawBitmap(srcBmp, 0f, 0f, null); targetCanvas.restore()
        srcBmp.recycle(); noise1.recycle(); noise2.recycle(); noise3.recycle()
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
            mSvg.documentWidth = getWidth()
            mSvg.documentHeight = getHeight()

            val alphaToUse = if (fill != null) Color.alpha(fill) else if (stroke != null) Color.alpha(stroke) else 255
            val layerPaint = if (alphaToUse < 255) Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = alphaToUse } else null

            if (fillShader != null || strokeShader != null) {
                // If shader is present, we render to a layer and apply shader via SRC_IN
                canvas.saveLayer(null, layerPaint)
                mSvg.renderToCanvas(canvas)

                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                if (fillShader != null) {
                    p.shader = fillShader
                    canvas.drawRect(0f, 0f, getWidth(), getHeight(), p)
                }
                canvas.restore()
            } else {
                if (layerPaint != null) {
                    canvas.saveLayer(null, layerPaint)
                    mSvg.renderToCanvas(canvas)
                    canvas.restore()
                } else {
                    mSvg.renderToCanvas(canvas)
                }
            }
        } catch (e: Exception) {}
    }

    override fun calculatePadding(): Float {
        var p = strokeWidth + doubleStrokeWidth + tripleStrokeWidth
        p = Math.max(p, shadowRadius + Math.max(Math.abs(shadowDx), Math.abs(shadowDy)))
        if (isMotionShadow) p = Math.max(p, motionShadowDistance + 20f)

        var effectExpansion = 0f
        val checkEffect = { effect: TextEffectType ->
            when(effect) {
                TextEffectType.GAUSSIAN_BLUR -> effectExpansion = Math.max(effectExpansion, blurRadius * 2.5f)
                TextEffectType.MOTION_BLUR -> {
                    val velLen = Math.hypot(motionBlurVelocityX.toDouble(), motionBlurVelocityY.toDouble()).toFloat()
                    val expansion = velLen + Math.abs(motionBlurOffset)
                    effectExpansion = Math.max(effectExpansion, expansion)
                }
                TextEffectType.NEON -> effectExpansion = Math.max(effectExpansion, neonRadius * 1.5f)
                TextEffectType.LONG_SHADOW -> effectExpansion = Math.max(effectExpansion, longShadowLength)
                TextEffectType.RADIAL_BLUR -> effectExpansion = Math.max(effectExpansion, 50f + radialBlurMotionStrength * 0.5f)
                TextEffectType.CHROMATIC_ABERRATION -> effectExpansion = Math.max(effectExpansion, chromaticShift)
                TextEffectType.GLITCH -> effectExpansion = Math.max(effectExpansion, 100f * glitchIntensity)
                TextEffectType.GLITCH_2 -> effectExpansion = Math.max(effectExpansion, 100f * glitch2Intensity)
                TextEffectType.FIERY -> effectExpansion = Math.max(effectExpansion, fieryIntensity * 50f + 30f)
                TextEffectType.WAVY -> effectExpansion = Math.max(effectExpansion, wavyIntensity * 50f + 20f)
                TextEffectType.ZOOM_BLUR -> effectExpansion = Math.max(effectExpansion, Math.max(getWidth(), getHeight()) * zoomBlurStrength * 1.5f + 100f)
                TextEffectType.REFLECTION -> effectExpansion = Math.max(effectExpansion, getHeight() * 1.5f + reflectionAmplitudeEnd)
                TextEffectType.TWIST -> effectExpansion = Math.max(effectExpansion, twistRadius * 0.5f)
                TextEffectType.BULGE_PINCH -> effectExpansion = Math.max(effectExpansion, bulgeRadius * 0.5f)
                TextEffectType.HALFTONE -> {
                    if (halftoneType == "OUTER") {
                        effectExpansion = Math.max(effectExpansion, halftoneRange + 20f)
                    }
                }
                else -> {}
            }
        }
        checkEffect(currentEffect)
        checkEffect(secondaryEffect)
        checkEffect(tertiaryEffect)

        return (p + effectExpansion + 20f).coerceAtLeast(0f)
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
                val (pStart, pMid, pEnd) = com.astral.typer.utils.GradationHelper.getSafePortions(hasMiddleColor, gradientStartPos, gradientMiddlePos, gradientEndPos)
                val sStart = pStart / 2f
                val sMid = pStart + pMid / 2f
                val sEnd = 1.0f - pEnd / 2f
                return if (hasMiddleColor) {
                    val sorted = listOf(
                        gradientStartColor to 0.0f,
                        gradientStartColor to sStart,
                        gradientMiddleColor to sMid,
                        gradientEndColor to sEnd,
                        gradientEndColor to 1.0f
                    ).sortedBy { it.second }
                    val colors = sorted.map { it.first }.toIntArray()
                    val positions = sorted.map { it.second.coerceIn(0f, 1f) }.toFloatArray()
                    LinearGradient(x0, y0, x1, y1, colors, positions, Shader.TileMode.CLAMP)
                } else {
                    val sorted = listOf(
                        gradientStartColor to 0.0f,
                        gradientStartColor to sStart,
                        gradientEndColor to sEnd,
                        gradientEndColor to 1.0f
                    ).sortedBy { it.second }
                    val colors = sorted.map { it.first }.toIntArray()
                    val positions = sorted.map { it.second.coerceIn(0f, 1f) }.toFloatArray()
                    LinearGradient(x0, y0, x1, y1, colors, positions, Shader.TileMode.CLAMP)
                }
            }
        }
        return createGradient(w, h, gradientAngle, gradientStartColor, gradientEndColor, hasMiddleColor, gradientMiddleColor, gradientStartPos, gradientMiddlePos, gradientEndPos)
    }

    private fun createGradient(
        w: Float, h: Float, angle: Int, startColor: Int, endColor: Int, hasMid: Boolean = false, midColor: Int = 0,
        startPos: Float = 0f, midPos: Float = 0.5f, endPos: Float = 1f
    ): Shader {
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
        val x0 = cx - halfLen * cos
        val y0 = cy - halfLen * sin
        val x1 = cx + halfLen * cos
        val y1 = cy + halfLen * sin

        val (pStart, pMid, pEnd) = com.astral.typer.utils.GradationHelper.getSafePortions(hasMid, startPos, midPos, endPos)
        val sStart = pStart / 2f
        val sMid = pStart + pMid / 2f
        val sEnd = 1.0f - pEnd / 2f
        val sorted = if (hasMid) {
            listOf(
                startColor to 0.0f,
                startColor to sStart,
                midColor to sMid,
                endColor to sEnd,
                endColor to 1.0f
            ).sortedBy { it.second }
        } else {
            listOf(
                startColor to 0.0f,
                startColor to sStart,
                endColor to sEnd,
                endColor to 1.0f
            ).sortedBy { it.second }
        }
        val colors = sorted.map { it.first }.toIntArray()
        val positions = sorted.map { it.second.coerceIn(0f, 1f) }.toFloatArray()

        return LinearGradient(x0, y0, x1, y1, colors, positions, Shader.TileMode.CLAMP)
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

    override fun evaluateBezierSurface(u: Float, v: Float, outPoint: FloatArray) {
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

    private fun drawGlitch2Software(targetCanvas: Canvas, w: Float, h: Float, drawInner: (Canvas) -> Unit) {
        val pad = calculatePadding()
        val bmpW = ceil(w + pad * 2).toInt()
        val bmpH = ceil(h + pad * 2).toInt()

        if (bmpW <= 0 || bmpH <= 0) {
            drawInner(targetCanvas)
            return
        }

        val srcBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val srcCanvas = Canvas(srcBmp)
        srcCanvas.translate(pad, pad)
        drawInner(srcCanvas)

        val outBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val outCanvas = Canvas(outBmp)

        val numSlices = glitch2Slices.toInt().coerceAtLeast(1)
        val splitPixels = 0.005f * glitch2RgbSplitIntensity * bmpW

        val redMatrix = android.graphics.ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        val redFilter = android.graphics.ColorMatrixColorFilter(redMatrix)

        val cyanMatrix = android.graphics.ColorMatrix(floatArrayOf(
            0f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        val cyanFilter = android.graphics.ColorMatrixColorFilter(cyanMatrix)

        val pRed = Paint().apply { colorFilter = redFilter; isFilterBitmap = true }
        val pCyan = Paint().apply {
            colorFilter = cyanFilter
            isFilterBitmap = true
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
        }

        for (i in 0 until numSlices) {
            val sliceYStart = (i * bmpH) / numSlices
            val sliceYEnd = ((i + 1) * bmpH) / numSlices
            if (sliceYStart >= sliceYEnd) continue

            val random = java.util.Random(glitch2Seed + i)
            val r = random.nextFloat()
            var offset = 0f
            if (r > 0.65f) {
                val glitch = (r - 0.65f) * 4.0f * glitch2Intensity
                val direction = if (random.nextFloat() > 0.5f) 1.0f else -1.0f
                offset = glitch * 0.08f * direction * bmpW
            }

            val srcRect = android.graphics.Rect(0, sliceYStart, bmpW, sliceYEnd)

            // Red pass
            val destRectRed = RectF(offset - splitPixels, sliceYStart.toFloat(), bmpW + offset - splitPixels, sliceYEnd.toFloat())
            outCanvas.save()
            outCanvas.clipRect(0f, sliceYStart.toFloat(), bmpW.toFloat(), sliceYEnd.toFloat())
            outCanvas.drawBitmap(srcBmp, srcRect, destRectRed, pRed)
            outCanvas.restore()

            // Cyan pass
            val destRectCyan = RectF(offset + splitPixels, sliceYStart.toFloat(), bmpW + offset + splitPixels, sliceYEnd.toFloat())
            outCanvas.save()
            outCanvas.clipRect(0f, sliceYStart.toFloat(), bmpW.toFloat(), sliceYEnd.toFloat())
            outCanvas.drawBitmap(srcBmp, srcRect, destRectCyan, pCyan)
            outCanvas.restore()
        }

        targetCanvas.save()
        targetCanvas.translate(-pad, -pad)
        targetCanvas.drawBitmap(outBmp, 0f, 0f, null)
        targetCanvas.restore()

        srcBmp.recycle()
        outBmp.recycle()
    }

    private fun calculatePerspectiveMatrix(src: RectF, dst: FloatArray): Matrix {
        val matrix = Matrix(); val srcPts = floatArrayOf(src.left, src.top, src.right, src.top, src.right, src.bottom, src.left, src.bottom)
        matrix.setPolyToPoly(srcPts, 0, dst, 0, 4); return matrix
    }

    override fun addErasePath(path: Path, size: Float, opacity: Int, hardness: Float) {
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

    override fun undoLastErasePath(baseMask: Bitmap?) {
        if (erasePaths.isNotEmpty()) { erasePaths.removeAt(erasePaths.size - 1); rebuildEraseMask(baseMask) }
    }

    override fun rebuildEraseMask(baseMask: Bitmap?) {
        val pad = calculatePadding()
        val baseW = getWidth().toInt().coerceAtLeast(1)
        val baseH = getHeight().toInt().coerceAtLeast(1)
        val maskW = (baseW + pad * 2).toInt().coerceAtLeast(1)
        val maskH = (baseH + pad * 2).toInt().coerceAtLeast(1)
        val newMask = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888); val c = Canvas(newMask)
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
        newLayer.customWidth = customWidth
        newLayer.customHeight = customHeight
        newLayer.x = x; newLayer.y = y; newLayer.rotation = rotation; newLayer.scaleX = scaleX; newLayer.scaleY = scaleY
        newLayer.isVisible = isVisible; newLayer.isLocked = isLocked; newLayer.isClipped = isClipped; newLayer.name = name
        newLayer.opacity = opacity; newLayer.blendMode = blendMode; newLayer.isOpacityGradient = isOpacityGradient; newLayer.opacityStart = opacityStart; newLayer.opacityEnd = opacityEnd; newLayer.opacityAngle = opacityAngle
        newLayer.shadowColor = shadowColor; newLayer.shadowRadius = shadowRadius; newLayer.shadowDx = shadowDx; newLayer.shadowDy = shadowDy
        newLayer.isMotionShadow = isMotionShadow; newLayer.isMotionShadowIncludeStroke = isMotionShadowIncludeStroke; newLayer.motionShadowAngle = motionShadowAngle; newLayer.motionShadowDistance = motionShadowDistance; newLayer.motionShadowThickness = motionShadowThickness
        newLayer.isGradient = isGradient; newLayer.gradientStartColor = gradientStartColor; newLayer.gradientEndColor = gradientEndColor; newLayer.gradientAngle = gradientAngle; newLayer.hasMiddleColor = hasMiddleColor; newLayer.gradientMiddleColor = gradientMiddleColor; newLayer.isGradientText = isGradientText; newLayer.isGradientStroke = isGradientStroke; newLayer.isGradientShadow = isGradientShadow
        newLayer.gradientStartPos = gradientStartPos; newLayer.gradientMiddlePos = gradientMiddlePos; newLayer.gradientEndPos = gradientEndPos
        newLayer.isGlobalGradient = isGlobalGradient; newLayer.globalP1 = PointF(globalP1.x, globalP1.y); newLayer.globalP2 = PointF(globalP2.x, globalP2.y)
        newLayer.strokeColor = strokeColor; newLayer.strokeWidth = strokeWidth; newLayer.doubleStrokeColor = doubleStrokeColor; newLayer.doubleStrokeWidth = doubleStrokeWidth; newLayer.tripleStrokeColor = tripleStrokeColor; newLayer.tripleStrokeWidth = tripleStrokeWidth
        newLayer.isPerspective = isPerspective; newLayer.perspectivePoints = perspectivePoints?.clone()
        newLayer.isWarp = isWarp; newLayer.warpRows = warpRows; newLayer.warpCols = warpCols; newLayer.warpMesh = warpMesh?.clone()
        newLayer.textureBitmap = textureBitmap; newLayer.textureOffsetX = textureOffsetX; newLayer.textureOffsetY = textureOffsetY
        newLayer.patternName = patternName; newLayer.patternColor = patternColor; newLayer.patternAlpha = patternAlpha; newLayer.patternScale = patternScale; newLayer.patternRotation = patternRotation
        if (eraseMask != null) newLayer.eraseMask = eraseMask!!.copy(eraseMask!!.config, true)
        for (p in erasePaths) newLayer.erasePaths.add(ErasePathData(Path(p.path), p.size, p.opacity, p.hardness))
        newLayer.currentEffect = currentEffect; newLayer.secondaryEffect = secondaryEffect; newLayer.tertiaryEffect = tertiaryEffect; newLayer.blurRadius = blurRadius; newLayer.longShadowLength = longShadowLength; newLayer.longShadowColor = longShadowColor; newLayer.longShadowAngle = longShadowAngle; newLayer.motionBlurLength = motionBlurLength; newLayer.motionBlurAngle = motionBlurAngle
        newLayer.motionBlurKernelSize = motionBlurKernelSize; newLayer.motionBlurOffset = motionBlurOffset; newLayer.motionBlurVelocityX = motionBlurVelocityX; newLayer.motionBlurVelocityY = motionBlurVelocityY
        newLayer.halftoneDotSize = halftoneDotSize; newLayer.halftoneDotColor = halftoneDotColor; newLayer.halftoneThreshold = halftoneThreshold
        newLayer.halftoneType = halftoneType; newLayer.halftoneAlpha = halftoneAlpha; newLayer.halftoneRange = halftoneRange; newLayer.halftoneDensity = halftoneDensity; newLayer.halftoneFadingIntensity = halftoneFadingIntensity; newLayer.halftoneShape = halftoneShape; newLayer.neonRadius = neonRadius; newLayer.neonColor = neonColor; newLayer.neonAlpha = neonAlpha; newLayer.neonInnerStrength = neonInnerStrength; newLayer.neonOuterStrength = neonOuterStrength; newLayer.neonKnockout = neonKnockout; newLayer.neonQuality = neonQuality; newLayer.glitchIntensity = glitchIntensity;
        newLayer.glitchSeed = glitchSeed; newLayer.decaySeed = decaySeed; newLayer.glitch2Seed = glitch2Seed; newLayer.glitch2Intensity = glitch2Intensity; newLayer.glitch2Slices = glitch2Slices; newLayer.glitch2RgbSplitIntensity = glitch2RgbSplitIntensity; newLayer.glitch2NoiseIntensity = glitch2NoiseIntensity;
        newLayer.pixelBlockSize = pixelBlockSize; newLayer.chromaticShift = chromaticShift; newLayer.chromaticColors = chromaticColors.clone(); newLayer.chromaticAngle = chromaticAngle; newLayer.effectSeed = effectSeed; newLayer.fieryColor = fieryColor; newLayer.fieryIntensity = fieryIntensity; newLayer.wavyIntensity = wavyIntensity; newLayer.wavyFrequency = wavyFrequency; newLayer.particleSize = particleSize; newLayer.particleSpread = particleSpread; newLayer.particleDissolveAngle = particleDissolveAngle; newLayer.multiGradientColors = multiGradientColors.clone(); newLayer.multiGradientAngle = multiGradientAngle; newLayer.radialBlurInnerRadius = radialBlurInnerRadius; newLayer.radialBlurMotionStrength = radialBlurMotionStrength
        newLayer.radialBlurCenterX = radialBlurCenterX; newLayer.radialBlurCenterY = radialBlurCenterY

        // Twist
        newLayer.twistAngle = twistAngle
        newLayer.twistOffsetX = twistOffsetX
        newLayer.twistOffsetY = twistOffsetY
        newLayer.twistRadius = twistRadius

        // Bulge & Pinch
        newLayer.bulgeCenterX = bulgeCenterX
        newLayer.bulgeCenterY = bulgeCenterY
        newLayer.bulgeRadius = bulgeRadius
        newLayer.bulgeStrength = bulgeStrength

        // Reflection
        newLayer.reflectionAlphaStart = reflectionAlphaStart
        newLayer.reflectionAlphaEnd = reflectionAlphaEnd
        newLayer.reflectionAmplitudeStart = reflectionAmplitudeStart
        newLayer.reflectionAmplitudeEnd = reflectionAmplitudeEnd
        newLayer.reflectionBoundary = reflectionBoundary
        newLayer.reflectionMirror = reflectionMirror
        newLayer.reflectionTime = reflectionTime
        newLayer.reflectionWavelengthStart = reflectionWavelengthStart
        newLayer.reflectionWavelengthEnd = reflectionWavelengthEnd

        // Zoom Blur
        newLayer.zoomBlurCenterX = zoomBlurCenterX
        newLayer.zoomBlurCenterY = zoomBlurCenterY
        newLayer.zoomBlurInnerRadius = zoomBlurInnerRadius
        newLayer.zoomBlurRadius = zoomBlurRadius
        newLayer.zoomBlurStrength = zoomBlurStrength

        return newLayer
    }

    override fun updateDenseWarpMesh() {
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

    override fun doubleResolution() {
        customWidth = getWidth() * 2f
        customHeight = getHeight() * 2f

        strokeWidth *= 2f
        doubleStrokeWidth *= 2f
        tripleStrokeWidth *= 2f
        shadowRadius *= 2f
        shadowDx *= 2f
        shadowDy *= 2f
        motionShadowDistance *= 2f
        motionShadowThickness *= 2f
        blurRadius *= 2f
        longShadowLength *= 2f
        neonRadius *= 2f
        chromaticShift *= 2f
        pixelBlockSize *= 2f
        twistRadius *= 2f
        twistOffsetX *= 2f
        twistOffsetY *= 2f
        bulgeRadius *= 2f
        reflectionAmplitudeStart *= 2f
        reflectionAmplitudeEnd *= 2f
        reflectionWavelengthStart *= 2f
        reflectionWavelengthEnd *= 2f
        zoomBlurRadius *= 2f

        perspectivePoints?.let { pts ->
            for (i in pts.indices) {
                pts[i] *= 2f
            }
        }

        warpMesh?.let { mesh ->
            for (i in mesh.indices) {
                mesh[i] *= 2f
            }
        }

        if (erasePaths.isNotEmpty()) {
            val matrix = Matrix()
            matrix.setScale(2f, 2f)
            val scaledPaths = erasePaths.map { ep ->
                val newPath = Path(ep.path)
                newPath.transform(matrix)
                ErasePathData(newPath, ep.size * 2f, ep.opacity, ep.hardness)
            }
            erasePaths.clear()
            erasePaths.addAll(scaledPaths)
            if (eraseMask != null) {
                rebuildEraseMask(null)
            }
        }

        scaleX /= 2f
        scaleY /= 2f
    }
}
