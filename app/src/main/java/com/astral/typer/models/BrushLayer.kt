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

    // Dynamic Smudge color state
    private var currentSmudgeColor: Int = Color.BLACK

    // Standard touch stroke points
    private var lastX: Float = 0f
    private var lastY: Float = 0f

    // Dynamic input state variables
    private var lastEventTime: Long = 0L
    private var stateStroke: Float = 0f
    private var stateStrokeStarted: Boolean = false
    private var stateNormSpeed1Slow: Float = 0f
    private var stateNormSpeed2Slow: Float = 0f
    private var stateCustomInput: Float = 0f

    // MyPaint brush engine state variables
    private var statePartialDabs: Float = 0f
    private var stateActualRadius: Float = 0f
    private var stateActualEllipticalDabRatio: Float = 1f
    private var stateActualEllipticalDabAngle: Float = 90f
    private var stateActualX: Float = 0f
    private var stateActualY: Float = 0f
    private var stateFlip: Float = -1f

    private var stateDabsPerActualRadius: Float = 4f
    private var stateDabsPerBasicRadius: Float = 0f
    private var stateDabsPerSecond: Float = 0f

    private var stateDirectionDx: Float = 0f
    private var stateDirectionDy: Float = 0f
    private var stateDirectionAngleDx: Float = 0f
    private var stateDirectionAngleDy: Float = 0f

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
        stateStroke = 0f
        stateStrokeStarted = false
        stateNormSpeed1Slow = 0f
        stateNormSpeed2Slow = 0f
        stateCustomInput = 0f
        currentSmudgeColor = brushColor

        statePartialDabs = 0f
        stateActualRadius = 0f
        stateActualEllipticalDabRatio = 1f
        stateActualEllipticalDabAngle = 90f
        stateActualX = x
        stateActualY = y
        stateFlip = -1f

        stateDirectionDx = 0f
        stateDirectionDy = 0f
        stateDirectionAngleDx = 0f
        stateDirectionAngleDy = 0f

        val context = TyperApplication.instance ?: return
        val preset = getPreset(context)
        val tempInputs = mapOf("pressure" to 1.0f)
        stateDabsPerActualRadius = preset.getSettingValue("dabs_per_actual_radius", tempInputs)
        stateDabsPerBasicRadius = preset.getSettingValue("dabs_per_basic_radius", tempInputs)
        stateDabsPerSecond = preset.getSettingValue("dabs_per_second", tempInputs)
    }

    private fun countDabsTo(x: Float, y: Float, dt: Float, preset: com.astral.typer.utils.MyPaintBrushHelper.BrushPreset): Float {
        val baseRadius = (brushSize / 2f).coerceAtLeast(0.1f)
        if (stateActualRadius == 0f) {
            stateActualRadius = baseRadius
        }

        val dx = x - lastX
        val dy = y - lastY

        var dist = 0f

        if (stateActualEllipticalDabRatio > 1.0f) {
            val angleRad = Math.toRadians(stateActualEllipticalDabAngle.toDouble()).toFloat()
            val cs = Math.cos(angleRad.toDouble()).toFloat()
            val sn = Math.sin(angleRad.toDouble()).toFloat()
            val yyr = (dy * cs - dx * sn) * stateActualEllipticalDabRatio
            val xxr = dy * sn + dx * cs
            dist = Math.hypot(yyr.toDouble(), xxr.toDouble()).toFloat()
        } else {
            dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        }

        val res1 = dist / stateActualRadius * stateDabsPerActualRadius
        val res2 = dist / baseRadius * stateDabsPerBasicRadius
        val res3 = dt * stateDabsPerSecond
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

        val context = TyperApplication.instance!!
        val preset = getPreset(context)

        // Calculate slow tracking factor
        val baseInputs = mapOf("pressure" to 1.0f)
        val slowTracking = preset.getSettingValue("slow_tracking", baseInputs)
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

        var dtimeLeft = dtime
        val dabsTodo = countDabsTo(targetX, targetY, dtimeLeft, preset)
        var dabsMoved = statePartialDabs

        var totalDabsTodo = dabsTodo

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

            val stepDx = frac * (targetX - lastX)
            val stepDy = frac * (targetY - lastY)
            val stepDtime = frac * dtimeLeft

            val interpolatedX = lastX + stepDx
            val interpolatedY = lastY + stepDy

            updateStatesAndDrawDab(interpolatedX, interpolatedY, stepDx, stepDy, stepDdab, stepDtime, 1.0f, true, preset)

            lastX = interpolatedX
            lastY = interpolatedY
            dtimeLeft -= stepDtime

            totalDabsTodo = countDabsTo(targetX, targetY, dtimeLeft, preset)
        }

        val stepDdab = totalDabsTodo
        val stepDx = targetX - lastX
        val stepDy = targetY - lastY
        val stepDtime = dtimeLeft

        updateStatesAndDrawDab(targetX, targetY, stepDx, stepDy, stepDdab, stepDtime, 1.0f, false, preset)

        lastX = targetX
        lastY = targetY

        statePartialDabs = dabsMoved + totalDabsTodo
    }

    fun endStroke() {
        if (!strokeHasMoved) {
            val context = TyperApplication.instance!!
            val preset = getPreset(context)
            val inputs = mapOf(
                "pressure" to 1.0f,
                "random" to Math.random().toFloat(),
                "stroke" to 0f,
                "speed1" to 0f,
                "speed2" to 0f,
                "custom" to 0f,
                "direction" to 0f,
                "direction_angle" to 0f
            )

            stateActualEllipticalDabRatio = preset.getSettingValue("elliptical_dab_ratio", inputs).coerceAtLeast(1f)
            stateActualEllipticalDabAngle = preset.getSettingValue("elliptical_dab_angle", inputs)
            val currentRadiusLog = preset.getSettingValue("radius_logarithmic", inputs)
            val baseRadiusLog = preset.settings["radius_logarithmic"]?.baseValue ?: 0.5f
            val baseRadiusScale = Math.exp(baseRadiusLog.toDouble()).toFloat() * 15f
            val scale = if (baseRadiusScale > 0f) brushSize / baseRadiusScale else 1.0f
            val dabSize = (Math.exp(currentRadiusLog.toDouble()).toFloat() * 15f * scale).coerceAtLeast(1f)
            stateActualRadius = dabSize / 2f

            drawDabWithInputs(lastX, lastY, inputs, preset)
        }
    }

    private fun updateStatesAndDrawDab(
        cx: Float,
        cy: Float,
        stepDx: Float,
        stepDy: Float,
        stepDdab: Float,
        stepDtime: Float,
        pressure: Float,
        shouldDraw: Boolean,
        preset: com.astral.typer.utils.MyPaintBrushHelper.BrushPreset
    ) {
        val tempInputs = mapOf("pressure" to pressure)
        val baseRadius = (brushSize / 2f).coerceAtLeast(0.1f)
        val stepDist = Math.hypot(stepDx.toDouble(), stepDy.toDouble()).toFloat()
        val normDist = stepDist / baseRadius

        val threshold = preset.getSettingValue("stroke_threshold", tempInputs)
        if (!stateStrokeStarted && pressure > threshold + 0.0001f) {
            stateStrokeStarted = true
            stateStroke = 0f
        } else if (stateStrokeStarted && pressure <= threshold * 0.9f + 0.0001f) {
            stateStrokeStarted = false
        }

        val strokeDurationLog = preset.getSettingValue("stroke_duration_logarithmic", tempInputs)
        val frequency = Math.exp(-strokeDurationLog.toDouble()).toFloat()
        val nextStroke = (stateStroke + normDist * frequency).coerceAtLeast(0f)
        val strokeHoldtime = preset.getSettingValue("stroke_holdtime", tempInputs)
        val wrap = 1.0f + strokeHoldtime.coerceAtLeast(0f)

        if (nextStroke >= wrap && wrap > 10.9f) {
            stateStroke = 1.0f
        } else if (nextStroke >= wrap) {
            stateStroke = nextStroke % wrap
        } else {
            stateStroke = nextStroke
        }

        // Update speed
        val safeStepDtime = if (stepDtime <= 0f) 0.0001f else stepDtime
        val normSpeed = stepDist / safeStepDtime
        val speed1Slowness = preset.getSettingValue("speed1_slowness", tempInputs)
        val fac1 = 1f - expDecay(speed1Slowness, safeStepDtime)
        stateNormSpeed1Slow += (normSpeed - stateNormSpeed1Slow) * fac1

        val speed2Slowness = preset.getSettingValue("speed2_slowness", tempInputs)
        val fac2 = 1f - expDecay(speed2Slowness, safeStepDtime)
        stateNormSpeed2Slow += (normSpeed - stateNormSpeed2Slow) * fac2

        // Speed inputs calculation
        val speed1Gamma = preset.getSettingValue("speed1_gamma", tempInputs)
        val gamma0 = Math.exp(speed1Gamma.toDouble()).toFloat()
        val fix1_x = 45f
        val fix1_y = 0.5f
        val fix2_x = 45f
        val fix2_dy = 0.015f
        val c1_0 = Math.log((fix1_x + gamma0).toDouble()).toFloat()
        val m0 = fix2_dy * (fix2_x + gamma0)
        val q0 = fix1_y - m0 * c1_0
        val inputSpeed1 = Math.log((gamma0 + stateNormSpeed1Slow).toDouble()).toFloat() * m0 + q0

        val speed2Gamma = preset.getSettingValue("speed2_gamma", tempInputs)
        val gamma1 = Math.exp(speed2Gamma.toDouble()).toFloat()
        val c1_1 = Math.log((fix1_x + gamma1).toDouble()).toFloat()
        val m1 = fix2_dy * (fix2_x + gamma1)
        val q1 = fix1_y - m1 * c1_1
        val inputSpeed2 = Math.log((gamma1 + stateNormSpeed2Slow).toDouble()).toFloat() * m1 + q1

        // Custom input
        val customInputSlowness = preset.getSettingValue("custom_input_slowness", tempInputs)
        val customInputSetting = preset.getSettingValue("custom_input", tempInputs)
        val facCustom = 1.0f - expDecay(customInputSlowness, 0.1f)
        stateCustomInput += (customInputSetting - stateCustomInput) * facCustom

        // Direction with lowpass filter
        val directionFilter = preset.getSettingValue("direction_filter", tempInputs)
        val facDir = 1f - expDecay(Math.exp(directionFilter.toDouble() * 0.5).toFloat() - 1f, stepDist)

        stateDirectionAngleDx += (stepDx - stateDirectionAngleDx) * facDir
        stateDirectionAngleDy += (stepDy - stateDirectionAngleDy) * facDir

        var signCorrectedDx = stepDx
        var signCorrectedDy = stepDy

        // Use the opposite speed vector if it is closer (for 180 degree turns)
        val distNormal = (stateDirectionDx - stepDx) * (stateDirectionDx - stepDx) + (stateDirectionDy - stepDy) * (stateDirectionDy - stepDy)
        val distOpposite = (stateDirectionDx - (-stepDx)) * (stateDirectionDx - (-stepDx)) + (stateDirectionDy - (-stepDy)) * (stateDirectionDy - (-stepDy))
        if (distNormal > distOpposite) {
            signCorrectedDx = -stepDx
            signCorrectedDy = -stepDy
        }
        stateDirectionDx += (signCorrectedDx - stateDirectionDx) * facDir
        stateDirectionDy += (signCorrectedDy - stateDirectionDy) * facDir

        val dirAngle = Math.toDegrees(Math.atan2(stateDirectionDy.toDouble(), stateDirectionDx.toDouble())).toFloat()
        val inputDirection = (dirAngle + 180f) % 180f

        val dirAngle360 = Math.toDegrees(Math.atan2(stateDirectionAngleDy.toDouble(), stateDirectionAngleDx.toDouble())).toFloat()
        val inputDirectionAngle = (dirAngle360 + 360f) % 360f

        val inputValues = mapOf(
            "pressure" to pressure,
            "random" to Math.random().toFloat(),
            "stroke" to stateStroke.coerceIn(0f, 1f),
            "speed1" to inputSpeed1.toFloat(),
            "speed2" to inputSpeed2.toFloat(),
            "custom" to stateCustomInput,
            "direction" to inputDirection,
            "direction_angle" to inputDirectionAngle
        )

        // Update states for next countDabsTo calculation
        stateDabsPerActualRadius = preset.getSettingValue("dabs_per_actual_radius", inputValues)
        stateDabsPerBasicRadius = preset.getSettingValue("dabs_per_basic_radius", inputValues)
        stateDabsPerSecond = preset.getSettingValue("dabs_per_second", inputValues)

        val currentRadiusLog = preset.getSettingValue("radius_logarithmic", inputValues)
        val baseRadiusLog = preset.settings["radius_logarithmic"]?.baseValue ?: 0.5f
        val baseRadiusScale = Math.exp(baseRadiusLog.toDouble()).toFloat() * 15f
        val scale = if (baseRadiusScale > 0f) brushSize / baseRadiusScale else 1.0f
        val dabSize = (Math.exp(currentRadiusLog.toDouble()).toFloat() * 15f * scale).coerceAtLeast(1f)
        stateActualRadius = dabSize / 2f

        stateActualEllipticalDabRatio = preset.getSettingValue("elliptical_dab_ratio", inputValues).coerceAtLeast(1f)
        stateActualEllipticalDabAngle = preset.getSettingValue("elliptical_dab_angle", inputValues)

        // Update actual positions using slow_tracking_per_dab
        val slowTrackingPerDab = preset.getSettingValue("slow_tracking_per_dab", inputValues)
        val facDab = 1f - expDecay(slowTrackingPerDab, stepDdab)
        stateActualX += (cx - stateActualX) * facDab
        stateActualY += (cy - stateActualY) * facDab

        if (shouldDraw) {
            stateFlip *= -1f
            drawDabWithInputs(stateActualX, stateActualY, inputValues, preset)
        }
    }

    private fun drawDabWithInputs(cx: Float, cy: Float, inputValues: Map<String, Float>, preset: com.astral.typer.utils.MyPaintBrushHelper.BrushPreset) {
        val opaqueBase = preset.getSettingValue("opaque", inputValues)
        val opaqueMultiply = if (preset.settings.containsKey("opaque_multiply")) {
            preset.getSettingValue("opaque_multiply", inputValues)
        } else {
            inputValues["pressure"] ?: 1.0f
        }
        val finalOpacity = (opaqueBase * opaqueMultiply).coerceIn(0f, 1f)
        val dabOpacity = (finalOpacity * brushOpacity).toInt().coerceIn(0, 255)

        // Radius
        val baseRadiusLog = preset.settings["radius_logarithmic"]?.baseValue ?: 0.5f
        val baseRadius = Math.exp(baseRadiusLog.toDouble()).toFloat() * 15f
        val scale = if (baseRadius > 0f) brushSize / baseRadius else 1.0f

        val currentRadiusLog = preset.getSettingValue("radius_logarithmic", inputValues)
        val dabSize = (Math.exp(currentRadiusLog.toDouble()).toFloat() * 15f * scale).coerceAtLeast(1f)
        val dabRadius = dabSize / 2f

        // Hardness
        val currentHardness = preset.getSettingValue("hardness", inputValues).coerceIn(0f, 1f)
        val baseHardness = preset.settings["hardness"]?.baseValue ?: 0.5f
        val hardnessScale = if (baseHardness > 0f) brushHardness / baseHardness else 1.0f
        val finalHardness = (currentHardness * hardnessScale).coerceIn(0f, 1f)

        // Offsets
        var dabX = cx
        var dabY = cy
        val offsetByRandom = preset.getSettingValue("offset_by_random", inputValues)
        if (offsetByRandom > 0f) {
            val maxOffset = offsetByRandom * dabRadius
            val angle = Math.random() * 2.0 * Math.PI
            val dist = Math.random() * maxOffset
            dabX += (dist * Math.cos(angle)).toFloat()
            dabY += (dist * Math.sin(angle)).toFloat()
        }

        // Radius by random
        var finalDabRadius = dabRadius
        val radiusByRandom = preset.getSettingValue("radius_by_random", inputValues)
        if (radiusByRandom > 0f) {
            val randFactor = (Math.random() * 2.0 - 1.0).toFloat()
            finalDabRadius *= (1f + randFactor * radiusByRandom).coerceAtLeast(0.1f)
        }

        // Smudge
        val smudge = preset.getSettingValue("smudge", inputValues).coerceIn(0f, 1f)
        val smudgeLength = preset.getSettingValue("smudge_length", inputValues).coerceIn(0f, 1f)

        // Color shifting
        val changeColorH = preset.getSettingValue("change_color_h", inputValues)
        val changeColorHslS = preset.getSettingValue("change_color_hsl_s", inputValues)
        val changeColorHsvS = preset.getSettingValue("change_color_hsv_s", inputValues)
        val changeColorL = preset.getSettingValue("change_color_l", inputValues)
        val changeColorV = preset.getSettingValue("change_color_v", inputValues)

        var rFloat = Color.red(brushColor) / 255f
        var gFloat = Color.green(brushColor) / 255f
        var bFloat = Color.blue(brushColor) / 255f

        val hsv = FloatArray(3)
        val hsl = FloatArray(3)
        val rgb = FloatArray(3)

        val usingHsvDynamics = changeColorH != 0f || changeColorHsvS != 0f || changeColorV != 0f
        val usingHslDynamics = changeColorL != 0f || changeColorHslS != 0f

        if (usingHsvDynamics) {
            rgbToHsv(rFloat, gFloat, bFloat, hsv)
            hsv[0] = (hsv[0] + changeColorH + 10f) % 1.0f
            hsv[1] = (hsv[1] + hsv[1] * hsv[2] * changeColorHsvS).coerceIn(0f, 1f)
            hsv[2] = (hsv[2] + changeColorV).coerceIn(0f, 1f)
            hsvToRgb(hsv[0], hsv[1], hsv[2], rgb)
            rFloat = rgb[0]; gFloat = rgb[1]; bFloat = rgb[2]
        }

        if (usingHslDynamics) {
            rgbToHsl(rFloat, gFloat, bFloat, hsl)
            hsl[2] = (hsl[2] + changeColorL).coerceIn(0f, 1f)
            val factor = minOf(Math.abs(1f - hsl[2]), Math.abs(hsl[2])) * 2f
            hsl[1] = (hsl[1] + hsl[1] * factor * changeColorHslS).coerceIn(0f, 1f)
            hslToRgb(hsl[0], hsl[1], hsl[2], rgb)
            rFloat = rgb[0]; gFloat = rgb[1]; bFloat = rgb[2]
        }

        var finalColor = Color.argb(
            255,
            (rFloat * 255f).toInt().coerceIn(0, 255),
            (gFloat * 255f).toInt().coerceIn(0, 255),
            (bFloat * 255f).toInt().coerceIn(0, 255)
        )

        // Smudge updates
        if (smudge > 0f && dabX >= 0 && dabX < canvasWidth && dabY >= 0 && dabY < canvasHeight) {
            val px = dabX.toInt()
            val py = dabY.toInt()
            val sampledColor = bitmap.getPixel(px, py)
            if (Color.alpha(sampledColor) > 0) {
                val r = (Color.red(currentSmudgeColor) * (1f - smudge) + Color.red(sampledColor) * smudge).toInt().coerceIn(0, 255)
                val g = (Color.green(currentSmudgeColor) * (1f - smudge) + Color.green(sampledColor) * smudge).toInt().coerceIn(0, 255)
                val b = (Color.blue(currentSmudgeColor) * (1f - smudge) + Color.blue(sampledColor) * smudge).toInt().coerceIn(0, 255)
                val a = (Color.alpha(currentSmudgeColor) * (1f - smudge) + Color.alpha(sampledColor) * smudge).toInt().coerceIn(0, 255)
                val blended = Color.argb(a, r, g, b)

                finalColor = blended

                val decay = (1f - smudgeLength).coerceIn(0f, 1f)
                val dr = (Color.red(blended) * (1f - decay) + Color.red(brushColor) * decay).toInt().coerceIn(0, 255)
                val dg = (Color.green(blended) * (1f - decay) + Color.green(brushColor) * decay).toInt().coerceIn(0, 255)
                val db = (Color.blue(blended) * (1f - decay) + Color.blue(brushColor) * decay).toInt().coerceIn(0, 255)
                val da = (Color.alpha(blended) * (1f - decay) + Color.alpha(brushColor) * decay).toInt().coerceIn(0, 255)
                currentSmudgeColor = Color.argb(da, dr, dg, db)
            } else {
                val decay = (1f - smudgeLength).coerceIn(0f, 1f)
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
            alpha = dabOpacity
        }

        if (finalHardness < 1f) {
            val colors = intArrayOf(finalColor, finalColor, Color.TRANSPARENT)
            val stops = floatArrayOf(0f, finalHardness.coerceIn(0f, 0.99f), 1f)
            paint.shader = RadialGradient(0f, 0f, finalDabRadius, colors, stops, Shader.TileMode.CLAMP)
        } else {
            paint.color = finalColor
        }

        // Eraser check
        val eraser = preset.getSettingValue("eraser", inputValues).coerceIn(0f, 1f)
        if (eraser > 0f) {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            if (finalHardness < 1f) {
                val colors = intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT)
                val stops = floatArrayOf(0f, finalHardness.coerceIn(0f, 0.99f), 1f)
                paint.shader = RadialGradient(0f, 0f, finalDabRadius, colors, stops, Shader.TileMode.CLAMP)
            } else {
                paint.color = Color.BLACK
            }
        }

        // Draw with elliptical transformations
        val ellipticalDabRatio = stateActualEllipticalDabRatio
        val ellipticalDabAngle = stateActualEllipticalDabAngle

        drawCanvas.save()
        drawCanvas.translate(dabX, dabY)
        drawCanvas.rotate(ellipticalDabAngle)
        val scaleY = if (ellipticalDabRatio <= 0.01f) 1f else (1f / ellipticalDabRatio)
        drawCanvas.scale(1f, scaleY)
        drawCanvas.drawCircle(0f, 0f, finalDabRadius, paint)
        drawCanvas.restore()
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
