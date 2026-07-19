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
import com.astral.typer.TyperApplication

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

    // Standard touch stroke points
    private var lastX: Float = 0f
    private var lastY: Float = 0f

    // Dynamic input state variables
    private var lastEventTime: Long = 0L

    // MyPaint brush engine state variables (Matching libmypaint 1.4+ exactly)
    private val states = FloatArray(STATES_COUNT)
    private val smudgeBuckets = FloatArray(256 * 9)
    private val settingsValue = HashMap<String, Float>()

    private var skip: Float = 0f
    private var skipLastX: Float = 0f
    private var skipLastY: Float = 0f
    private var skippedDtime: Float = 0f
    private var randomInput: Float = 0f

    private val speedMappingGamma = FloatArray(2)
    private val speedMappingM = FloatArray(2)
    private val speedMappingQ = FloatArray(2)

    companion object {
        const val STATE_X = 0
        const val STATE_Y = 1
        const val STATE_PRESSURE = 2
        const val STATE_PARTIAL_DABS = 3
        const val STATE_ACTUAL_RADIUS = 4
        const val STATE_SMUDGE_RA = 5
        const val STATE_SMUDGE_GA = 6
        const val STATE_SMUDGE_BA = 7
        const val STATE_SMUDGE_A = 8
        const val STATE_LAST_GETCOLOR_R = 9
        const val STATE_LAST_GETCOLOR_G = 10
        const val STATE_LAST_GETCOLOR_B = 11
        const val STATE_LAST_GETCOLOR_A = 12
        const val STATE_LAST_GETCOLOR_RECENTNESS = 13
        const val STATE_ACTUAL_X = 14
        const val STATE_ACTUAL_Y = 15
        const val STATE_NORM_DX_SLOW = 16
        const val STATE_NORM_DY_SLOW = 17
        const val STATE_NORM_SPEED1_SLOW = 18
        const val STATE_NORM_SPEED2_SLOW = 19
        const val STATE_STROKE = 20
        const val STATE_STROKE_STARTED = 21
        const val STATE_CUSTOM_INPUT = 22
        const val STATE_RNG_SEED = 23
        const val STATE_ACTUAL_ELLIPTICAL_DAB_RATIO = 24
        const val STATE_ACTUAL_ELLIPTICAL_DAB_ANGLE = 25
        const val STATE_DIRECTION_DX = 26
        const val STATE_DIRECTION_DY = 27
        const val STATE_DECLINATION = 28
        const val STATE_ASCENSION = 29
        const val STATE_VIEWZOOM = 30
        const val STATE_VIEWROTATION = 31
        const val STATE_DIRECTION_ANGLE_DX = 32
        const val STATE_DIRECTION_ANGLE_DY = 33
        const val STATE_ATTACK_ANGLE = 34
        const val STATE_FLIP = 35
        const val STATE_GRIDMAP_X = 36
        const val STATE_GRIDMAP_Y = 37
        const val STATE_DECLINATIONX = 38
        const val STATE_DECLINATIONY = 39
        const val STATE_DABS_PER_BASIC_RADIUS = 40
        const val STATE_DABS_PER_ACTUAL_RADIUS = 41
        const val STATE_DABS_PER_SECOND = 42
        const val STATE_BARREL_ROTATION = 43
        const val STATES_COUNT = 44

        const val BUCKET_SMUDGE_R = 0
        const val BUCKET_SMUDGE_G = 1
        const val BUCKET_SMUDGE_B = 2
        const val BUCKET_SMUDGE_A = 3
        const val BUCKET_PREV_COL_R = 4
        const val BUCKET_PREV_COL_G = 5
        const val BUCKET_PREV_COL_B = 6
        const val BUCKET_PREV_COL_A = 7
        const val BUCKET_PREV_COL_RECENTNESS = 8
    }

    var activePreset: com.astral.typer.utils.MyPaintBrushHelper.BrushPreset? = null

    fun getPreset(context: android.content.Context): com.astral.typer.utils.MyPaintBrushHelper.BrushPreset {
        var preset = activePreset
        if (preset == null || preset.name != brushName) {
            val assetPath = com.astral.typer.utils.MyPaintBrushHelper.findBrushAssetPath(context, brushName)
            preset = com.astral.typer.utils.MyPaintBrushHelper.loadPreset(context, assetPath)
            activePreset = preset
        }
        return preset!!
    }

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
        lastEventTime = System.currentTimeMillis()

        // Reset state variables
        skip = 0f
        skipLastX = 0f
        skipLastY = 0f
        skippedDtime = 0f
        randomInput = (Math.random()).toFloat()

        for (i in 0 until STATES_COUNT) {
            states[i] = 0f
        }
        states[STATE_FLIP] = -1f

        // Reset smudge buckets
        smudgeBuckets.fill(0f)

        states[STATE_X] = x
        states[STATE_Y] = y
        states[STATE_PRESSURE] = 0f

        states[STATE_ACTUAL_X] = x
        states[STATE_ACTUAL_Y] = y
        states[STATE_STROKE] = 1.0f
        states[STATE_VIEWZOOM] = 1.0f

        val context = TyperApplication.instance ?: return
        val preset = getPreset(context)
        calculateSpeedMappings(preset)

        // Evaluate default base values for spacing
        states[STATE_DABS_PER_BASIC_RADIUS] = getBaseValue(preset, "dabs_per_basic_radius")
        states[STATE_DABS_PER_ACTUAL_RADIUS] = getBaseValue(preset, "dabs_per_actual_radius")
        states[STATE_DABS_PER_SECOND] = getBaseValue(preset, "dabs_per_second")

        settingsValue.clear()
        // Initialize settingsValue with base values so first state updates have previous values
        val allSettingNames = listOf(
            "opaque", "opaque_multiply", "opaque_linearize", "radius_logarithmic", "hardness", "softness",
            "anti_aliasing", "dabs_per_basic_radius", "dabs_per_actual_radius", "dabs_per_second",
            "gridmap_scale", "gridmap_scale_x", "gridmap_scale_y", "radius_by_random", "speed1_slowness",
            "speed2_slowness", "speed1_gamma", "speed2_gamma", "offset_by_random", "offset_y", "offset_x",
            "offset_angle", "offset_angle_asc", "offset_angle_view", "offset_angle_2", "offset_angle_2_asc",
            "offset_angle_2_view", "offset_angle_adj", "offset_multiplier", "offset_by_speed",
            "offset_by_speed_slowness", "slow_tracking", "slow_tracking_per_dab", "tracking_noise",
            "color_h", "color_s", "color_v", "restore_color", "change_color_h", "change_color_l",
            "change_color_hsl_s", "change_color_v", "change_color_hsv_s", "smudge", "paint_mode",
            "smudge_transparency", "smudge_length", "smudge_length_log", "smudge_bucket", "smudge_radius_log",
            "eraser", "stroke_threshold", "stroke_duration_logarithmic", "stroke_holdtime", "custom_input",
            "custom_input_slowness", "elliptical_dab_ratio", "elliptical_dab_angle", "direction_filter",
            "lock_alpha", "colorize", "posterize", "posterize_num", "snap_to_pixel", "pressure_gain_log"
        )
        for (name in allSettingNames) {
            settingsValue[name] = getBaseValue(preset, name)
        }
    }

    private fun getBrushScale(preset: com.astral.typer.utils.MyPaintBrushHelper.BrushPreset): Float {
        val baseRadiusLog = getBaseValue(preset, "radius_logarithmic")
        val baseRadiusScale = Math.exp(baseRadiusLog.toDouble()).toFloat() * 15f
        return if (baseRadiusScale > 0f) brushSize / baseRadiusScale else 1.0f
    }

    private fun calculateSpeedMappings(preset: com.astral.typer.utils.MyPaintBrushHelper.BrushPreset) {
        for (i in 0 until 2) {
            val gammaLog = if (i == 0) getBaseValue(preset, "speed1_gamma") else getBaseValue(preset, "speed2_gamma")
            val gamma = Math.exp(gammaLog.toDouble()).toFloat()
            val fix1_x = 45f
            val fix1_y = 0.5f
            val fix2_x = 45f
            val fix2_dy = 0.015f

            val c1 = Math.log((fix1_x + gamma).toDouble()).toFloat()
            val m = fix2_dy * (fix2_x + gamma)
            val q = fix1_y - m * c1

            speedMappingGamma[i] = gamma
            speedMappingM[i] = m
            speedMappingQ[i] = q
        }
    }

    private fun countDabsTo(x: Float, y: Float, dt: Float, preset: com.astral.typer.utils.MyPaintBrushHelper.BrushPreset): Float {
        val baseRadiusLog = getBaseValue(preset, "radius_logarithmic")
        val baseRadius = (Math.exp(baseRadiusLog.toDouble()).toFloat() * getBrushScale(preset)).coerceIn(0.2f, 1000f)

        if (states[STATE_ACTUAL_RADIUS] == 0f) {
            states[STATE_ACTUAL_RADIUS] = baseRadius
        }

        val dx = x - states[STATE_X]
        val dy = y - states[STATE_Y]

        val dist = if (states[STATE_ACTUAL_ELLIPTICAL_DAB_RATIO] > 1f) {
            val angleRad = Math.toRadians(states[STATE_ACTUAL_ELLIPTICAL_DAB_ANGLE].toDouble()).toFloat()
            val cs = Math.cos(angleRad.toDouble()).toFloat()
            val sn = Math.sin(angleRad.toDouble()).toFloat()
            val yyr = (dy * cs - dx * sn) * states[STATE_ACTUAL_ELLIPTICAL_DAB_RATIO]
            val xxr = dy * sn + dx * cs
            Math.hypot(yyr.toDouble(), xxr.toDouble()).toFloat()
        } else {
            Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        }

        val res1 = dist / states[STATE_ACTUAL_RADIUS] * states[STATE_DABS_PER_ACTUAL_RADIUS]
        val res2 = dist / baseRadius * states[STATE_DABS_PER_BASIC_RADIUS]
        val res3 = dt * states[STATE_DABS_PER_SECOND]
        var res4 = res1 + res2 + res3
        if (res4.isNaN() || res4 < 0f) {
            res4 = 0f
        }
        return res4
    }

    fun continueStroke(x: Float, y: Float) {
        val now = System.currentTimeMillis()
        val dtime = if (lastEventTime == 0L) 0.016f else ((now - lastEventTime) / 1000f).coerceIn(0.001f, 1.0f)
        lastEventTime = now

        val context = TyperApplication.instance ?: return
        val preset = getPreset(context)

        // Calculate slow tracking factor
        val slowTracking = getBaseValue(preset, "slow_tracking")
        val trackingFactor = if (slowTracking > 0f) {
            (1f - Math.exp((-100f * dtime / slowTracking).toDouble()).toFloat()).coerceIn(0.01f, 1f)
        } else {
            1f
        }

        val targetX = lastX + (x - lastX) * trackingFactor
        val targetY = lastY + (y - lastY) * trackingFactor

        val dx = targetX - lastX
        val dy = targetY - lastY
        val distance = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

        if (distance > 2f) {
            strokeHasMoved = true
        }

        // Skip input length if requested (for stable tracking noise)
        var actualX = targetX
        var actualY = targetY
        var actualDtime = dtime

        val trackingNoise = getBaseValue(preset, "tracking_noise")
        if (trackingNoise != 0f) {
            val baseRadiusLog = getBaseValue(preset, "radius_logarithmic")
            val baseRadius = Math.exp(baseRadiusLog.toDouble()).toFloat()
            val noise = baseRadius * trackingNoise

            if (noise > 0.001f) {
                if (skip > 0.001f) {
                    val dist = Math.hypot((skipLastX - actualX).toDouble(), (skipLastY - actualY).toDouble()).toFloat()
                    skipLastX = actualX
                    skipLastY = actualY
                    skippedDtime += actualDtime
                    skip -= dist
                    actualDtime = skippedDtime

                    if (skip > 0.001f && !(actualDtime > 5f)) {
                        lastX = actualX
                        lastY = actualY
                        return
                    }

                    skip = 0f
                    skipLastX = 0f
                    skipLastY = 0f
                    skippedDtime = 0f
                }

                // we need to skip some length of input to make tracking noise independent from input frequency
                skip = 0.5f * noise
                skipLastX = actualX
                skipLastY = actualY

                // add noise
                actualX += noise * randGauss()
                actualY += noise * randGauss()
            }
        }

        var dtimeLeft = actualDtime
        var dabsTodo = countDabsTo(actualX, actualY, dtimeLeft, preset)
        var dabsMoved = states[STATE_PARTIAL_DABS]

        var totalDabsTodo = dabsTodo

        val pressure = 1.0f

        while (dabsMoved + totalDabsTodo >= 1.0f) {
            val stepDdab: Float
            val frac: Float
            if (dabsMoved > 0f) {
                stepDdab = 1.0f - dabsMoved
                dabsMoved = 0f
            } else {
                stepDdab = 1.0f
            }
            frac = if (totalDabsTodo > 0f) stepDdab / totalDabsTodo else 1.0f

            val stepDx = frac * (actualX - states[STATE_X])
            val stepDy = frac * (actualY - states[STATE_Y])
            val stepDpressure = frac * (pressure - states[STATE_PRESSURE])
            val stepDtime = frac * dtimeLeft

            updateStatesAndSettingValues(
                preset = preset,
                stepDdab = stepDdab,
                stepDx = stepDx,
                stepDy = stepDy,
                stepDpressure = stepDpressure,
                stepDeclination = 0f,
                stepAscension = 0f,
                stepDtime = stepDtime,
                stepViewzoom = 1f,
                stepViewrotation = 0f,
                stepDeclinationx = 0f,
                stepDeclinationy = 0f,
                stepBarrelRotation = 0f
            )

            states[STATE_FLIP] *= -1f
            prepareAndDrawDab(preset)

            randomInput = Math.random().toFloat()

            lastX = states[STATE_X]
            lastY = states[STATE_Y]
            dtimeLeft -= stepDtime

            totalDabsTodo = countDabsTo(actualX, actualY, dtimeLeft, preset)
        }

        val stepDdab = totalDabsTodo
        val stepDx = actualX - states[STATE_X]
        val stepDy = actualY - states[STATE_Y]
        val stepDpressure = pressure - states[STATE_PRESSURE]
        val stepDtime = dtimeLeft

        updateStatesAndSettingValues(
            preset = preset,
            stepDdab = stepDdab,
            stepDx = stepDx,
            stepDy = stepDy,
            stepDpressure = stepDpressure,
            stepDeclination = 0f,
            stepAscension = 0f,
            stepDtime = stepDtime,
            stepViewzoom = 1f,
            stepViewrotation = 0f,
            stepDeclinationx = 0f,
            stepDeclinationy = 0f,
            stepBarrelRotation = 0f
        )

        lastX = actualX
        lastY = actualY

        states[STATE_PARTIAL_DABS] = dabsMoved + totalDabsTodo
    }

    fun endStroke() {
        if (!strokeHasMoved) {
            val context = TyperApplication.instance ?: return
            val preset = getPreset(context)

            updateStatesAndSettingValues(
                preset = preset,
                stepDdab = 0f,
                stepDx = 0f,
                stepDy = 0f,
                stepDpressure = 1f,
                stepDeclination = 0f,
                stepAscension = 0f,
                stepDtime = 0.016f,
                stepViewzoom = 1f,
                stepViewrotation = 0f,
                stepDeclinationx = 0f,
                stepDeclinationy = 0f,
                stepBarrelRotation = 0f
            )

            prepareAndDrawDab(preset)
        }
    }

    private fun updateStatesAndSettingValues(
        preset: com.astral.typer.utils.MyPaintBrushHelper.BrushPreset,
        stepDdab: Float,
        stepDx: Float,
        stepDy: Float,
        stepDpressure: Float,
        stepDeclination: Float,
        stepAscension: Float,
        stepDtime: Float,
        stepViewzoom: Float,
        stepViewrotation: Float,
        stepDeclinationx: Float,
        stepDeclinationy: Float,
        stepBarrelRotation: Float
    ) {
        var safeStepDtime = stepDtime
        if (safeStepDtime < 0f) {
            safeStepDtime = 0.001f
        } else if (safeStepDtime == 0f) {
            safeStepDtime = 0.001f
        }

        states[STATE_X] += stepDx
        states[STATE_Y] += stepDy
        states[STATE_PRESSURE] += stepDpressure

        states[STATE_DECLINATION] += stepDeclination
        states[STATE_ASCENSION] += stepAscension
        states[STATE_DECLINATIONX] += stepDeclinationx
        states[STATE_DECLINATIONY] += stepDeclinationy

        states[STATE_VIEWZOOM] = stepViewzoom
        val viewrotation = modArith(Math.toDegrees(stepViewrotation.toDouble()).toFloat() + 180f, 360f) - 180f
        states[STATE_VIEWROTATION] = viewrotation

        // Gridmap state update
        val xVal = states[STATE_ACTUAL_X]
        val yVal = states[STATE_ACTUAL_Y]
        val scale = Math.exp((settingsValue["gridmap_scale"] ?: 0f).toDouble()).toFloat()
        val scaleX = settingsValue["gridmap_scale_x"] ?: 1f
        val scaleY = settingsValue["gridmap_scale_y"] ?: 1f
        val scaledSize = scale * 256f
        states[STATE_GRIDMAP_X] = modArith(Math.abs(xVal * scaleX), scaledSize) / scaledSize * 256f
        states[STATE_GRIDMAP_Y] = modArith(Math.abs(yVal * scaleY), scaledSize) / scaledSize * 256f
        if (xVal < 0f) {
            states[STATE_GRIDMAP_X] = 256f - states[STATE_GRIDMAP_X]
        }
        if (yVal < 0f) {
            states[STATE_GRIDMAP_Y] = 256f - states[STATE_GRIDMAP_Y]
        }

        val baseRadiusLog = getBaseValue(preset, "radius_logarithmic")
        val baseRadius = Math.exp(baseRadiusLog.toDouble()).toFloat().coerceIn(0.2f, 1000f)
        states[STATE_BARREL_ROTATION] += stepBarrelRotation

        if (states[STATE_PRESSURE] <= 0f) {
            states[STATE_PRESSURE] = 0f
        }
        val pressure = states[STATE_PRESSURE]

        val lim = 0.0001f
        val threshold = getBaseValue(preset, "stroke_threshold")
        val started = states[STATE_STROKE_STARTED]
        if (started == 0f && pressure > threshold + lim) {
            states[STATE_STROKE_STARTED] = 1f
            states[STATE_STROKE] = 0f
        } else if (started != 0f && pressure <= threshold * 0.9f + lim) {
            states[STATE_STROKE_STARTED] = 0f
        }

        val normDx = stepDx / safeStepDtime * states[STATE_VIEWZOOM]
        val normDy = stepDy / safeStepDtime * states[STATE_VIEWZOOM]
        val normSpeed = Math.hypot(normDx.toDouble(), normDy.toDouble()).toFloat()
        val normDist = Math.hypot(
            (stepDx / safeStepDtime / baseRadius).toDouble(),
            (stepDy / safeStepDtime / baseRadius).toDouble()
        ).toFloat() * safeStepDtime

        val speed1Val = (Math.log((speedMappingGamma[0] + states[STATE_NORM_SPEED1_SLOW]).toDouble()).toFloat() * speedMappingM[0] + speedMappingQ[0])
        val speed2Val = (Math.log((speedMappingGamma[1] + states[STATE_NORM_SPEED2_SLOW]).toDouble()).toFloat() * speedMappingM[1] + speedMappingQ[1])

        val dirAngle = Math.atan2(states[STATE_DIRECTION_DY].toDouble(), states[STATE_DIRECTION_DX].toDouble()).toFloat()
        val directionInput = modArith(Math.toDegrees(dirAngle.toDouble()).toFloat() + viewrotation + 180f, 180f)

        val dirAngle360 = Math.atan2(states[STATE_DIRECTION_ANGLE_DY].toDouble(), states[STATE_DIRECTION_ANGLE_DX].toDouble()).toFloat()
        val directionAngleInput = (Math.toDegrees(dirAngle360.toDouble()).toFloat() + viewrotation + 360f) % 360f

        val ascensionInput = modArith(states[STATE_ASCENSION] + viewrotation + 180f, 360f) - 180f

        val viewzoomInput = baseRadiusLog - Math.log((baseRadius / states[STATE_VIEWZOOM]).toDouble()).toFloat()
        val attackAngleInput = smallestAngularDifference(states[STATE_ASCENSION], modArith(Math.toDegrees(dirAngle360.toDouble()).toFloat() + 90f, 360f))

        val currentInputs = mapOf(
            "pressure" to pressure * Math.exp(getBaseValue(preset, "pressure_gain_log").toDouble()).toFloat(),
            "random" to randomInput,
            "stroke" to Math.min(states[STATE_STROKE], 1.0f),
            "direction" to directionInput,
            "tilt_declination" to states[STATE_DECLINATION],
            "tilt_ascension" to ascensionInput,
            "speed1" to speed1Val,
            "speed2" to speed2Val,
            "custom" to states[STATE_CUSTOM_INPUT],
            "direction_angle" to directionAngleInput,
            "attack_angle" to attackAngleInput,
            "tilt_declinationx" to states[STATE_DECLINATIONX],
            "tilt_declinationy" to states[STATE_DECLINATIONY],
            "gridmap_x" to states[STATE_GRIDMAP_X].coerceIn(0f, 256.0f),
            "gridmap_y" to states[STATE_GRIDMAP_Y].coerceIn(0f, 256.0f),
            "viewzoom" to viewzoomInput,
            "brush_radius" to baseRadiusLog,
            "barrel_rotation" to modArith(states[STATE_BARREL_ROTATION], 360f)
        )

        for (key in preset.settings.keys) {
            settingsValue[key] = preset.getSettingValue(key, currentInputs)
        }
        val allSettingNames = listOf(
            "opaque", "opaque_multiply", "opaque_linearize", "radius_logarithmic", "hardness", "softness",
            "anti_aliasing", "dabs_per_basic_radius", "dabs_per_actual_radius", "dabs_per_second",
            "gridmap_scale", "gridmap_scale_x", "gridmap_scale_y", "radius_by_random", "speed1_slowness",
            "speed2_slowness", "speed1_gamma", "speed2_gamma", "offset_by_random", "offset_y", "offset_x",
            "offset_angle", "offset_angle_asc", "offset_angle_view", "offset_angle_2", "offset_angle_2_asc",
            "offset_angle_2_view", "offset_angle_adj", "offset_multiplier", "offset_by_speed",
            "offset_by_speed_slowness", "slow_tracking", "slow_tracking_per_dab", "tracking_noise",
            "color_h", "color_s", "color_v", "restore_color", "change_color_h", "change_color_l",
            "change_color_hsl_s", "change_color_v", "change_color_hsv_s", "smudge", "paint_mode",
            "smudge_transparency", "smudge_length", "smudge_length_log", "smudge_bucket", "smudge_radius_log",
            "eraser", "stroke_threshold", "stroke_duration_logarithmic", "stroke_holdtime", "custom_input",
            "custom_input_slowness", "elliptical_dab_ratio", "elliptical_dab_angle", "direction_filter",
            "lock_alpha", "colorize", "posterize", "posterize_num", "snap_to_pixel", "pressure_gain_log"
        )
        for (name in allSettingNames) {
            if (!settingsValue.containsKey(name)) {
                settingsValue[name] = getBaseValue(preset, name)
            }
        }

        states[STATE_DABS_PER_BASIC_RADIUS] = settingsValue["dabs_per_basic_radius"] ?: 0f
        states[STATE_DABS_PER_ACTUAL_RADIUS] = settingsValue["dabs_per_actual_radius"] ?: 4f
        states[STATE_DABS_PER_SECOND] = settingsValue["dabs_per_second"] ?: 0f

        val facX = 1f - expDecay(settingsValue["slow_tracking_per_dab"] ?: 0f, stepDdab)
        states[STATE_ACTUAL_X] += (states[STATE_X] - states[STATE_ACTUAL_X]) * facX
        states[STATE_ACTUAL_Y] += (states[STATE_Y] - states[STATE_ACTUAL_Y]) * facX

        val fac1 = 1f - expDecay(settingsValue["speed1_slowness"] ?: 0.04f, safeStepDtime)
        states[STATE_NORM_SPEED1_SLOW] += (normSpeed - states[STATE_NORM_SPEED1_SLOW]) * fac1
        val fac2 = 1f - expDecay(settingsValue["speed2_slowness"] ?: 0.8f, safeStepDtime)
        states[STATE_NORM_SPEED2_SLOW] += (normSpeed - states[STATE_NORM_SPEED2_SLOW]) * fac2

        var timeConstant = Math.exp(((settingsValue["offset_by_speed_slowness"] ?: 1f) * 0.01f).toDouble()).toFloat() - 1f
        if (timeConstant < 0.002f) {
            timeConstant = 0.002f
        }
        val facSpeedSlow = 1f - expDecay(timeConstant, safeStepDtime)
        states[STATE_NORM_DX_SLOW] += (normDx - states[STATE_NORM_DX_SLOW]) * facSpeedSlow
        states[STATE_NORM_DY_SLOW] += (normDy - states[STATE_NORM_DY_SLOW]) * facSpeedSlow

        // orientation
        var dxOrient = stepDx * states[STATE_VIEWZOOM]
        var dyOrient = stepDy * states[STATE_VIEWZOOM]
        val stepInDabtime = Math.hypot(dxOrient.toDouble(), dyOrient.toDouble()).toFloat()
        val directionFilterVal = settingsValue["direction_filter"] ?: 2f
        val facDir = 1f - expDecay(Math.exp(directionFilterVal * 0.5).toFloat() - 1f, stepInDabtime)

        val dxOld = states[STATE_DIRECTION_DX]
        val dyOld = states[STATE_DIRECTION_DY]

        states[STATE_DIRECTION_ANGLE_DX] += (dxOrient - states[STATE_DIRECTION_ANGLE_DX]) * facDir
        states[STATE_DIRECTION_ANGLE_DY] += (dyOrient - states[STATE_DIRECTION_ANGLE_DY]) * facDir

        if ((dxOld - dxOrient) * (dxOld - dxOrient) + (dyOld - dyOrient) * (dyOld - dyOrient) >
            (dxOld + dxOrient) * (dxOld + dxOrient) + (dyOld + dyOrient) * (dyOld + dyOrient)
        ) {
            dxOrient = -dxOrient
            dyOrient = -dyOrient
        }
        states[STATE_DIRECTION_DX] += (dxOrient - states[STATE_DIRECTION_DX]) * facDir
        states[STATE_DIRECTION_DY] += (dyOrient - states[STATE_DIRECTION_DY]) * facDir

        val facCustom = 1f - expDecay(settingsValue["custom_input_slowness"] ?: 0f, 0.1f)
        states[STATE_CUSTOM_INPUT] += ((settingsValue["custom_input"] ?: 0f) - states[STATE_CUSTOM_INPUT]) * facCustom

        val frequency = Math.exp(-(settingsValue["stroke_duration_logarithmic"] ?: 4f).toDouble()).toFloat()
        val stroke = (states[STATE_STROKE] + normDist * frequency).coerceAtLeast(0f)
        val wrap = 1f + (settingsValue["stroke_holdtime"] ?: 0f).coerceAtLeast(0f)
        if (stroke >= wrap && wrap > 10.9f) {
            states[STATE_STROKE] = 1.0f
        } else if (stroke >= wrap) {
            states[STATE_STROKE] = stroke % wrap
        } else {
            states[STATE_STROKE] = stroke
        }

        val radiusLog = settingsValue["radius_logarithmic"] ?: 0.5f
        states[STATE_ACTUAL_RADIUS] = (Math.exp(radiusLog.toDouble()).toFloat() * getBrushScale(preset)).coerceIn(0.2f, 1000f)

        states[STATE_ACTUAL_ELLIPTICAL_DAB_RATIO] = settingsValue["elliptical_dab_ratio"] ?: 1f
        states[STATE_ACTUAL_ELLIPTICAL_DAB_ANGLE] = modArith((settingsValue["elliptical_dab_angle"] ?: 90f) - viewrotation + 180f, 180f) - 180f
    }

    private fun getSmudgeBucketOffset(preset: com.astral.typer.utils.MyPaintBrushHelper.BrushPreset, inputValues: Map<String, Float>): Int {
        if (!preset.settings.containsKey("smudge_bucket")) {
            return -1
        }
        val smudgeBucketValue = preset.getSettingValue("smudge_bucket", inputValues)
        val bucketIndex = Math.round(smudgeBucketValue).coerceIn(0, 255)
        return bucketIndex * 9
    }

    private fun getBucketVal(offset: Int, field: Int): Float {
        return if (offset < 0) {
            states[STATE_SMUDGE_RA + field]
        } else {
            smudgeBuckets[offset + field]
        }
    }

    private fun setBucketVal(offset: Int, field: Int, value: Float) {
        if (offset < 0) {
            states[STATE_SMUDGE_RA + field] = value
        } else {
            smudgeBuckets[offset + field] = value
        }
    }

    private fun prepareAndDrawDab(preset: com.astral.typer.utils.MyPaintBrushHelper.BrushPreset): Boolean {
        val opaqueFac = settingsValue["opaque_multiply"] ?: 1.0f
        var opaque = (settingsValue["opaque"] ?: 1.0f).coerceAtLeast(0.0f)
        opaque = (opaque * opaqueFac).coerceIn(0.0f, 1.0f)

        val opaqueLinearize = getBaseValue(preset, "opaque_linearize")
        if (opaqueLinearize != 0f) {
            var dabsPerPixel = (states[STATE_DABS_PER_ACTUAL_RADIUS] + states[STATE_DABS_PER_BASIC_RADIUS]) * 2.0f
            if (dabsPerPixel < 1.0f) dabsPerPixel = 1.0f
            dabsPerPixel = 1.0f + opaqueLinearize * (dabsPerPixel - 1.0f)

            val beta = 1.0f - opaque
            val betaDab = Math.pow(beta.toDouble(), (1.0f / dabsPerPixel).toDouble()).toFloat()
            opaque = 1.0f - betaDab
        }

        var x = states[STATE_ACTUAL_X]
        var y = states[STATE_ACTUAL_Y]

        val baseRadiusLog = getBaseValue(preset, "radius_logarithmic")
        val baseRadius = Math.exp(baseRadiusLog.toDouble()).toFloat() * getBrushScale(preset)

        // Directional offsets
        val offs = directionalOffsets(baseRadius, states[STATE_FLIP], settingsValue)
        x += offs.first
        y += offs.second

        val viewZoom = states[STATE_VIEWZOOM]
        val offsetBySpeed = settingsValue["offset_by_speed"] ?: 0f
        if (offsetBySpeed != 0f) {
            x += states[STATE_NORM_DX_SLOW] * offsetBySpeed * 0.1f / viewZoom
            y += states[STATE_NORM_DY_SLOW] * offsetBySpeed * 0.1f / viewZoom
        }

        val offsetByRandom = settingsValue["offset_by_random"] ?: 0f
        if (offsetByRandom != 0f) {
            val amp = offsetByRandom.coerceAtLeast(0f)
            x += randGauss() * amp * baseRadius
            y += randGauss() * amp * baseRadius
        }

        var radius = states[STATE_ACTUAL_RADIUS]
        val radiusByRandom = settingsValue["radius_by_random"] ?: 0f
        if (radiusByRandom != 0f) {
            val noise = randGauss() * radiusByRandom
            val radiusLog = (settingsValue["radius_logarithmic"] ?: baseRadiusLog) + noise
            radius = (Math.exp(radiusLog.toDouble()).toFloat() * getBrushScale(preset)).coerceIn(0.2f, 1000f)
            val alphaCorrection = (states[STATE_ACTUAL_RADIUS] / radius) * (states[STATE_ACTUAL_RADIUS] / radius)
            if (alphaCorrection <= 1f) {
                opaque *= alphaCorrection
            }
        }

        val paintFactor = settingsValue["paint_mode"] ?: 1.0f
        val paintSettingConstant = preset.settings["paint_mode"] == null
        val legacySmudge = paintFactor <= 0f && paintSettingConstant

        // Color calculations
        var rFloat = Color.red(brushColor) / 255f
        var gFloat = Color.green(brushColor) / 255f
        var bFloat = Color.blue(brushColor) / 255f

        // Smudge
        val smudgeLength = settingsValue["smudge_length"] ?: 0.5f
        val smudgeValue = settingsValue["smudge"] ?: 0f

        val smudgeOffset = getSmudgeBucketOffset(preset, settingsValue)

        if (smudgeLength < 1.0f && (smudgeValue != 0f || preset.settings["smudge"] != null)) {
            val px = Math.round(x)
            val py = Math.round(y)

            var sampledR = rFloat
            var sampledG = gFloat
            var sampledB = bFloat
            var sampledA = 0f

            if (px >= 0 && px < canvasWidth && py >= 0 && py < canvasHeight) {
                val sampledColor = bitmap.getPixel(px, py)
                sampledR = Color.red(sampledColor) / 255f
                sampledG = Color.green(sampledColor) / 255f
                sampledB = Color.blue(sampledColor) / 255f
                sampledA = Color.alpha(sampledColor) / 255f
            }

            var updateFactor = smudgeLength.coerceAtLeast(0.01f)
            val recentness = getBucketVal(smudgeOffset, BUCKET_PREV_COL_RECENTNESS) * updateFactor
            setBucketVal(smudgeOffset, BUCKET_PREV_COL_RECENTNESS, recentness)

            val smudgeLengthLog = settingsValue["smudge_length_log"] ?: 0f
            val margin = 1e-16f
            if (recentness < (Math.min(1.0, Math.pow((0.5f * updateFactor).toDouble(), smudgeLengthLog.toDouble()) + margin)).toFloat()) {
                if (recentness == 0f) {
                    updateFactor = 0f
                }
                setBucketVal(smudgeOffset, BUCKET_PREV_COL_RECENTNESS, 1.0f)

                val smudgeOpLim = settingsValue["smudge_transparency"] ?: 0f
                if ((smudgeOpLim > 0f && sampledA < smudgeOpLim) || (smudgeOpLim < 0f && sampledA > -smudgeOpLim)) {
                    return false
                }

                setBucketVal(smudgeOffset, BUCKET_PREV_COL_R, sampledR)
                setBucketVal(smudgeOffset, BUCKET_PREV_COL_G, sampledG)
                setBucketVal(smudgeOffset, BUCKET_PREV_COL_B, sampledB)
                setBucketVal(smudgeOffset, BUCKET_PREV_COL_A, sampledA)
            } else {
                sampledR = getBucketVal(smudgeOffset, BUCKET_PREV_COL_R)
                sampledG = getBucketVal(smudgeOffset, BUCKET_PREV_COL_G)
                sampledB = getBucketVal(smudgeOffset, BUCKET_PREV_COL_B)
                sampledA = getBucketVal(smudgeOffset, BUCKET_PREV_COL_A)
            }

            if (legacySmudge) {
                val facOld = updateFactor
                val facNew = (1.0f - updateFactor) * sampledA
                val newR = facOld * getBucketVal(smudgeOffset, BUCKET_SMUDGE_R) + facNew * sampledR
                val newG = facOld * getBucketVal(smudgeOffset, BUCKET_SMUDGE_G) + facNew * sampledG
                val newB = facOld * getBucketVal(smudgeOffset, BUCKET_SMUDGE_B) + facNew * sampledB
                val newA = (facOld * getBucketVal(smudgeOffset, BUCKET_SMUDGE_A) + facNew).coerceIn(0f, 1f)

                setBucketVal(smudgeOffset, BUCKET_SMUDGE_R, newR)
                setBucketVal(smudgeOffset, BUCKET_SMUDGE_G, newG)
                setBucketVal(smudgeOffset, BUCKET_SMUDGE_B, newB)
                setBucketVal(smudgeOffset, BUCKET_SMUDGE_A, newA)
            } else {
                val facOld = updateFactor
                val facNew = (1.0f - updateFactor) * sampledA
                val newR = facOld * getBucketVal(smudgeOffset, BUCKET_SMUDGE_R) + facNew * sampledR
                val newG = facOld * getBucketVal(smudgeOffset, BUCKET_SMUDGE_G) + facNew * sampledG
                val newB = facOld * getBucketVal(smudgeOffset, BUCKET_SMUDGE_B) + facNew * sampledB
                val newA = (facOld * getBucketVal(smudgeOffset, BUCKET_SMUDGE_A) + facNew).coerceIn(0f, 1f)

                setBucketVal(smudgeOffset, BUCKET_SMUDGE_R, newR)
                setBucketVal(smudgeOffset, BUCKET_SMUDGE_G, newG)
                setBucketVal(smudgeOffset, BUCKET_SMUDGE_B, newB)
                setBucketVal(smudgeOffset, BUCKET_SMUDGE_A, newA)
            }
        }

        var eraserTargetAlpha = 1.0f
        if (smudgeValue > 0f) {
            val smudgeFactor = Math.min(1.0f, smudgeValue)
            eraserTargetAlpha = (1.0f - smudgeFactor) + smudgeFactor * getBucketVal(smudgeOffset, BUCKET_SMUDGE_A)
            eraserTargetAlpha = eraserTargetAlpha.coerceIn(0f, 1f)

            if (eraserTargetAlpha > 0f) {
                val colFactor = 1.0f - smudgeFactor
                rFloat = (smudgeFactor * getBucketVal(smudgeOffset, BUCKET_SMUDGE_R) + colFactor * rFloat) / eraserTargetAlpha
                gFloat = (smudgeFactor * getBucketVal(smudgeOffset, BUCKET_SMUDGE_G) + colFactor * gFloat) / eraserTargetAlpha
                bFloat = (smudgeFactor * getBucketVal(smudgeOffset, BUCKET_SMUDGE_B) + colFactor * bFloat) / eraserTargetAlpha
            } else {
                rFloat = 1.0f
                gFloat = 0.0f
                bFloat = 0.0f
            }
        }

        val eraser = settingsValue["eraser"] ?: 0f
        if (eraser != 0f) {
            eraserTargetAlpha *= (1.0f - eraser)
        }

        val changeColorH = settingsValue["change_color_h"] ?: 0f
        val changeColorHsvS = settingsValue["change_color_hsv_s"] ?: 0f
        val changeColorV = settingsValue["change_color_v"] ?: 0f
        val changeColorL = settingsValue["change_color_l"] ?: 0f
        val changeColorHslS = settingsValue["change_color_hsl_s"] ?: 0f

        val usingHsvDynamics = changeColorH != 0f || changeColorHsvS != 0f || changeColorV != 0f
        val usingHslDynamics = changeColorL != 0f || changeColorHslS != 0f

        if (usingHsvDynamics) {
            val hsv = FloatArray(3)
            rgbToHsv(rFloat, gFloat, bFloat, hsv)
            hsv[0] = (hsv[0] + changeColorH + 10f) % 1.0f
            hsv[1] = (hsv[1] + hsv[1] * hsv[2] * changeColorHsvS).coerceIn(0f, 1f)
            hsv[2] = (hsv[2] + changeColorV).coerceIn(0f, 1f)
            val rgb = FloatArray(3)
            hsvToRgb(hsv[0], hsv[1], hsv[2], rgb)
            rFloat = rgb[0]; gFloat = rgb[1]; bFloat = rgb[2]
        }

        if (usingHslDynamics) {
            val hsl = FloatArray(3)
            rgbToHsl(rFloat, gFloat, bFloat, hsl)
            hsl[2] = (hsl[2] + changeColorL).coerceIn(0f, 1f)
            val factor = Math.min(Math.abs(1f - hsl[2]), Math.abs(hsl[2])) * 2f
            hsl[1] = (hsl[1] + hsl[1] * factor * changeColorHslS).coerceIn(0f, 1f)
            val rgb = FloatArray(3)
            hslToRgb(hsl[0], hsl[1], hsl[2], rgb)
            rFloat = rgb[0]; gFloat = rgb[1]; bFloat = rgb[2]
        }

        var finalColor = Color.argb(
            255,
            (rFloat * 255f).toInt().coerceIn(0, 255),
            (gFloat * 255f).toInt().coerceIn(0, 255),
            (bFloat * 255f).toInt().coerceIn(0, 255)
        )

        var hardness = (settingsValue["hardness"] ?: 0.5f).coerceIn(0f, 1f)
        val baseHardness = getBaseValue(preset, "hardness")
        val hardnessScale = if (baseHardness > 0f) brushHardness / baseHardness else 1.0f
        hardness = (hardness * hardnessScale).coerceIn(0f, 1f)

        val currentFadeoutInPixels = radius * (1.0f - hardness)
        val minFadeoutInPixels = settingsValue["anti_aliasing"] ?: 0f
        if (currentFadeoutInPixels < minFadeoutInPixels) {
            val currentOpticalRadius = radius - (1.0f - hardness) * radius / 2.0f
            val hardnessNew = (currentOpticalRadius - (minFadeoutInPixels / 2.0f)) / (currentOpticalRadius + (minFadeoutInPixels / 2.0f))
            val radiusNew = minFadeoutInPixels / (1.0f - hardnessNew)

            hardness = hardnessNew.coerceIn(0f, 1f)
            radius = radiusNew
        }

        val snapToPixel = settingsValue["snap_to_pixel"] ?: 0f
        if (snapToPixel > 0.0f) {
            val snappedX = Math.floor(x.toDouble()).toFloat() + 0.5f
            val snappedY = Math.floor(y.toDouble()).toFloat() + 0.5f
            x = x + (snappedX - x) * snapToPixel
            y = y + (snappedY - y) * snapToPixel

            var snappedRadius = Math.round(radius * 2.0f).toFloat() / 2.0f
            if (snappedRadius < 0.5f) {
                snappedRadius = 0.5f
            }
            if (snapToPixel > 0.9999f) {
                snappedRadius -= 0.0001f
            }
            radius = radius + (snappedRadius - radius) * snapToPixel
        }

        val dabOpacity = (opaque * brushOpacity).toInt().coerceIn(0, 255)

        val isPureEraser = eraser > 0f
        val ellipticalDabRatio = states[STATE_ACTUAL_ELLIPTICAL_DAB_RATIO]
        val ellipticalDabAngle = states[STATE_ACTUAL_ELLIPTICAL_DAB_ANGLE]
        val scaleY = if (ellipticalDabRatio <= 0.01f) 1f else (1f / ellipticalDabRatio)

        if (isPureEraser) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                alpha = dabOpacity
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
            if (hardness < 1f) {
                val colors = intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT)
                val stops = floatArrayOf(0f, hardness.coerceIn(0f, 0.99f), 1f)
                paint.shader = RadialGradient(0f, 0f, radius, colors, stops, Shader.TileMode.CLAMP)
            } else {
                paint.color = Color.BLACK
            }

            drawCanvas.save()
            drawCanvas.translate(x, y)
            drawCanvas.rotate(ellipticalDabAngle)
            drawCanvas.scale(1f, scaleY)
            drawCanvas.drawCircle(0f, 0f, radius, paint)
            drawCanvas.restore()
        } else if (eraserTargetAlpha < 1.0f && smudgeValue > 0f) {
            // Blending / smudge brush with transparency - 2-pass drawing
            // Pass 1: Erase pass (DST_OUT) with the base dab alpha profile
            val paintErase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                alpha = dabOpacity
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
            if (hardness < 1f) {
                val colors = intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT)
                val stops = floatArrayOf(0f, hardness.coerceIn(0f, 0.99f), 1f)
                paintErase.shader = RadialGradient(0f, 0f, radius, colors, stops, Shader.TileMode.CLAMP)
            } else {
                paintErase.color = Color.BLACK
            }

            drawCanvas.save()
            drawCanvas.translate(x, y)
            drawCanvas.rotate(ellipticalDabAngle)
            drawCanvas.scale(1f, scaleY)
            drawCanvas.drawCircle(0f, 0f, radius, paintErase)
            drawCanvas.restore()

            // Pass 2: Add pass (ADD) with finalColor and scaled alpha
            val paintAdd = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                alpha = (dabOpacity * eraserTargetAlpha).toInt().coerceIn(0, 255)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            }
            if (hardness < 1f) {
                val colors = intArrayOf(finalColor, finalColor, Color.TRANSPARENT)
                val stops = floatArrayOf(0f, hardness.coerceIn(0f, 0.99f), 1f)
                paintAdd.shader = RadialGradient(0f, 0f, radius, colors, stops, Shader.TileMode.CLAMP)
            } else {
                paintAdd.color = finalColor
            }

            drawCanvas.save()
            drawCanvas.translate(x, y)
            drawCanvas.rotate(ellipticalDabAngle)
            drawCanvas.scale(1f, scaleY)
            drawCanvas.drawCircle(0f, 0f, radius, paintAdd)
            drawCanvas.restore()
        } else {
            // Standard paint brush - 1-pass drawing
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                alpha = dabOpacity
            }
            if (hardness < 1f) {
                val colors = intArrayOf(finalColor, finalColor, Color.TRANSPARENT)
                val stops = floatArrayOf(0f, hardness.coerceIn(0f, 0.99f), 1f)
                paint.shader = RadialGradient(0f, 0f, radius, colors, stops, Shader.TileMode.CLAMP)
            } else {
                paint.color = finalColor
            }

            drawCanvas.save()
            drawCanvas.translate(x, y)
            drawCanvas.rotate(ellipticalDabAngle)
            drawCanvas.scale(1f, scaleY)
            drawCanvas.drawCircle(0f, 0f, radius, paint)
            drawCanvas.restore()
        }

        return true
    }

    private fun directionalOffsets(baseRadius: Float, brushFlip: Float, settings: Map<String, Float>): Pair<Float, Float> {
        val offsetMult = Math.exp((settings["offset_multiplier"] ?: 0f).toDouble()).toFloat()
        if (offsetMult.isInfinite() || offsetMult.isNaN()) {
            return Pair(0f, 0f)
        }

        var dx = settings["offset_x"] ?: 0f
        var dy = settings["offset_y"] ?: 0f

        val offsetAngleAdj = settings["offset_angle_adj"] ?: 0f
        val dirAngleDy = states[STATE_DIRECTION_ANGLE_DY]
        val dirAngleDx = states[STATE_DIRECTION_ANGLE_DX]
        val angleDeg = ((Math.toDegrees(Math.atan2(dirAngleDy.toDouble(), dirAngleDx.toDouble()).toDouble()) - 90.0) % 360.0).toFloat()

        // offset to one side of direction
        val offsetAngle = settings["offset_angle"] ?: 0f
        if (offsetAngle != 0f) {
            val dirAngle = Math.toRadians((angleDeg + offsetAngleAdj).toDouble())
            dx += (Math.cos(dirAngle) * offsetAngle).toFloat()
            dy += (Math.sin(dirAngle) * offsetAngle).toFloat()
        }

        // offset to one side of ascension angle
        val viewRotation = states[STATE_VIEWROTATION]
        val offsetAngleAsc = settings["offset_angle_asc"] ?: 0f
        if (offsetAngleAsc != 0f) {
            val ascension = states[STATE_ASCENSION]
            val ascAngle = Math.toRadians((ascension - viewRotation + offsetAngleAdj).toDouble())
            dx += (Math.cos(ascAngle) * offsetAngleAsc).toFloat()
            dy += (Math.sin(ascAngle) * offsetAngleAsc).toFloat()
        }

        // offset to one side of view orientation
        val viewOffset = settings["offset_angle_view"] ?: 0f
        if (viewOffset != 0f) {
            val viewAngle = Math.toRadians((viewRotation + offsetAngleAdj).toDouble())
            dx += (Math.cos(-viewAngle) * viewOffset).toFloat()
            dy += (Math.sin(-viewAngle) * viewOffset).toFloat()
        }

        // offset mirrored to sides of direction
        val offsetDirMirror = (settings["offset_angle_2"] ?: 0f).coerceAtLeast(0f)
        if (offsetDirMirror != 0f) {
            val dirMirrorAngle = Math.toRadians((angleDeg + offsetAngleAdj * brushFlip).toDouble())
            val offsetFactor = offsetDirMirror * brushFlip
            dx += (Math.cos(dirMirrorAngle) * offsetFactor).toFloat()
            dy += (Math.sin(dirMirrorAngle) * offsetFactor).toFloat()
        }

        // offset mirrored to sides of ascension angle
        val offsetAscMirror = (settings["offset_angle_2_asc"] ?: 0f).coerceAtLeast(0f)
        if (offsetAscMirror != 0f) {
            val ascension = states[STATE_ASCENSION]
            val ascAngle = Math.toRadians((ascension - viewRotation + offsetAngleAdj * brushFlip).toDouble())
            val offsetFactor = brushFlip * offsetAscMirror
            dx += (Math.cos(ascAngle) * offsetFactor).toFloat()
            dy += (Math.sin(ascAngle) * offsetFactor).toFloat()
        }

        // offset mirrored to sides of view orientation
        val offsetViewMirror = (settings["offset_angle_2_view"] ?: 0f).coerceAtLeast(0f)
        if (offsetViewMirror != 0f) {
            val offsetFactor = brushFlip * offsetViewMirror
            val offsetAngleRad = Math.toRadians((viewRotation + offsetAngleAdj).toDouble())
            dx += (Math.cos(-offsetAngleRad) * offsetFactor).toFloat()
            dy += (Math.sin(-offsetAngleRad) * offsetFactor).toFloat()
        }

        val lim = 3240f
        val baseMul = baseRadius * offsetMult
        return Pair(
            (dx * baseMul).coerceIn(-lim, lim),
            (dy * baseMul).coerceIn(-lim, lim)
        )
    }

    private fun getBaseValue(preset: com.astral.typer.utils.MyPaintBrushHelper.BrushPreset, settingName: String): Float {
        return preset.settings[settingName]?.baseValue ?: when (settingName) {
            "opaque" -> preset.opaque
            "hardness" -> preset.hardness
            "radius_logarithmic" -> preset.radiusLog
            "eraser" -> if (preset.isEraser) 1.0f else 0.0f
            "dabs_per_actual_radius" -> preset.dabsPerActualRadius
            "dabs_per_basic_radius" -> preset.dabsPerBasicRadius
            "dabs_per_second" -> preset.dabsPerSecond
            "offset_by_random" -> preset.offsetByRandom
            "radius_by_random" -> preset.radiusByRandom
            "elliptical_dab_ratio" -> preset.ellipticalDabRatio
            "elliptical_dab_angle" -> preset.ellipticalDabAngle
            "smudge" -> preset.smudge
            "smudge_length" -> preset.smudgeLength
            "slow_tracking" -> preset.slowTracking
            "speed1_gamma" -> preset.settings["speed1_gamma"]?.baseValue ?: 4.0f
            "speed2_gamma" -> preset.settings["speed2_gamma"]?.baseValue ?: 4.0f
            "speed1_slowness" -> preset.settings["speed1_slowness"]?.baseValue ?: 0.04f
            "speed2_slowness" -> preset.settings["speed2_slowness"]?.baseValue ?: 0.8f
            "stroke_duration_logarithmic" -> preset.settings["stroke_duration_logarithmic"]?.baseValue ?: 4.0f
            "stroke_holdtime" -> preset.settings["stroke_holdtime"]?.baseValue ?: 0.0f
            "stroke_threshold" -> preset.settings["stroke_threshold"]?.baseValue ?: 0.0f
            "direction_filter" -> preset.settings["direction_filter"]?.baseValue ?: 2.0f
            "custom_input_slowness" -> preset.settings["custom_input_slowness"]?.baseValue ?: 0f
            "offset_by_speed_slowness" -> preset.settings["offset_by_speed_slowness"]?.baseValue ?: 1.0f
            else -> 0f
        }
    }

    private fun modArith(a: Float, n: Float): Float {
        return (a - n * Math.floor((a / n).toDouble())).toFloat()
    }

    private fun smallestAngularDifference(angleA: Float, angleB: Float): Float {
        var a = angleB - angleA
        a = modArith(a + 180f, 360f) - 180f
        a += if (a > 180f) -360f else if (a < -180f) 360f else 0f
        return a
    }

    private fun randGauss(): Float {
        var sum = 0.0
        sum += Math.random()
        sum += Math.random()
        sum += Math.random()
        sum += Math.random()
        return (sum * 1.73205080757 - 3.46410161514).toFloat()
    }

    private fun expDecay(tConst: Float, t: Float): Float {
        if (tConst <= 0.001f) return 0f
        return Math.exp((-t / tConst).toDouble()).toFloat()
    }

    private fun rgbToHsv(r: Float, g: Float, b: Float, out: FloatArray) {
        val max = maxOf(r, maxOf(g, b))
        val min = minOf(r, minOf(g, b))
        val delta = max - min
        var h = 0f
        if (delta > 0f) {
            h = when (max) {
                r -> (g - b) / delta + (if (g < b) 6f else 0f)
                g -> (b - r) / delta + 2f
                else -> (r - g) / delta + 4f
            }
            h /= 6f
        }
        val s = if (max > 0f) delta / max else 0f
        val v = max
        out[0] = h
        out[1] = s
        out[2] = v
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float, out: FloatArray) {
        val h6 = (h - Math.floor(h.toDouble()).toFloat()) * 6f
        val i = h6.toInt()
        val f = h6 - i
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        when (i % 6) {
            0 -> { out[0] = v; out[1] = t; out[2] = p }
            1 -> { out[0] = q; out[1] = v; out[2] = p }
            2 -> { out[0] = p; out[1] = v; out[2] = t }
            3 -> { out[0] = p; out[1] = q; out[2] = v }
            4 -> { out[0] = t; out[1] = p; out[2] = v }
            else -> { out[0] = v; out[1] = p; out[2] = q }
        }
    }

    private fun rgbToHsl(r: Float, g: Float, b: Float, out: FloatArray) {
        val max = maxOf(r, maxOf(g, b))
        val min = minOf(r, minOf(g, b))
        val l = (max + min) / 2f
        var s = 0f
        var h = 0f
        if (max != min) {
            val d = max - min
            s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
            h = when (max) {
                r -> (g - b) / d + (if (g < b) 6f else 0f)
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            }
            h /= 6f
        }
        out[0] = h
        out[1] = s
        out[2] = l
    }

    private fun hslToRgb(h: Float, s: Float, l: Float, out: FloatArray) {
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        fun hue2rgb(t: Float): Float {
            var tNorm = t
            if (tNorm < 0f) tNorm += 1f
            if (tNorm > 1f) tNorm -= 1f
            if (tNorm < 1f/6f) return p + (q - p) * 6f * tNorm
            if (tNorm < 1f/2f) return q
            if (tNorm < 2f/3f) return p + (q - p) * (2f/3f - tNorm) * 6f
            return p
        }
        out[0] = hue2rgb(h + 1f/3f)
        out[1] = hue2rgb(h)
        out[2] = hue2rgb(h - 1f/3f)
    }
}
