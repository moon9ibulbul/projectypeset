package com.astral.typer.models

import com.astral.typer.utils.CustomTypefaceSpan
import com.astral.typer.utils.LetterSpacingSpan
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
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import java.util.Random
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

class TextLayer(
    initialText: String = "Double tap to edit",
    override var color: Int = Color.BLACK
) : Layer(), StylableLayer {

    private var _text: SpannableStringBuilder = SpannableStringBuilder(initialText)
    var text: SpannableStringBuilder
        get() = _text
        set(value) {
            val oldStr = _text.toString()
            val newStr = value.toString()
            _text = value
            if (oldStr != newStr) {
                letterWarpMeshes.clear()
                letterWarpRows.clear()
                letterWarpCols.clear()
                selectedWarpIndex = -1
            }
        }
    var fontSize: Float = 100f
    var typeface: Typeface = Typeface.DEFAULT
    var fontPath: String? = null // Identifier for the font (e.g., "Standard:Serif" or "/path/to/font.ttf")
    var textAlign: Layout.Alignment = Layout.Alignment.ALIGN_CENTER
    var isJustified: Boolean = false

    // Spacing
    var letterSpacing: Float = 0f
    var lineSpacing: Float = 0f

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

    // Perspective
    override var isPerspective: Boolean = false
    override var perspectivePoints: FloatArray? = null

    // Warp
    override var isWarp: Boolean = false

    private var _warpRows: Int = 2
    override var warpRows: Int
        get() = if (selectedWarpIndex == -1) _warpRows else (letterWarpRows[selectedWarpIndex] ?: 2)
        set(value) {
            if (selectedWarpIndex == -1) {
                _warpRows = value
            } else {
                letterWarpRows[selectedWarpIndex] = value
            }
        }

    private var _warpCols: Int = 2
    override var warpCols: Int
        get() = if (selectedWarpIndex == -1) _warpCols else (letterWarpCols[selectedWarpIndex] ?: 2)
        set(value) {
            if (selectedWarpIndex == -1) {
                _warpCols = value
            } else {
                letterWarpCols[selectedWarpIndex] = value
            }
        }

    private var _warpMesh: FloatArray? = null
    override var warpMesh: FloatArray?
        get() = if (selectedWarpIndex == -1) _warpMesh else letterWarpMeshes[selectedWarpIndex]
        set(value) {
            if (selectedWarpIndex == -1) {
                _warpMesh = value
            } else {
                if (value != null) {
                    letterWarpMeshes[selectedWarpIndex] = value
                } else {
                    letterWarpMeshes.remove(selectedWarpIndex)
                }
            }
        }

    override var selectedWarpIndex: Int = -1
    var letterWarpMeshes: MutableMap<Int, FloatArray> = mutableMapOf()
    var letterWarpRows: MutableMap<Int, Int> = mutableMapOf()
    var letterWarpCols: MutableMap<Int, Int> = mutableMapOf()

    val mainWarpMesh: FloatArray?
        get() = _warpMesh
    val mainWarpRows: Int
        get() = _warpRows
    val mainWarpCols: Int
        get() = _warpCols

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

    // Erase Paths for Granular Undo (Runtime only, lost on save/load if not serialized, but acceptable for session undo)
    override val erasePaths = mutableListOf<ErasePathData>()

    // Live erase path for preview
    @Transient
    override var activeErasePath: Path? = null
    @Transient
    override var activeEraseSize: Float = 0f
    @Transient
    override var activeEraseOpacity: Int = 0
    @Transient
    override var activeEraseHardness: Float = 0f
    override var eraseDragRevision: Int = 0

    // Effect
    override var currentEffect: TextEffectType = TextEffectType.NONE
    override var secondaryEffect: TextEffectType = TextEffectType.NONE

    // Gaussian Blur
    override var blurRadius: Float = 0f

    // Long Shadow
    override var longShadowLength: Float = 30f
    override var longShadowColor: Int = Color.DKGRAY
    override var longShadowAngle: Float = 45f

    // Motion Blur
    override var motionBlurLength: Float = 0f
    override var motionBlurAngle: Int = 0

    // Halftone
    override var halftoneDotSize: Float = 10f
    override var halftoneDotColor: Int = Color.BLACK
    override var halftoneThreshold: Float = 0.5f

    // Neon
    override var neonRadius: Float = 30f
    override var neonColor: Int = Color.CYAN

    // Glitch
    override var glitchIntensity: Float = 1.0f

    // Pixelation
    override var pixelBlockSize: Float = 10f

    // Chromatic Aberration
    override var chromaticShift: Float = 5f
    override var chromaticColors: IntArray = intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF00FF00.toInt()) // Left, Right, Center

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
    override var multiGradientColors: IntArray = intArrayOf(0xFFFF0000.toInt(), 0xFFFF7F00.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF4B0082.toInt(), 0xFF9400D3.toInt()) // Classic Rainbow
    override var multiGradientAngle: Float = 0f

    // Radial Blur
    override var radialBlurInnerRadius: Float = 0f
    override var radialBlurMotionStrength: Float = 0f

    // Text Decay
    override var decayIntensity: Float = 0.5f
    override var decayFadingLevel: Float = 0.5f

    // Shape
    var isOval: Boolean = false

    // Random Seed for Glitch effect
    override var effectSeed: Long = System.currentTimeMillis()

    // Caching for Pixelation
    @Transient
    private var cachedPixelBitmap: Bitmap? = null
    @Transient
    private var cachedPixelHash: Int = 0

    // Caching for Wavy fallback
    @Transient
    private var cachedWavyBitmap: Bitmap? = null
    @Transient
    private var cachedWavyHash: Int = 0

    // Caching for Pattern Shader
    @Transient
    private var cachedPatternShader: Shader? = null
    @Transient
    private var cachedPatternHash: Int = 0
    @Transient
    private var cachedPatternXfermode: PorterDuffXfermode? = null

    // Base Content Caching for Warp & Perspective optimization
    @Transient
    var cleanContentCache: Bitmap? = null
    @Transient
    var cleanContentHash: Int = 0

    @Transient
    var erasedContentCache: Bitmap? = null
    @Transient
    var erasedContentHash: Int = -1

    fun calculateCleanContentHash(w: Float, ch: Float, pad: Float, qualityScale: Float): Int {
        var result = text.toString().hashCode()
        result = 31 * result + w.hashCode()
        result = 31 * result + ch.hashCode()
        result = 31 * result + pad.hashCode()
        result = 31 * result + qualityScale.hashCode()
        result = 31 * result + color
        result = 31 * result + fontSize.hashCode()
        result = 31 * result + (fontPath?.hashCode() ?: 0)
        result = 31 * result + textAlign.hashCode()
        result = 31 * result + isJustified.hashCode()
        result = 31 * result + letterSpacing.hashCode()
        result = 31 * result + lineSpacing.hashCode()
        result = 31 * result + shadowColor
        result = 31 * result + shadowRadius.hashCode()
        result = 31 * result + shadowDx.hashCode()
        result = 31 * result + shadowDy.hashCode()
        result = 31 * result + isMotionShadow.hashCode()
        result = 31 * result + isMotionShadowIncludeStroke.hashCode()
        result = 31 * result + motionShadowAngle
        result = 31 * result + motionShadowDistance.hashCode()
        result = 31 * result + motionShadowThickness.hashCode()
        result = 31 * result + isGradient.hashCode()
        result = 31 * result + gradientStartColor
        result = 31 * result + gradientEndColor
        result = 31 * result + gradientAngle
        result = 31 * result + isGradientText.hashCode()
        result = 31 * result + isGradientStroke.hashCode()
        result = 31 * result + isGradientShadow.hashCode()
        result = 31 * result + isGlobalGradient.hashCode()
        result = 31 * result + globalP1.x.hashCode()
        result = 31 * result + globalP1.y.hashCode()
        result = 31 * result + globalP2.x.hashCode()
        result = 31 * result + globalP2.y.hashCode()
        result = 31 * result + strokeColor
        result = 31 * result + strokeWidth.hashCode()
        result = 31 * result + doubleStrokeColor
        result = 31 * result + doubleStrokeWidth.hashCode()
        result = 31 * result + (textureBitmap?.hashCode() ?: 0)
        result = 31 * result + textureOffsetX.hashCode()
        result = 31 * result + textureOffsetY.hashCode()
        result = 31 * result + (patternName?.hashCode() ?: 0)
        result = 31 * result + patternColor
        result = 31 * result + patternAlpha
        result = 31 * result + patternScale.hashCode()
        result = 31 * result + patternRotation.hashCode()
        result = 31 * result + currentEffect.hashCode()
        result = 31 * result + secondaryEffect.hashCode()
        result = 31 * result + effectSeed.hashCode()
        result = 31 * result + blurRadius.hashCode()
        result = 31 * result + longShadowLength.hashCode()
        result = 31 * result + longShadowColor
        result = 31 * result + longShadowAngle.hashCode()
        result = 31 * result + motionBlurLength.hashCode()
        result = 31 * result + motionBlurAngle
        result = 31 * result + halftoneDotSize.hashCode()
        result = 31 * result + halftoneDotColor
        result = 31 * result + halftoneThreshold.hashCode()
        result = 31 * result + neonRadius.hashCode()
        result = 31 * result + neonColor
        result = 31 * result + glitchIntensity.hashCode()
        result = 31 * result + pixelBlockSize.hashCode()
        result = 31 * result + chromaticShift.hashCode()
        result = 31 * result + chromaticColors.contentHashCode()
        result = 31 * result + multiGradientColors.contentHashCode()
        result = 31 * result + multiGradientAngle.hashCode()
        result = 31 * result + particleSize.hashCode()
        result = 31 * result + particleSpread.hashCode()
        result = 31 * result + particleDissolveAngle.hashCode()
        result = 31 * result + radialBlurInnerRadius.hashCode()
        result = 31 * result + radialBlurMotionStrength.hashCode()
        result = 31 * result + decayIntensity.hashCode()
        result = 31 * result + decayFadingLevel.hashCode()
        return result
    }

    private fun getErasedContentBitmap(layout: StaticLayout, w: Float, ch: Float, pad: Float, qualityScale: Float, bmpW: Int, bmpH: Int): Bitmap {
        // 1. Ensure cleanContentCache is valid
        val cleanHash = calculateCleanContentHash(w, ch, pad, qualityScale)
        val cleanValid = cleanContentCache != null && !cleanContentCache!!.isRecycled &&
                cleanContentCache!!.width == bmpW && cleanContentCache!!.height == bmpH &&
                cleanContentHash == cleanHash

        if (!cleanValid) {
            cleanContentCache?.recycle()
            val newClean = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val c = Canvas(newClean)
            c.scale(qualityScale, qualityScale)
            c.translate(pad, pad)
            drawCleanContent(c, layout, w, ch)
            cleanContentCache = newClean
            cleanContentHash = cleanHash
            erasedContentHash = -1 // force update erased content
        }

        // 2. Ensure erasedContentCache has matching size
        if (erasedContentCache == null || erasedContentCache!!.isRecycled ||
            erasedContentCache!!.width != bmpW || erasedContentCache!!.height != bmpH) {
            erasedContentCache?.recycle()
            erasedContentCache = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            erasedContentHash = -1 // force update
        }

        // 3. Update erasedContentCache if eraseDragRevision has changed
        if (erasedContentHash != eraseDragRevision) {
            val erasedBmp = erasedContentCache!!
            val c = Canvas(erasedBmp)
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Draw clean content
            c.drawBitmap(cleanContentCache!!, 0f, 0f, null)

            // Apply Erase Mask (with scale since clean content is qualityScale-scaled)
            if (eraseMask != null) {
                val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                c.drawBitmap(eraseMask!!, null, RectF(0f, 0f, bmpW.toFloat(), bmpH.toFloat()), maskPaint)
            }

            // Apply active erase path preview (also with scale)
            if (activeErasePath != null) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    style = Paint.Style.STROKE
                    strokeWidth = activeEraseSize * qualityScale
                    alpha = activeEraseOpacity
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    if (activeEraseHardness < 100) {
                        val radius = (activeEraseSize * qualityScale) / 2f
                        val blur = radius * (1f - (activeEraseHardness / 100f))
                        if (blur > 0.5f) {
                            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                        }
                    }
                }
                c.save()
                c.scale(qualityScale, qualityScale)
                c.drawPath(activeErasePath!!, p)
                c.restore()
            }
            erasedContentHash = eraseDragRevision
        }

        return erasedContentCache!!
    }

    fun recycleCache() {
        cleanContentCache?.recycle()
        cleanContentCache = null
        erasedContentCache?.recycle()
        erasedContentCache = null
        cachedPixelBitmap?.recycle()
        cachedPixelBitmap = null
        cachedWavyBitmap?.recycle()
        cachedWavyBitmap = null
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var cachedLayout: StaticLayout? = null

    var boxWidth: Float? = null
    var fixedHeight: Float? = null

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
        newLayer.isMotionShadowIncludeStroke = this.isMotionShadowIncludeStroke
        newLayer.motionShadowAngle = this.motionShadowAngle
        newLayer.motionShadowDistance = this.motionShadowDistance
        newLayer.motionShadowThickness = this.motionShadowThickness

        newLayer.isGradient = this.isGradient
        newLayer.gradientStartColor = this.gradientStartColor
        newLayer.gradientEndColor = this.gradientEndColor
        newLayer.gradientAngle = this.gradientAngle
        newLayer.isGradientText = this.isGradientText
        newLayer.isGradientStroke = this.isGradientStroke
        newLayer.isGradientShadow = this.isGradientShadow
        newLayer.isGlobalGradient = this.isGlobalGradient
        newLayer.globalP1 = PointF(this.globalP1.x, this.globalP1.y)
        newLayer.globalP2 = PointF(this.globalP2.x, this.globalP2.y)
        newLayer.strokeColor = this.strokeColor
        newLayer.strokeWidth = this.strokeWidth
        newLayer.doubleStrokeColor = this.doubleStrokeColor
        newLayer.doubleStrokeWidth = this.doubleStrokeWidth
        newLayer.boxWidth = this.boxWidth
        newLayer.fixedHeight = this.fixedHeight
        newLayer.isOval = this.isOval

        newLayer.isPerspective = this.isPerspective
        newLayer.perspectivePoints = this.perspectivePoints?.clone()

        newLayer.isWarp = this.isWarp
        newLayer._warpRows = this._warpRows
        newLayer._warpCols = this._warpCols
        newLayer._warpMesh = this._warpMesh?.clone()

        newLayer.selectedWarpIndex = this.selectedWarpIndex
        newLayer.letterWarpMeshes = this.letterWarpMeshes.mapValues { it.value.clone() }.toMutableMap()
        newLayer.letterWarpRows = this.letterWarpRows.toMutableMap()
        newLayer.letterWarpCols = this.letterWarpCols.toMutableMap()

        newLayer.textureBitmap = this.textureBitmap
        newLayer.textureOffsetX = this.textureOffsetX
        newLayer.textureOffsetY = this.textureOffsetY

        newLayer.patternName = this.patternName
        newLayer.patternColor = this.patternColor
        newLayer.patternAlpha = this.patternAlpha
        newLayer.patternScale = this.patternScale
        newLayer.patternRotation = this.patternRotation

        if (this.eraseMask != null) {
            newLayer.eraseMask = this.eraseMask!!.copy(this.eraseMask!!.config, true)
        }

        // Clone paths (Paths are mutable, need deep copy)
        for (p in this.erasePaths) {
            newLayer.erasePaths.add(ErasePathData(Path(p.path), p.size, p.opacity, p.hardness))
        }

        newLayer.currentEffect = this.currentEffect
        newLayer.secondaryEffect = this.secondaryEffect
        newLayer.blurRadius = this.blurRadius
        newLayer.longShadowLength = this.longShadowLength
        newLayer.longShadowColor = this.longShadowColor
        newLayer.longShadowAngle = this.longShadowAngle
        newLayer.motionBlurLength = this.motionBlurLength
        newLayer.motionBlurAngle = this.motionBlurAngle
        newLayer.halftoneDotSize = this.halftoneDotSize
        newLayer.halftoneDotColor = this.halftoneDotColor
        newLayer.halftoneThreshold = this.halftoneThreshold
        newLayer.neonRadius = this.neonRadius
        newLayer.neonColor = this.neonColor
        newLayer.glitchIntensity = this.glitchIntensity
        newLayer.pixelBlockSize = this.pixelBlockSize
        newLayer.chromaticShift = this.chromaticShift
        newLayer.chromaticColors = this.chromaticColors.clone()
        newLayer.effectSeed = this.effectSeed

        newLayer.fieryColor = this.fieryColor
        newLayer.fieryIntensity = this.fieryIntensity
        newLayer.wavyIntensity = this.wavyIntensity
        newLayer.wavyFrequency = this.wavyFrequency
        newLayer.particleSize = this.particleSize
        newLayer.particleSpread = this.particleSpread
        newLayer.particleDissolveAngle = this.particleDissolveAngle

        newLayer.multiGradientColors = this.multiGradientColors.clone()
        newLayer.multiGradientAngle = this.multiGradientAngle

        newLayer.radialBlurInnerRadius = this.radialBlurInnerRadius
        newLayer.radialBlurMotionStrength = this.radialBlurMotionStrength
        newLayer.decayIntensity = this.decayIntensity
        newLayer.decayFadingLevel = this.decayFadingLevel

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

    override fun addErasePath(path: Path, size: Float, opacity: Int, hardness: Float) {
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

        // New clean bitmap
        val newMask = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888)
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
        // If fixedHeight is set, we return boxWidth/fixedHeight for the container size
        // But cachedLayout.width is the text flow width.
        // We should return the visible bounding box width.
        return if (boxWidth != null && boxWidth!! > 0) boxWidth!! else (cachedLayout?.width?.toFloat() ?: 0f)
    }

    override fun getHeight(): Float {
        ensureLayout()
        // Return fixedHeight if set, otherwise content height
        return if (fixedHeight != null && fixedHeight!! > 0) fixedHeight!! else (cachedLayout?.height?.toFloat() ?: 0f)
    }

    // Internal method to get actual content height (for scrolling/positioning if needed)
    fun getContentHeight(): Float {
        ensureLayout()
        return cachedLayout?.height?.toFloat() ?: 0f
    }

    override fun calculatePadding(): Float {
        var p = strokeWidth + doubleStrokeWidth
        p = Math.max(p, shadowRadius + Math.max(Math.abs(shadowDx), Math.abs(shadowDy)))
        if (isMotionShadow) p = Math.max(p, motionShadowDistance + 20f)

        var effectExpansion = 0f
        val checkEffect = { effect: TextEffectType ->
            when(effect) {
                TextEffectType.GAUSSIAN_BLUR -> effectExpansion = Math.max(effectExpansion, blurRadius * 2.5f)
                TextEffectType.MOTION_BLUR -> effectExpansion = Math.max(effectExpansion, motionBlurLength)
                TextEffectType.NEON -> effectExpansion = Math.max(effectExpansion, neonRadius * 1.5f)
                TextEffectType.LONG_SHADOW -> effectExpansion = Math.max(effectExpansion, longShadowLength)
                TextEffectType.RADIAL_BLUR -> effectExpansion = Math.max(effectExpansion, 50f + radialBlurMotionStrength * 0.5f)
                TextEffectType.CHROMATIC_ABERRATION -> effectExpansion = Math.max(effectExpansion, chromaticShift)
                TextEffectType.GLITCH -> effectExpansion = Math.max(effectExpansion, 100f * glitchIntensity)
                TextEffectType.FIERY -> effectExpansion = Math.max(effectExpansion, fieryIntensity * 20f + 20f)
                TextEffectType.WAVY -> effectExpansion = Math.max(effectExpansion, wavyIntensity * 10f + 10f)
                else -> {}
            }
        }
        checkEffect(currentEffect)
        checkEffect(secondaryEffect)

        return (p + effectExpansion + 20f).coerceAtLeast(0f)
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

        // Texture Application (Legacy)
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

            // Helper to create a fresh builder (Builders cannot be reused after build())
            fun createBuilder(): StaticLayout.Builder {
                val b = StaticLayout.Builder.obtain(
                    text, 0, text.length, textPaint, layoutWidth.coerceAtLeast(10)
                ).setAlignment(textAlign)
                 .setLineSpacing(lineSpacing, 1.0f)
                 .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                 .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)

                if (isJustified && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    b.setJustificationMode(1)
                }
                return b
            }

            var builder = createBuilder()

            // Oval Shape Support (API 23+)
            if (isOval && boxWidth != null && boxWidth!! > 0) {
                // Heuristic: Iterate to find height and indents
                // Pass 1: Build basic layout to estimate height (Rectangular)
                // This consumes the first builder
                val tempLayout = builder.build()
                val rectHeight = tempLayout.height

                // Estimate target height for Oval (Area compensation: Oval fits less text than Rect)
                // A_rect = w*h. A_oval = (PI/4)*w*h_oval. To wrap same text: h_oval approx 1.27 * h_rect.
                val targetHeight = (rectHeight * 1.3f).toInt()

                val halfH = targetHeight / 2f
                val halfW = layoutWidth / 2f

                // Estimate lines based on temp layout
                val lineCount = tempLayout.lineCount
                val avgLineHeight = if(lineCount > 0) rectHeight.toFloat() / lineCount else (textPaint.textSize + lineSpacing)

                // Allocate enough slots for increased height
                val estLines = (targetHeight / avgLineHeight).toInt() + 5

                val lIndents = IntArray(estLines)
                val rIndents = IntArray(estLines)

                for (i in 0 until estLines) {
                    // Calculate Y for this line relative to oval center
                    val lineY = (i * avgLineHeight) + (avgLineHeight / 2f)
                    val yRel = lineY - (targetHeight / 2f) // Center is at targetHeight/2

                    // Normalize Y to -1..1 range, clamping slightly inside to avoid 0-width edge cases
                    val normalizedY = (yRel / halfH).coerceIn(-0.99f, 0.99f)

                    // Oval width factor at this Y: sqrt(1 - y^2)
                    val widthFactor = kotlin.math.sqrt(1f - normalizedY * normalizedY)

                    // Available width at this Y
                    val availW = layoutWidth * widthFactor

                    // Indent required on each side
                    val indent = ((layoutWidth - availW) / 2f).toInt().coerceAtLeast(0)

                    lIndents[i] = indent
                    rIndents[i] = indent
                }

                // Obtain a NEW builder for the final layout
                builder = createBuilder()
                builder.setIndents(lIndents, rIndents)
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
        if (isGlobalGradient) {
            val inverse = Matrix()
            val matrix = Matrix()
            matrix.setTranslate(x, y)
            matrix.preRotate(rotation)
            matrix.preScale(scaleX, scaleY)
            if (matrix.invert(inverse)) {
                val pts = floatArrayOf(globalP1.x, globalP1.y, globalP2.x, globalP2.y)
                inverse.mapPoints(pts)
                // Points are now relative to center. Convert to top-left relative.
                val x0 = pts[0] + w / 2f
                val y0 = pts[1] + h / 2f
                val x1 = pts[2] + w / 2f
                val y1 = pts[3] + h / 2f
                return LinearGradient(x0, y0, x1, y1, gradientStartColor, gradientEndColor, Shader.TileMode.CLAMP)
            }
        }
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

    private fun getMultiGradientShader(w: Float, h: Float): Shader {
        val cx = w / 2f
        val cy = h / 2f
        val angleRad = Math.toRadians(multiGradientAngle.toDouble())
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

        // Distribute colors evenly
        val positions = FloatArray(multiGradientColors.size) { i ->
            i.toFloat() / (multiGradientColors.size - 1)
        }

        return LinearGradient(x0, y0, x1, y1, multiGradientColors, positions, Shader.TileMode.CLAMP)
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

        val pad = calculatePadding()
        val ch = getContentHeight()
        val hScale = if (h > 0) ch / h else 1f

        val isWarpActive = isWarp && (_warpMesh != null || letterWarpMeshes.isNotEmpty())

        val bounds = if (isWarpActive) {
            val b = RectF()
            val expanded = getExpandedRenderBounds(layout, w, h, ch, pad)
            val renderW = expanded.renderRight - expanded.renderLeft
            val renderH = expanded.renderBottom - expanded.renderTop

            if (_warpMesh != null && selectedWarpIndex == -1) {
                val steps = 10
                val out = FloatArray(2)
                for (i in 0..steps) {
                    val ly = expanded.renderTop + (i / steps.toFloat()) * renderH
                    val v = (ly + h / 2f) / h
                    for (j in 0..steps) {
                        val lx = expanded.renderLeft + (j / steps.toFloat()) * renderW
                        val u = (lx + w / 2f) / w
                        evaluateFullLayerBezierSurface(u, v, out)
                        if (i == 0 && j == 0) b.set(out[0], out[1], out[0], out[1]) else b.union(out[0], out[1])
                    }
                }
            } else {
                b.set(expanded.renderLeft, expanded.renderTop, expanded.renderRight, expanded.renderBottom)
            }
            b.inset(-50f, -50f)
            b
        } else if (isPerspective && perspectivePoints != null) {
            val srcRect = RectF(-w / 2f, -h / 2f, w / 2f, h / 2f)
            val matrix = calculatePerspectiveMatrix(srcRect, perspectivePoints!!)
            val b = RectF()
            val pts = floatArrayOf(
                -w / 2f, -h / 2f,
                w / 2f, -h / 2f,
                w / 2f, -h / 2f + ch,
                -w / 2f, -h / 2f + ch
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

        if (isWarpActive) {
            val qualityScale = Math.max(1f, Math.max(Math.abs(scaleX), Math.abs(scaleY))).coerceAtMost(3f)
            if (_warpMesh != null && selectedWarpIndex == -1) {
                drawWarped(canvas, layout, w, h, ch, _warpRows, _warpCols, _warpMesh!!, qualityScale)
            } else {
                drawCharacterByCharacter(canvas, layout, w, h, ch, qualityScale)
            }
        } else if (isPerspective && perspectivePoints != null) {
             drawPerspective(canvas, layout, w, h, ch)
        } else {
             // If fixedHeight is set, we need to clip the content area
             if (fixedHeight != null && fixedHeight!! > 0) {
                 canvas.save()
                 val pad = calculatePadding()
                 canvas.clipRect(-w/2f - pad, -h/2f - pad, w/2f + pad, h/2f + pad)
                 canvas.translate(dx, dy)
                 drawContent(canvas, layout, w, h)
                 canvas.restore()
             } else {
                 canvas.translate(dx, dy)
                 drawContent(canvas, layout, w, h)
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

    private fun drawPerspective(canvas: Canvas, layout: StaticLayout, w: Float, h: Float, ch: Float) {
        val srcRect = RectF(-w / 2f, -h / 2f, w / 2f, h / 2f)
        val matrix = calculatePerspectiveMatrix(srcRect, perspectivePoints!!)
        val pad = calculatePadding()

        val qualityScale = Math.max(1f, Math.max(Math.abs(scaleX), Math.abs(scaleY))).coerceAtMost(3f)
        val bmpW = ceil((w + pad * 2) * qualityScale).toInt()
        val bmpH = ceil((ch + pad * 2) * qualityScale).toInt()

        if (bmpW > 0 && bmpH > 0) {
            val finalBmp = getErasedContentBitmap(layout, w, ch, pad, qualityScale, bmpW, bmpH)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
            canvas.save()
            canvas.concat(matrix)
            val destRect = RectF(
                -w / 2f - pad,
                -h / 2f - pad,
                -w / 2f - pad + finalBmp.width / qualityScale,
                -h / 2f - pad + finalBmp.height / qualityScale
            )
            canvas.drawBitmap(finalBmp, null, destRect, paint)
            canvas.restore()
        }
    }

    class WarpTarget(val index: Int, val label: String)

    fun getWarpTargets(): List<WarpTarget> {
        val targets = mutableListOf<WarpTarget>()
        val fullText = text.toString()
        targets.add(WarpTarget(-1, fullText))
        for (i in 0 until fullText.length) {
            val char = fullText[i]
            if (!char.isWhitespace()) {
                targets.add(WarpTarget(i, char.toString()))
            }
        }
        return targets
    }

    fun getWarpTargetBounds(targetIndex: Int): RectF {
        if (targetIndex == -1) {
            val w = getWidth()
            val h = getHeight()
            return RectF(-w / 2f, -h / 2f, w / 2f, h / 2f)
        }
        ensureLayout()
        val layout = cachedLayout ?: return RectF(0f, 0f, 0f, 0f)
        val textStr = text.toString()
        if (targetIndex < 0 || targetIndex >= textStr.length) {
            return RectF(0f, 0f, 0f, 0f)
        }
        val line = layout.getLineForOffset(targetIndex)
        val xStart = layout.getPrimaryHorizontal(targetIndex)
        val xEnd = layout.getPrimaryHorizontal(targetIndex + 1)
        val yTop = layout.getLineTop(line).toFloat()
        val yBottom = layout.getLineBottom(line).toFloat()

        val w = getWidth()
        val h = getHeight()
        val left = Math.min(xStart, xEnd)
        val right = Math.max(xStart, xEnd)

        return RectF(
            -w / 2f + left,
            -h / 2f + yTop,
            -w / 2f + right,
            -h / 2f + yBottom
        )
    }

    fun initWarpMeshForTarget(targetIndex: Int, rows: Int, cols: Int, forceReset: Boolean = false) {
        val bounds = getWarpTargetBounds(targetIndex)
        val count = (rows + 1) * (cols + 1)
        val mesh = FloatArray(count * 2)
        var index = 0
        val oldSelectedWarpIndex = selectedWarpIndex
        val hasOldMesh = !forceReset && (if (targetIndex == -1) _warpMesh != null else letterWarpMeshes[targetIndex] != null)
        val outPoint = FloatArray(2)

        for (r in 0..rows) {
            val v = r / rows.toFloat()
            for (c in 0..cols) {
                val u = c / cols.toFloat()
                if (hasOldMesh) {
                    selectedWarpIndex = targetIndex
                    evaluateBezierSurface(u, v, outPoint)
                    mesh[index++] = outPoint[0]
                    mesh[index++] = outPoint[1]
                } else {
                    val y = bounds.top + (bounds.height() * v)
                    val x = bounds.left + (bounds.width() * u)
                    mesh[index++] = x
                    mesh[index++] = y
                }
            }
        }
        selectedWarpIndex = oldSelectedWarpIndex

        if (targetIndex == -1) {
            _warpMesh = mesh
            _warpRows = rows
            _warpCols = cols
        } else {
            letterWarpMeshes[targetIndex] = mesh
            letterWarpRows[targetIndex] = rows
            letterWarpCols[targetIndex] = cols
        }
    }

    class RenderBounds(
        val renderLeft: Float,
        val renderRight: Float,
        val renderTop: Float,
        val renderBottom: Float
    )

    fun getExpandedRenderBounds(layout: StaticLayout, w: Float, h: Float, ch: Float, pad: Float): RenderBounds {
        val fullText = text.toString()
        var minX = -w / 2f - pad
        var maxX = w / 2f + pad
        var minY = -h / 2f - pad
        var maxY = -h / 2f + ch + pad

        if (letterWarpMeshes.isNotEmpty()) {
            var first = true
            for (i in 0 until fullText.length) {
                val char = fullText[i]
                if (char.isWhitespace()) continue

                val mesh = letterWarpMeshes[i]
                if (mesh != null) {
                    for (idx in 0 until (mesh.size / 2)) {
                        val mx = mesh[idx * 2]
                        val my = mesh[idx * 2 + 1]
                        if (first) {
                            minX = mx - pad; maxX = mx + pad; minY = my - pad; maxY = my + pad
                            first = false
                        } else {
                            if (mx - pad < minX) minX = mx - pad
                            if (mx + pad > maxX) maxX = mx + pad
                            if (my - pad < minY) minY = my - pad
                            if (my + pad > maxY) maxY = my + pad
                        }
                    }
                } else {
                    val line = layout.getLineForOffset(i)
                    val xStart = layout.getPrimaryHorizontal(i)
                    val xEnd = layout.getPrimaryHorizontal(i + 1)
                    val yTop = layout.getLineTop(line).toFloat()
                    val yBottom = layout.getLineBottom(line).toFloat()

                    val left = Math.min(xStart, xEnd)
                    val right = Math.max(xStart, xEnd)

                    val xL = -w / 2f + left
                    val xR = -w / 2f + right
                    val yT = -h / 2f + yTop
                    val yB = -h / 2f + yBottom

                    if (first) {
                        minX = xL - pad; maxX = xR + pad; minY = yT - pad; maxY = yB + pad
                        first = false
                    } else {
                        if (xL - pad < minX) minX = xL - pad
                        if (xR + pad > maxX) maxX = xR + pad
                        if (yT - pad < minY) minY = yT - pad
                        if (yB + pad > maxY) maxY = yB + pad
                    }
                }
            }
        }

        return RenderBounds(
            renderLeft = minX,
            renderRight = maxX,
            renderTop = minY,
            renderBottom = maxY
        )
    }

    fun evaluateFullLayerBezierSurface(u: Float, v: Float, outPoint: FloatArray) {
        val mesh = _warpMesh
        if (mesh == null) {
            outPoint[0] = -getWidth() / 2f + u * getWidth()
            outPoint[1] = -getHeight() / 2f + v * getHeight()
            return
        }
        val rows = _warpRows
        val cols = _warpCols
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

    fun evaluateBezierSurfaceForCharacter(charIndex: Int, u: Float, v: Float, outPoint: FloatArray) {
        val charMesh = letterWarpMeshes[charIndex] ?: return
        val charRows = letterWarpRows[charIndex] ?: 2
        val charCols = letterWarpCols[charIndex] ?: 2
        var x = 0f
        var y = 0f
        for (i in 0..charRows) {
            for (j in 0..charCols) {
                val b_i = bernstein(charRows, i, v)
                val b_j = bernstein(charCols, j, u)
                val basis = b_i * b_j
                val idx = (i * (charCols + 1) + j) * 2
                x += charMesh[idx] * basis
                y += charMesh[idx + 1] * basis
            }
        }
        outPoint[0] = x
        outPoint[1] = y
    }

    override fun evaluateBezierSurface(u: Float, v: Float, outPoint: FloatArray) {
        if (selectedWarpIndex != -1) {
            evaluateBezierSurfaceForCharacter(selectedWarpIndex, u, v, outPoint)
            return
        }
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

    private fun drawCharacterByCharacter(canvas: Canvas, layout: StaticLayout, w: Float, h: Float, ch: Float, qualityScale: Float) {
        val fullText = text.toString()
        val pad = calculatePadding()

        ensureLayout()

        for (i in 0 until fullText.length) {
            val char = fullText[i]
            if (char.isWhitespace()) continue

            // Find unwarped bounds inside full layout
            val line = layout.getLineForOffset(i)
            val xStart = layout.getPrimaryHorizontal(i)
            val xEnd = layout.getPrimaryHorizontal(i + 1)
            val yTop = layout.getLineTop(line).toFloat()
            val yBottom = layout.getLineBottom(line).toFloat()

            val left = Math.min(xStart, xEnd)
            val right = Math.max(xStart, xEnd)
            val charW = (right - left).coerceAtLeast(5f)
            val charH = (yBottom - yTop).coerceAtLeast(5f)

            // Extract single-character text content along with all spans that apply to index i
            val charSb = SpannableStringBuilder(char.toString())
            val spans = text.getSpans(i, i + 1, Any::class.java)
            for (span in spans) {
                val start = text.getSpanStart(span)
                val end = text.getSpanEnd(span)
                if (i in start until end) {
                    val copiedSpan = when (span) {
                        is android.text.style.ForegroundColorSpan -> android.text.style.ForegroundColorSpan(span.foregroundColor)
                        is android.text.style.StyleSpan -> android.text.style.StyleSpan(span.style)
                        is android.text.style.UnderlineSpan -> android.text.style.UnderlineSpan()
                        is android.text.style.StrikethroughSpan -> android.text.style.StrikethroughSpan()
                        is CustomTypefaceSpan -> CustomTypefaceSpan(span.typeface, span.fontPath ?: "")
                        is android.text.style.AbsoluteSizeSpan -> android.text.style.AbsoluteSizeSpan(span.size)
                        is LetterSpacingSpan -> LetterSpacingSpan(span.spacing)
                        else -> null
                    }
                    if (copiedSpan != null) {
                        charSb.setSpan(copiedSpan, 0, 1, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }

            // Create temporary layout for just this character
            val charPaint = TextPaint(textPaint)
            charPaint.textSize = fontSize
            charPaint.color = color
            charPaint.typeface = typeface
            charPaint.alpha = 255
            charPaint.letterSpacing = letterSpacing

            val charLayoutWidth = Math.ceil(StaticLayout.getDesiredWidth(charSb, charPaint).toDouble()).toFloat().coerceAtLeast(5f)
            val charLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(charSb, 0, charSb.length, charPaint, (charLayoutWidth + 10).toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.0f)
                    .build()
            } else {
                StaticLayout(charSb, charPaint, (charLayoutWidth + 10).toInt(), Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false)
            }

            val mesh = letterWarpMeshes[i]
            if (mesh != null) {
                val bmpW = ceil((charW + pad * 2) * qualityScale).toInt()
                val bmpH = ceil((charH + pad * 2) * qualityScale).toInt()

                if (bmpW > 0 && bmpH > 0) {
                    val tempBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                    val tempCanvas = Canvas(tempBmp)
                    tempCanvas.scale(qualityScale, qualityScale)

                    tempCanvas.save()
                    tempCanvas.translate(pad, pad)
                    drawContent(tempCanvas, charLayout, charW, charH, left, yTop, isCharByChar = true)
                    tempCanvas.restore()

                    val meshW = 20
                    val meshH = 20
                    val paddedVerts = FloatArray((meshW + 1) * (meshH + 1) * 2)
                    val outPoint = FloatArray(2)
                    var idx = 0

                    for (rowIdx in 0..meshH) {
                        val v = (rowIdx.toFloat() / meshH) * ((charH + pad * 2) / charH) - (pad / charH)
                        for (colIdx in 0..meshW) {
                            val u = (colIdx.toFloat() / meshW) * ((charW + pad * 2) / charW) - (pad / charW)
                            evaluateBezierSurfaceForCharacter(i, u, v, outPoint)
                            paddedVerts[idx++] = outPoint[0]
                            paddedVerts[idx++] = outPoint[1]
                        }
                    }

                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        canvas.drawBitmapMesh(tempBmp, meshW, meshH, paddedVerts, 0, null, 0, paint)
                    } else {
                        canvas.drawBitmapMesh(tempBmp, meshW, meshH, paddedVerts, 0, null, 0, null)
                    }
                    tempBmp.recycle()
                }
            } else {
                canvas.save()
                canvas.translate(-w / 2f + left, -h / 2f + yTop)
                drawContent(canvas, charLayout, charW, charH, left, yTop, isCharByChar = true)
                canvas.restore()
            }
        }
    }

    private fun getAssembledLetterWarpBitmap(bounds: RenderBounds, layout: StaticLayout, w: Float, h: Float, ch: Float, pad: Float, qualityScale: Float, bmpW: Int, bmpH: Int): Bitmap {
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(qualityScale, qualityScale)
        canvas.translate(-bounds.renderLeft, -bounds.renderTop)
        drawCharacterByCharacter(canvas, layout, w, h, ch, qualityScale)
        return bmp
    }

    private fun drawWarped(canvas: Canvas, layout: StaticLayout, w: Float, h: Float, ch: Float, rows: Int, cols: Int, mesh: FloatArray, qualityScale: Float = 1.0f) {
        val pad = calculatePadding()
        val isFreshBmp = letterWarpMeshes.isNotEmpty()

        val bounds = if (isFreshBmp) {
            getExpandedRenderBounds(layout, w, h, ch, pad)
        } else {
            RenderBounds(
                renderLeft = -w / 2f - pad,
                renderRight = w / 2f + pad,
                renderTop = -h / 2f - pad,
                renderBottom = -h / 2f + ch + pad
            )
        }

        val renderW = bounds.renderRight - bounds.renderLeft
        val renderH = bounds.renderBottom - bounds.renderTop
        val bmpW = ceil(renderW * qualityScale).toInt()
        val bmpH = ceil(renderH * qualityScale).toInt()

        if (bmpW > 0 && bmpH > 0) {
            val finalBmp = if (isFreshBmp) {
                getAssembledLetterWarpBitmap(bounds, layout, w, h, ch, pad, qualityScale, bmpW, bmpH)
            } else {
                getErasedContentBitmap(layout, w, ch, pad, qualityScale, bmpW, bmpH)
            }

            val meshW = 20
            val meshH = 20
            val paddedVerts = FloatArray((meshW + 1) * (meshH + 1) * 2)
            val outPoint = FloatArray(2)
            var idx = 0
            for (i in 0..meshH) {
                val ly = bounds.renderTop + (i.toFloat() / meshH) * renderH
                val v = (ly + h / 2f) / h
                for (j in 0..meshW) {
                    val lx = bounds.renderLeft + (j.toFloat() / meshW) * renderW
                    val u = (lx + w / 2f) / w
                    evaluateFullLayerBezierSurface(u, v, outPoint)
                    paddedVerts[idx++] = outPoint[0]
                    paddedVerts[idx++] = outPoint[1]
                }
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                canvas.drawBitmapMesh(finalBmp, meshW, meshH, paddedVerts, 0, null, 0, paint)
            } else {
                canvas.drawBitmapMesh(finalBmp, meshW, meshH, paddedVerts, 0, null, 0, null)
            }

            if (isFreshBmp) {
                finalBmp.recycle()
            }
        }
    }

    private fun drawCleanContent(canvas: Canvas, layout: StaticLayout, w: Float, h: Float, charLeft: Float = 0f, charTop: Float = 0f, isCharByChar: Boolean = false) {
        val paint = layout.paint
        val fullW = getWidth()
        val fullH = getContentHeight()

        val gradientShader = if (isCharByChar) {
            val shader = getGradientShader(fullW, fullH)
            if (shader != null) {
                val mat = Matrix()
                mat.postTranslate(-charLeft, -charTop)
                shader.setLocalMatrix(mat)
            }
            shader
        } else {
            getGradientShader(w, h)
        }

        var silhouetteColor: Int? = null
        var isDrawingShadowPass = false

        val drawMain = { targetCanvas: Canvas ->
            val originalShader = paint.shader
            val originalColor = paint.color
            val originalStyle = paint.style
            val originalStrokeWidth = paint.strokeWidth
            val originalMaskFilter = paint.maskFilter
            val originalAlpha = paint.alpha

            val iterationAlpha = originalAlpha / 255f

            fun modulateColor(c: Int, ignoreOriginalAlpha: Boolean = false): Int {
                if (!isDrawingShadowPass) return c
                val baseAlpha = if (ignoreOriginalAlpha) 1.0f else (Color.alpha(c) / 255f)
                val a = (baseAlpha * iterationAlpha * 255).toInt().coerceIn(0, 255)
                return (c and 0x00FFFFFF) or (a shl 24)
            }

            // 1. Double Stroke
            if (doubleStrokeWidth > 0f && strokeWidth > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = strokeWidth + doubleStrokeWidth * 2
                paint.shader = null
                paint.color = modulateColor(silhouetteColor ?: doubleStrokeColor, ignoreOriginalAlpha = isDrawingShadowPass)
                paint.clearShadowLayer()
                layout.draw(targetCanvas)
            }

            // 2. Stroke
            if (strokeWidth > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = strokeWidth
                if (silhouetteColor != null) {
                    paint.shader = null
                    paint.color = modulateColor(silhouetteColor!!, ignoreOriginalAlpha = isDrawingShadowPass)
                } else if (isGradient && isGradientStroke) {
                    paint.shader = gradientShader
                    paint.color = modulateColor(Color.WHITE, ignoreOriginalAlpha = isDrawingShadowPass)
                } else {
                    paint.shader = null
                    paint.color = modulateColor(strokeColor, ignoreOriginalAlpha = isDrawingShadowPass)
                }
                paint.clearShadowLayer()
                layout.draw(targetCanvas)
            }

            // 3. Fill
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
            if (silhouetteColor != null) {
                paint.shader = null
                paint.color = modulateColor(silhouetteColor!!)
                paint.clearShadowLayer()
                layout.draw(targetCanvas)
            } else if (isDrawingShadowPass) {
                paint.shader = if (isGradient && isGradientShadow) gradientShader else null
                paint.color = modulateColor(shadowColor)
                paint.clearShadowLayer()
                layout.draw(targetCanvas)
            } else {
                val hasMultiGradient = currentEffect == TextEffectType.MULTI_GRADIENT || secondaryEffect == TextEffectType.MULTI_GRADIENT
                if (hasMultiGradient) {
                    val mShader = if (isCharByChar) {
                        val shader = getMultiGradientShader(fullW, fullH)
                        val mat = Matrix()
                        mat.postTranslate(-charLeft, -charTop)
                        shader.setLocalMatrix(mat)
                        shader
                    } else {
                        getMultiGradientShader(w, h)
                    }
                    paint.shader = mShader
                    paint.color = Color.WHITE
                } else if (isGradient && isGradientText) {
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
                paint.clearShadowLayer()
                layout.draw(targetCanvas)

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
                            } else {
                                cachedPatternShader = null
                            }
                        }

                        if (cachedPatternShader != null) {
                            paint.shader = cachedPatternShader
                            paint.color = Color.WHITE
                            paint.alpha = patternAlpha
                            paint.xfermode = cachedPatternXfermode
                            layout.draw(targetCanvas)

                            // Restore
                            paint.xfermode = null
                            paint.alpha = originalAlpha
                        }
                    }
                }
            }

            // Restore
            paint.shader = originalShader
            paint.color = originalColor
            paint.style = originalStyle
            paint.strokeWidth = originalStrokeWidth
            paint.maskFilter = originalMaskFilter
            paint.alpha = originalAlpha
        }

        val drawShadows = { targetCanvas: Canvas ->
            val originalShader = paint.shader
            val originalColor = paint.color
            val originalStyle = paint.style
            val originalStrokeWidth = paint.strokeWidth
            val originalMaskFilter = paint.maskFilter
            val originalAlpha = paint.alpha

            // 1. Motion Shadow
            if (isMotionShadow && motionShadowDistance > 0) {
                paint.style = Paint.Style.FILL
                paint.shader = if (isGradient && isGradientShadow) gradientShader else null
                paint.color = shadowColor

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
                    val iterationAlpha = (initialShadowAlpha * (1f - t)).toInt().coerceIn(0, 255)
                    paint.alpha = iterationAlpha
                    val blur = t * maxBlur
                    if (blur > 0.5f) {
                        paint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                    } else {
                        paint.maskFilter = null
                    }
                    val dx = d * cos
                    val dy = d * sin

                    val drawIteration = { canvas: Canvas ->
                        if (isMotionShadowIncludeStroke) {
                            isDrawingShadowPass = true
                            drawMain(canvas)
                            isDrawingShadowPass = false
                        } else {
                            layout.draw(canvas)
                        }
                    }

                    targetCanvas.save()
                    targetCanvas.translate(dx, dy)
                    drawIteration(targetCanvas)
                    targetCanvas.restore()

                    targetCanvas.save()
                    targetCanvas.translate(-dx, -dy)
                    drawIteration(targetCanvas)
                    targetCanvas.restore()
                }
                paint.maskFilter = null
                paint.alpha = originalAlpha
            }

            // 2. Standard Shadow
            if (!isMotionShadow && shadowRadius > 0) {
                if (isGradient && isGradientShadow) {
                    paint.shader = gradientShader
                    paint.color = Color.WHITE
                    paint.maskFilter = BlurMaskFilter(shadowRadius, BlurMaskFilter.Blur.NORMAL)
                    targetCanvas.save()
                    targetCanvas.translate(shadowDx, shadowDy)
                    layout.draw(targetCanvas)
                    targetCanvas.restore()
                } else {
                    paint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
                    layout.draw(targetCanvas)
                    paint.clearShadowLayer()
                }
            }

            // Restore
            paint.shader = originalShader
            paint.color = originalColor
            paint.style = originalStyle
            paint.strokeWidth = originalStrokeWidth
            paint.maskFilter = originalMaskFilter
            paint.alpha = originalAlpha
        }

        drawShadows(canvas)
        val drawBase = { innerCanvas: Canvas -> drawMain(innerCanvas) }

        // Setup effects chain
        val activeEffects = mutableListOf<TextEffectType>()
        if (currentEffect != TextEffectType.NONE && currentEffect != TextEffectType.MULTI_GRADIENT) activeEffects.add(currentEffect)
        if (secondaryEffect != TextEffectType.NONE && secondaryEffect != TextEffectType.MULTI_GRADIENT) activeEffects.add(secondaryEffect)

        fun applyEffect(effect: TextEffectType, targetCanvas: Canvas, drawInner: (Canvas) -> Unit) {
            when (effect) {
                TextEffectType.CHROMATIC_ABERRATION -> {
                    val originalXfermode = paint.xfermode
                    val originalColor = paint.color
                    val originalShader = paint.shader
                    val prevSilhouette = silhouetteColor

                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                    paint.shader = null

                    // Left Layer
                    silhouetteColor = chromaticColors[0]
                    targetCanvas.save()
                    targetCanvas.translate(-chromaticShift, 0f)
                    drawInner(targetCanvas)
                    targetCanvas.restore()

                    // Right Layer
                    silhouetteColor = chromaticColors[1]
                    targetCanvas.save()
                    targetCanvas.translate(chromaticShift, 0f)
                    drawInner(targetCanvas)
                    targetCanvas.restore()

                    // Center Layer
                    silhouetteColor = chromaticColors[2]
                    drawInner(targetCanvas)

                    silhouetteColor = prevSilhouette

                    paint.xfermode = originalXfermode
                    paint.color = originalColor
                    paint.shader = originalShader
                }
                TextEffectType.PIXELATION -> {
                    val pad = calculatePadding()
                    val safeBlockSize = pixelBlockSize.coerceAtLeast(1f)
                    val scaleFactor = 1f / safeBlockSize

                    val scaledW = ((w + pad * 2) * scaleFactor).toInt().coerceAtLeast(1)
                    val scaledH = ((h + pad * 2) * scaleFactor).toInt().coerceAtLeast(1)

                    val currentHash = listOf(
                        text.toString(), w, h, color, fontSize, safeBlockSize,
                        strokeWidth, strokeColor, doubleStrokeWidth, doubleStrokeColor,
                        shadowRadius, shadowColor, shadowDx, shadowDy,
                        isGradient, isGradientStroke, isGradientShadow, isGradientText,
                        currentEffect, secondaryEffect, letterSpacing, lineSpacing, typeface, pad
                    ).hashCode()

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
                        val pixelPaint = Paint()
                        pixelPaint.isFilterBitmap = false
                        val destRect = RectF(-pad, -pad, w + pad, h + pad)
                        targetCanvas.drawBitmap(cachedPixelBitmap!!, null, destRect, pixelPaint)
                    }
                }
                TextEffectType.GLITCH -> {
                    val pad = calculatePadding()
                    val random = Random(effectSeed)
                    val slices = mutableListOf<Pair<RectF, Float>>()

                    var currentY = -pad
                    // Max strip height: say 15% of total height, min height 2%
                    val maxStripHeight = h * 0.15f
                    val minStripHeight = h * 0.02f

                    while (currentY < h + pad) {
                        // Determine random strip height
                        var stripHeight = minStripHeight + (random.nextFloat() * (maxStripHeight - minStripHeight))
                        if (stripHeight < 1f) stripHeight = 1f // Prevent infinite loop on extremely small layers

                        val bottom = kotlin.math.min(currentY + stripHeight, h + pad)

                        // Decide if this slice should shift (50% chance)
                        val xOffset = if (random.nextFloat() < 0.5f) {
                            (random.nextFloat() - 0.5f) * 100f * glitchIntensity
                        } else {
                            0f
                        }

                        slices.add(Pair(RectF(-pad, currentY, w + pad, bottom), xOffset))

                        // Guard against floating point precision stagnation
                        if (bottom <= currentY) {
                            break
                        }
                        currentY = bottom
                    }

                    for (slice in slices) {
                        val rect = slice.first
                        val xOffset = slice.second

                        targetCanvas.save()
                        targetCanvas.clipRect(rect)
                        targetCanvas.translate(xOffset, 0f)

                        drawInner(targetCanvas)

                        targetCanvas.restore()
                    }
                }
                TextEffectType.NEON -> {
                    val originalColor = paint.color
                    val originalStyle = paint.style
                    val originalMaskFilter = paint.maskFilter
                    val prevSilhouette = silhouetteColor

                    paint.style = Paint.Style.FILL
                    silhouetteColor = if (neonColor != Color.CYAN) neonColor else color
                    paint.maskFilter = BlurMaskFilter(neonRadius.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)

                    drawInner(targetCanvas)

                    paint.maskFilter = null
                    silhouetteColor = prevSilhouette
                    drawInner(targetCanvas)

                    paint.color = originalColor
                    paint.style = originalStyle
                    paint.maskFilter = originalMaskFilter
                }
                TextEffectType.LONG_SHADOW -> {
                    val originalColor = paint.color
                    val prevSilhouette = silhouetteColor
                    silhouetteColor = longShadowColor
                    val shadowLen = longShadowLength.toInt().coerceAtLeast(1)
                    val rad = Math.toRadians(longShadowAngle.toDouble())
                    val xStep = cos(rad).toFloat()
                    val yStep = sin(rad).toFloat()

                    for (i in 1..shadowLen) {
                        targetCanvas.save()
                        targetCanvas.translate(i.toFloat() * xStep, i.toFloat() * yStep)
                        drawInner(targetCanvas)
                        targetCanvas.restore()
                    }

                    silhouetteColor = prevSilhouette
                    drawInner(targetCanvas)
                    paint.color = originalColor
                }
                TextEffectType.FIERY -> {
                    val pad = calculatePadding()
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("FieryNode")
                            node.setPosition(0, 0, (w + pad * 2).toInt(), (h + pad * 2).toInt())
                            val recordingCanvas = node.beginRecording()
                            recordingCanvas.translate(pad, pad)
                            drawInner(recordingCanvas)
                            node.endRecording()

                            val shader = android.graphics.RuntimeShader(FIERY_SHADER)
                            shader.setFloatUniform("time", (System.currentTimeMillis() % 100000) / 1000f)
                            shader.setFloatUniform("intensity", fieryIntensity)
                            val r = Color.red(fieryColor) / 255f
                            val g = Color.green(fieryColor) / 255f
                            val b = Color.blue(fieryColor) / 255f
                            shader.setFloatUniform("color", r, g, b)

                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.save()
                            targetCanvas.translate(-pad, -pad)
                            targetCanvas.drawRenderNode(node)
                            targetCanvas.restore()
                            useRenderEffect = true
                        } catch(e: Exception) {}
                    }
                    if (!useRenderEffect) drawInner(targetCanvas)
                }
                TextEffectType.WAVY -> {
                    val pad = calculatePadding()
                    var useRenderEffect = false
                    val isTransformed = isWarp || isPerspective
                    if (!isTransformed && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("WavyNode")
                            node.setPosition(0, 0, (w + pad * 2).toInt(), (h + pad * 2).toInt())
                            val recordingCanvas = node.beginRecording()
                            recordingCanvas.translate(pad, pad)
                            drawInner(recordingCanvas)
                            node.endRecording()

                            val shader = android.graphics.RuntimeShader(WAVY_SHADER)
                            shader.setFloatUniform("time", (System.currentTimeMillis() % 100000) / 1000f)
                            shader.setFloatUniform("intensity", wavyIntensity)
                            shader.setFloatUniform("frequency", wavyFrequency)

                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.save()
                            targetCanvas.translate(-pad, -pad)
                            targetCanvas.drawRenderNode(node)
                            targetCanvas.restore()
                            useRenderEffect = true
                        } catch(e: Exception) {}
                    }
                    if (!useRenderEffect) {
                        val bmpW = ceil(w + pad * 2).toInt()
                        val bmpH = ceil(h + pad * 2).toInt()

                        if (bmpW > 0 && bmpH > 0) {
                            val currentHash = listOf(
                                text.toString(), w, h, color, fontSize,
                                strokeWidth, strokeColor, doubleStrokeWidth, doubleStrokeColor,
                                letterSpacing, lineSpacing, typeface, pad
                            ).hashCode()

                            if (cachedWavyBitmap == null || cachedWavyBitmap!!.width != bmpW || cachedWavyBitmap!!.height != bmpH || cachedWavyHash != currentHash) {
                                cachedWavyBitmap?.recycle()
                                val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                                val c = Canvas(bitmap)
                                c.translate(pad, pad)
                                drawInner(c)
                                cachedWavyBitmap = bitmap
                                cachedWavyHash = currentHash
                            }

                            val meshW = 20
                            val meshH = 20
                            val verts = FloatArray((meshW + 1) * (meshH + 1) * 2)
                            val time = (System.currentTimeMillis() % 100000) / 1000f
                            var idx = 0
                            for (i in 0..meshH) {
                                val v = i.toFloat() / meshH
                                val y = v * bmpH
                                val offset = sin(y * 0.05 * wavyFrequency + time * 5.0).toFloat() * wavyIntensity * 10.0f
                                for (j in 0..meshW) {
                                    val u = j.toFloat() / meshW
                                    val x = u * bmpW
                                    verts[idx++] = x + offset
                                    verts[idx++] = y
                                }
                            }
                            targetCanvas.save()
                            targetCanvas.translate(-pad, -pad)
                            targetCanvas.drawBitmapMesh(cachedWavyBitmap!!, meshW, meshH, verts, 0, null, 0, null)
                            targetCanvas.restore()
                        } else {
                            drawInner(targetCanvas)
                        }
                    }
                }
                TextEffectType.PARTICLE_DISSOLVE -> {
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("ParticleNode")
                            node.setPosition(0, 0, w.toInt(), h.toInt())
                            val recordingCanvas = node.beginRecording()
                            drawInner(recordingCanvas)
                            node.endRecording()

                            val shader = android.graphics.RuntimeShader(PARTICLE_SHADER)
                            shader.setFloatUniform("particleSize", particleSize)
                            shader.setFloatUniform("spread", particleSpread)
                            shader.setFloatUniform("seed", effectSeed.toFloat())
                            shader.setFloatUniform("angle", particleDissolveAngle)
                            shader.setFloatUniform("size", w, h)

                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.drawRenderNode(node)
                            useRenderEffect = true
                        } catch(e: Exception) {}
                    }
                    if (!useRenderEffect) drawInner(targetCanvas)
                }
                TextEffectType.GAUSSIAN_BLUR -> {
                    val pad = calculatePadding()
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("GaussianBlurNode")
                            val wFull = (w + pad * 2).toInt().coerceAtLeast(1)
                            val hFull = (h + pad * 2).toInt().coerceAtLeast(1)
                            node.setPosition(0, 0, wFull, hFull)

                            val recordingCanvas = node.beginRecording()
                            recordingCanvas.translate(pad, pad)
                            drawInner(recordingCanvas)
                            node.endRecording()

                            val r = blurRadius.coerceAtLeast(0.1f)
                            node.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))

                            targetCanvas.save()
                            targetCanvas.translate(-pad, -pad)
                            targetCanvas.drawRenderNode(node)
                            targetCanvas.restore()
                            useRenderEffect = true
                        } catch (e: Exception) {}
                    }

                    if (!useRenderEffect) {
                        val originalMask = paint.maskFilter
                        if (blurRadius > 0) {
                            paint.maskFilter = BlurMaskFilter(blurRadius.coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL)
                        }
                        drawInner(targetCanvas)
                        paint.maskFilter = originalMask
                    }
                }
                TextEffectType.MOTION_BLUR -> {
                    val pad = calculatePadding()
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("MotionBlurNode")
                            val wFull = (w + pad * 2).toInt().coerceAtLeast(1)
                            val hFull = (h + pad * 2).toInt().coerceAtLeast(1)
                            node.setPosition(0, 0, wFull, hFull)

                            val recordingCanvas = node.beginRecording()
                            recordingCanvas.translate(pad, pad)
                            drawInner(recordingCanvas)
                            node.endRecording()

                            val shader = android.graphics.RuntimeShader(MOTION_BLUR_SHADER)
                            val rad = Math.toRadians(motionBlurAngle.toDouble())
                            shader.setFloatUniform("direction", Math.cos(rad).toFloat(), Math.sin(rad).toFloat())
                            shader.setFloatUniform("length", motionBlurLength)

                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.save()
                            targetCanvas.translate(-pad, -pad)
                            targetCanvas.drawRenderNode(node)
                            targetCanvas.restore()
                            useRenderEffect = true
                        } catch (e: Exception) {}
                    }

                    if (!useRenderEffect) {
                        val originalMask = paint.maskFilter
                        val fallbackBlur = motionBlurLength / 2f
                        if (fallbackBlur > 0) {
                            paint.maskFilter = BlurMaskFilter(fallbackBlur.coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL)
                        }
                        drawInner(targetCanvas)
                        paint.maskFilter = originalMask
                    }
                }
                TextEffectType.RADIAL_BLUR -> {
                    val pad = calculatePadding()
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("RadialBlurNode")
                            val wFull = (w + pad * 2).toInt().coerceAtLeast(1)
                            val hFull = (h + pad * 2).toInt().coerceAtLeast(1)
                            node.setPosition(0, 0, wFull, hFull)

                            val recordingCanvas = node.beginRecording()
                            recordingCanvas.translate(pad, pad)
                            drawInner(recordingCanvas)
                            node.endRecording()

                            val shader = android.graphics.RuntimeShader(RADIAL_BLUR_SHADER)
                            shader.setFloatUniform("center", w / 2f + pad, h / 2f + pad)
                            shader.setFloatUniform("innerRadius", radialBlurInnerRadius)
                            shader.setFloatUniform("motionStrength", radialBlurMotionStrength)
                            shader.setFloatUniform("size", w + pad * 2, h + pad * 2)

                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.save()
                            targetCanvas.translate(-pad, -pad)
                            targetCanvas.drawRenderNode(node)
                            targetCanvas.restore()
                            useRenderEffect = true
                        } catch (e: Exception) {}
                    }
                    if (!useRenderEffect) {
                        // Software Fallback for Warp/Perspective or older devices
                        val bmpW = ceil(w + pad * 2).toInt()
                        val bmpH = ceil(h + pad * 2).toInt()
                        if (bmpW > 0 && bmpH > 0) {
                            val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                            val c = Canvas(bitmap)
                            c.translate(pad, pad)
                            drawInner(c)

                            val center = PointF(bmpW / 2f, bmpH / 2f)
                            val motionRad = Math.toRadians(radialBlurMotionStrength.toDouble())
                            val zoomBase = (1.0 - (radialBlurMotionStrength / 180.0).coerceIn(0.0, 1.0)) * 0.03

                            val fbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

                            targetCanvas.save()
                            targetCanvas.translate(-pad, -pad)

                            // Multi-pass iterative rendering for software fallback (Warp/Perspective)
                            // We need to blend multiple blurred copies to avoid "ghosting" artifacts

                            // 1. Create a pre-blurred source to smooth out iterations
                            val preBlurredBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                            val preBlurCanvas = Canvas(preBlurredBmp)
                            val preBlurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                // Add a light blur to the source to smear iterations together
                                maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)
                            }
                            preBlurCanvas.drawBitmap(bitmap, 0f, 0f, preBlurPaint)

                            // 2. Iterative Drawing
                            val blurBitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                            val blurCanvas = Canvas(blurBitmap)
                            val iterations = 15 // Increased iterations for smoothness
                            val itPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

                            // Boost zoomBase slightly for software fallback to make it more noticeable
                            val effectiveZoomBase = zoomBase * 1.5

                            for (i in -iterations..iterations) {
                                val t = i / iterations.toFloat()
                                val angle = t * motionRad
                                val zoom = 1.0 + (t * effectiveZoomBase)

                                val m = Matrix()
                                m.postTranslate(-center.x, -center.y)
                                m.postScale(zoom.toFloat(), zoom.toFloat())
                                m.postRotate(Math.toDegrees(angle).toFloat())
                                m.postTranslate(center.x, center.y)

                                // Exponential falloff for weights to favor the center
                                val weight = (1.0 - Math.abs(t) * 0.5) / (iterations * 1.5)
                                itPaint.alpha = (weight * 255).toInt().coerceIn(1, 255)
                                blurCanvas.drawBitmap(preBlurredBmp, m, itPaint)
                            }

                            // 3. Prepare Final Composite
                            val finalBitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                            val finalCanvas = Canvas(finalBitmap)

                            // a. Draw Blurred Base
                            finalCanvas.drawBitmap(blurBitmap, 0f, 0f, null)

                            // b. Draw Sharp Center using masking
                            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

                            val sharpBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                            val sharpCanvas = Canvas(sharpBitmap)

                            val transitionWidth = Math.max(80f, radialBlurInnerRadius * 0.4f)
                            val grad = android.graphics.RadialGradient(
                                center.x, center.y, radialBlurInnerRadius + transitionWidth,
                                intArrayOf(Color.TRANSPARENT, Color.BLACK),
                                floatArrayOf(radialBlurInnerRadius / (radialBlurInnerRadius + transitionWidth), 1f),
                                Shader.TileMode.CLAMP
                            )
                            maskPaint.shader = grad
                            sharpCanvas.drawCircle(center.x, center.y, radialBlurInnerRadius + transitionWidth, maskPaint)

                            finalCanvas.drawBitmap(sharpBitmap, 0f, 0f, null)

                            // 4. Output to target
                            targetCanvas.drawBitmap(finalBitmap, 0f, 0f, fbPaint)

                            // Cleanup
                            preBlurredBmp.recycle()
                            blurBitmap.recycle()
                            finalBitmap.recycle()
                            sharpBitmap.recycle()

                            targetCanvas.restore()
                            bitmap.recycle()
                            blurBitmap.recycle()
                        } else {
                            drawInner(targetCanvas)
                        }
                    }
                }
                TextEffectType.HALFTONE -> {
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("HalftoneNode")
                            val wInt = w.toInt().coerceAtLeast(1)
                            val hInt = h.toInt().coerceAtLeast(1)
                            node.setPosition(0, 0, wInt, hInt)

                            val recordingCanvas = node.beginRecording()
                            drawInner(recordingCanvas)
                            node.endRecording()

                            val shader = android.graphics.RuntimeShader(HALFTONE_SHADER)
                            shader.setFloatUniform("dotSize", halftoneDotSize.coerceAtLeast(1f))
                            shader.setFloatUniform("threshold", halftoneThreshold)
                            val r = Color.red(halftoneDotColor) / 255f
                            val g = Color.green(halftoneDotColor) / 255f
                            val b = Color.blue(halftoneDotColor) / 255f
                            shader.setFloatUniform("dotColor", r, g, b)

                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.drawRenderNode(node)
                            useRenderEffect = true
                        } catch (e: Exception) {}
                    }

                    if (!useRenderEffect) {
                        drawInner(targetCanvas)
                    }
                }
                TextEffectType.TEXT_DECAY -> {
                    var useRenderEffect = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
                        try {
                            val node = android.graphics.RenderNode("DecayNode")
                            val wInt = w.toInt().coerceAtLeast(1)
                            val hInt = h.toInt().coerceAtLeast(1)
                            node.setPosition(0, 0, wInt, hInt)

                            val recordingCanvas = node.beginRecording()
                            drawInner(recordingCanvas)
                            node.endRecording()

                            val shader = android.graphics.RuntimeShader(TEXT_DECAY_SHADER)
                            shader.setFloatUniform("intensity", decayIntensity)
                            shader.setFloatUniform("fadingLevel", decayFadingLevel)
                            shader.setFloatUniform("seed", (effectSeed % 10000).toFloat())
                            shader.setFloatUniform("size", w, h)

                            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            targetCanvas.drawRenderNode(node)
                            useRenderEffect = true
                        } catch (e: Exception) {}
                    }
                    if (!useRenderEffect) {
                        drawTextDecaySoftware(targetCanvas, w, h, drawInner)
                    }
                }
                else -> {
                    drawInner(targetCanvas)
                }
            }
        }

        if (activeEffects.size == 2) {
            applyEffect(activeEffects[1], canvas) { innerCanvas ->
                applyEffect(activeEffects[0], innerCanvas, drawBase)
            }
        } else if (activeEffects.size == 1) {
            applyEffect(activeEffects[0], canvas, drawBase)
        } else {
            drawBase(canvas)
        }

    }

    private fun drawContent(canvas: Canvas, layout: StaticLayout, w: Float, h: Float, charLeft: Float = 0f, charTop: Float = 0f, isCharByChar: Boolean = false) {
        drawCleanContent(canvas, layout, w, h, charLeft, charTop, isCharByChar)

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

    private fun drawTextDecaySoftware(targetCanvas: Canvas, w: Float, h: Float, drawInner: (Canvas) -> Unit) {
        val pad = calculatePadding()
        val bmpW = ceil(w + pad * 2).toInt()
        val bmpH = ceil(h + pad * 2).toInt()

        if (bmpW <= 0 || bmpH <= 0) {
            drawInner(targetCanvas)
            return
        }

        // 1. Render source text to bitmap
        val srcBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val srcCanvas = Canvas(srcBmp)
        srcCanvas.translate(pad, pad)
        drawInner(srcCanvas)

        // 2. Generate multi-octave noise
        val random = Random(effectSeed)
        fun generateNoise(scale: Float): Bitmap {
            val nW = (bmpW * scale).toInt().coerceAtLeast(1)
            val nH = (bmpH * scale).toInt().coerceAtLeast(1)
            val b = Bitmap.createBitmap(nW, nH, Bitmap.Config.ARGB_8888)
            for (y in 0 until nH) for (x in 0 until nW) {
                val n = random.nextInt(256)
                b.setPixel(x, y, Color.rgb(n, n, n))
            }
            val scaled = Bitmap.createScaledBitmap(b, bmpW, bmpH, true)
            b.recycle()
            return scaled
        }

        val noise1 = generateNoise(0.1f)
        val noise2 = generateNoise(0.2f)
        val noise3 = generateNoise(0.4f)

        val pixels = IntArray(bmpW * bmpH)
        val n1 = IntArray(bmpW * bmpH)
        val n2 = IntArray(bmpW * bmpH)
        val n3 = IntArray(bmpW * bmpH)

        srcBmp.getPixels(pixels, 0, bmpW, 0, 0, bmpW, bmpH)
        noise1.getPixels(n1, 0, bmpW, 0, 0, bmpW, bmpH)
        noise2.getPixels(n2, 0, bmpW, 0, 0, bmpW, bmpH)
        noise3.getPixels(n3, 0, bmpW, 0, 0, bmpW, bmpH)

        val threshold = 1.1f - (decayIntensity * 1.1f)
        val softness = decayFadingLevel * 0.4f + 0.01f

        fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        for (i in pixels.indices) {
            val color = pixels[i]
            val alpha = Color.alpha(color) / 255f
            if (alpha <= 0f) continue

            // Combine noise octaves (normalized to 0..1)
            var n = (Color.red(n1[i]) / 255f)
            n += (Color.red(n2[i]) / 255f) * 0.5f
            n += (Color.red(n3[i]) / 255f) * 0.25f
            n /= 1.75f

            // Edge weight heuristic from shader
            val edgeWeight = (1.0f - alpha) * 0.5f
            val valCombined = n + edgeWeight

            // Apply smoothstep mask
            val mask = smoothstep(threshold - softness, threshold + softness, valCombined)
            val finalAlpha = (alpha * (1.0f - mask) * 255f).toInt().coerceIn(0, 255)

            pixels[i] = (color and 0x00FFFFFF) or (finalAlpha shl 24)
        }

        srcBmp.setPixels(pixels, 0, bmpW, 0, 0, bmpW, bmpH)

        targetCanvas.save()
        targetCanvas.translate(-pad, -pad)
        targetCanvas.drawBitmap(srcBmp, 0f, 0f, null)
        targetCanvas.restore()

        // Cleanup
        srcBmp.recycle()
        noise1.recycle()
        noise2.recycle()
        noise3.recycle()
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

    companion object {

        const val MOTION_BLUR_SHADER = """
            uniform shader content;
            uniform float2 direction;
            uniform float length;

            float rand(float2 co) {
                return fract(sin(dot(co, float2(12.9898, 78.233))) * 43758.5453);
            }

            half4 main(float2 coord) {
                half4 color = half4(0);
                float total = 0.0;
                float noise = (rand(coord) - 0.5) * 0.1;

                for (float i = 0.0; i <= 50.0; i += 1.0) {
                    float t = i / 50.0;
                    float offset = (t - 0.5 + noise) * length;
                    color += content.eval(coord + direction * offset);
                    total += 1.0;
                }
                return color / total;
            }
        """

        const val HALFTONE_SHADER = """
            uniform shader content;
            uniform float dotSize;
            uniform float threshold;
            uniform float3 dotColor;

            half4 main(float2 coord) {
                half4 c = content.eval(coord);
                if (c.a == 0.0) return half4(0);

                float2 gridPos = floor(coord / dotSize);
                float2 center = (gridPos + 0.5) * dotSize;
                float dist = distance(coord, center);
                float radius = dotSize * 0.5 * threshold;

                if (dist < radius) {
                    return half4(dotColor * c.a, c.a);
                }
                return half4(0);
            }
        """

        const val CRT_SHADER = """
            uniform shader content;
            uniform float lineHeight;
            uniform float intensity;

            half4 main(float2 coord) {
                half4 c = content.eval(coord);
                if (c.a == 0.0) return half4(0);

                float line = sin(coord.y * 3.14159 / lineHeight);
                float factor = 1.0 - (intensity * 0.5 * (1.0 - line));

                return half4(c.rgb * factor, c.a);
            }
        """

        const val FIERY_SHADER = """
            uniform shader content;
            uniform float time;
            uniform float intensity;
            uniform float3 color;

            // Simple noise function (placeholder)
            float noise(float2 co) {
                return fract(sin(dot(co, float2(12.9898, 78.233))) * 43758.5453);
            }

            half4 main(float2 coord) {
                half4 c = content.eval(coord);
                if (c.a == 0.0) return half4(0);

                // Displace coordinate upwards with noise
                float2 uv = coord;
                float n = noise(uv * 0.01 + float2(0, time));
                uv.y += n * intensity * 20.0;

                half4 displaced = content.eval(uv);

                // Mix with fire color
                return mix(c, half4(color, 1.0) * c.a, intensity * n);
            }
        """

        const val WAVY_SHADER = """
            uniform shader content;
            uniform float time;
            uniform float intensity;
            uniform float frequency;

            half4 main(float2 coord) {
                float offset = sin(coord.y * 0.05 * frequency + time * 5.0) * intensity * 10.0;
                return content.eval(coord + float2(offset, 0));
            }
        """

        const val RADIAL_BLUR_SHADER = """
            uniform shader content;
            uniform float2 center;
            uniform float innerRadius;
            uniform float motionStrength;
            uniform float2 size;

            half4 main(float2 coord) {
                float dist = distance(coord, center);

                // Smooth transition: 0 blur inside innerRadius
                // Increased transition zone for better smoothness at larger radii
                float transitionWidth = max(80.0, innerRadius * 0.4);
                float blurAmount = smoothstep(innerRadius, innerRadius + transitionWidth, dist);

                if (blurAmount <= 0.0) {
                    return content.eval(coord);
                }

                half4 color = half4(0);
                float totalWeight = 0.0;

                float angleRad = radians(motionStrength);

                // Samples along the arc (Clockwise and Counter-Clockwise)
                // If motionStrength is 0, angleRad is 0, effectively no spin.
                // We add a base zoom blur if motionStrength is small to satisfy "regular radial blur"

                float zoomBase = (1.0 - clamp(motionStrength / 30.0, 0.0, 1.0)) * 0.03;

                for (float i = -10.0; i <= 10.0; i += 1.0) {
                    float t = i / 10.0;
                    // Rotation should also scale with blurAmount for smooth transition
                    float currentAngle = t * angleRad * blurAmount;

                    float s = sin(currentAngle);
                    float c = cos(currentAngle);

                    float2 rel = coord - center;
                    float2 rotatedRel = float2(
                        rel.x * c - rel.y * s,
                        rel.x * s + rel.y * c
                    );

                    // Zoom component: 1.0 is no zoom, >1.0 is zoom in
                    // We apply more zoom at the edges
                    float zoom = 1.0 + (t * zoomBase * blurAmount);

                    float2 sampledCoord = center + rotatedRel * zoom;

                    half4 sampleColor = content.eval(sampledCoord);

                    // Fade weight for smoother motion trail
                    float weight = 1.0 - abs(t) * 0.3;
                    color += sampleColor * weight;
                    totalWeight += weight;
                }

                return color / totalWeight;
            }
        """

        const val TEXT_DECAY_SHADER = """
            uniform shader content;
            uniform float intensity;
            uniform float fadingLevel;
            uniform float seed;
            uniform float2 size;

            float rand(float2 co) {
                return fract(sin(dot(co, float2(12.9898, 78.233))) * 43758.5453);
            }

            float noise(float2 p) {
                float2 i = floor(p);
                float2 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float a = rand(i);
                float b = rand(i + float2(1.0, 0.0));
                float c = rand(i + float2(0.0, 1.0));
                float d = rand(i + float2(1.0, 1.0));
                return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
            }

            half4 main(float2 coord) {
                half4 c = content.eval(coord);
                if (c.a == 0.0) return half4(0);

                float n = noise(coord * 0.1 + seed);
                n += noise(coord * 0.2 + seed * 1.1) * 0.5;
                n += noise(coord * 0.4 + seed * 1.2) * 0.25;
                n /= 1.75;

                float edgeWeight = (1.0 - c.a) * 0.5;
                float val = n + edgeWeight;

                float threshold = 1.1 - (intensity * 1.1);
                float softness = fadingLevel * 0.4 + 0.01;
                float mask = smoothstep(threshold - softness, threshold + softness, val);

                return half4(c.rgb, c.a * (1.0 - mask));
            }
        """

        const val PARTICLE_SHADER = """
            uniform shader content;
            uniform float particleSize;
            uniform float spread;
            uniform float seed;
            uniform float angle;
            uniform float2 size;

            float noise(float2 co) {
                 return fract(sin(dot(co, float2(12.9898, 78.233))) * 43758.5453);
            }

            half4 main(float2 coord) {
                // Snap to grid
                float2 grid = floor(coord / particleSize) * particleSize;
                float n = noise(grid + seed);

                // Calculate direction
                float rad = radians(angle);
                float2 dir = float2(cos(rad), sin(rad));

                // Project coordinate onto direction
                float2 center = size / 2.0;
                float2 p = coord - center;
                float dist = dot(p, dir);

                // Max distance approx
                float maxDist = length(size) / 2.0;
                float normDist = dist / maxDist; // -1 to 1

                // Probability of survival
                // spread controls the extent of the dissolve region
                // We map normDist to a threshold.
                // We want: if spread is 0, full survival.
                // If spread is 1, full dissolve (eventually).
                // Let's model it such that pixels 'further' in the direction are more likely to dissolve.

                // Threshold ramp:
                // We want survival probability to decrease as normDist increases.
                // t goes from 0 (at -dir) to 1 (at +dir)
                float t = (normDist + 1.0) / 2.0;

                // Effective threshold based on spread.
                // If spread is 0.5, we want the last 50% to start dissolving?
                // Or maybe spread controls density.

                // User Request: "Dissolve into particles at the edges"
                // Let's say:
                // If t > (1.0 - spread), we start dissolving.
                // The probability of keeping the pixel decreases linearly or smoothly.

                float startDissolve = 1.0 - spread;
                float prob = 1.0;

                if (t > startDissolve) {
                     // Remap t from [startDissolve, 1.0] to [1.0, 0.0]
                     prob = 1.0 - ((t - startDissolve) / max(0.001, spread));
                }

                if (n > prob) return half4(0);

                return content.eval(coord);
            }
        """
    }
}
