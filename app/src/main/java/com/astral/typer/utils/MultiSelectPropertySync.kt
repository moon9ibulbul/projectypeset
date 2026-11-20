package com.astral.typer.utils

import android.graphics.Typeface
import android.text.Layout
import com.astral.typer.models.Layer
import com.astral.typer.models.StylableLayer
import com.astral.typer.models.TextEffectType
import com.astral.typer.models.TextLayer

/**
 * Tracks a snapshot of the primary selected layer's editable properties when multiple layers are selected.
 * Any property that changes on the primary layer is automatically propagated to all other unlocked
 * layers in the multi-selection, while leaving position (x, y), text content, warp, perspective,
 * spans, and erase masks untouched.
 */
object MultiSelectPropertySync {

    private var trackedLayer: Layer? = null
    private var snapshot: MutableMap<String, Any?> = mutableMapOf()

    fun resetSnapshot(primary: Layer?) {
        trackedLayer = primary
        snapshot = if (primary != null) captureSnapshot(primary) else mutableMapOf()
    }

    fun syncIfNeeded(primary: Layer?, targets: Collection<Layer>) {
        if (primary == null || targets.size <= 1) {
            if (trackedLayer != primary) {
                resetSnapshot(primary)
            }
            return
        }
        if (trackedLayer !== primary || snapshot.isEmpty()) {
            resetSnapshot(primary)
            return
        }

        val current = captureSnapshot(primary)
        val changedKeys = mutableListOf<String>()
        for ((k, v) in current) {
            val prev = snapshot[k]
            if (!valuesEqual(prev, v)) {
                changedKeys.add(k)
            }
        }
        if (changedKeys.isEmpty()) return

        for (target in targets) {
            if (target === primary || target.isLocked) continue
            applyChangedKeys(changedKeys, current, target)
        }

        snapshot = current
    }

    private fun valuesEqual(a: Any?, b: Any?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        if (a is IntArray && b is IntArray) return a.contentEquals(b)
        if (a is FloatArray && b is FloatArray) return a.contentEquals(b)
        return a == b
    }

    private fun captureSnapshot(layer: Layer): MutableMap<String, Any?> {
        val m = mutableMapOf<String, Any?>()
        m["scaleX"] = layer.scaleX
        m["scaleY"] = layer.scaleY
        m["rotation"] = layer.rotation

        if (layer is StylableLayer) {
            m["color"] = layer.color
            m["opacity"] = layer.opacity
            m["blendMode"] = layer.blendMode
            m["isOpacityGradient"] = layer.isOpacityGradient
            m["opacityStart"] = layer.opacityStart
            m["opacityEnd"] = layer.opacityEnd
            m["opacityAngle"] = layer.opacityAngle

            m["shadowColor"] = layer.shadowColor
            m["shadowRadius"] = layer.shadowRadius
            m["shadowDx"] = layer.shadowDx
            m["shadowDy"] = layer.shadowDy
            m["shadowThickness"] = layer.shadowThickness
            m["isDropShadowIncludeStroke"] = layer.isDropShadowIncludeStroke

            m["isMotionShadow"] = layer.isMotionShadow
            m["isMotionShadowIncludeStroke"] = layer.isMotionShadowIncludeStroke
            m["isMotionShadowTwoWay"] = layer.isMotionShadowTwoWay
            m["motionShadowAngle"] = layer.motionShadowAngle
            m["motionShadowDistance"] = layer.motionShadowDistance
            m["motionShadowDx"] = layer.motionShadowDx
            m["motionShadowDy"] = layer.motionShadowDy
            m["motionShadowThickness"] = layer.motionShadowThickness
            m["motionShadowSmoothness"] = layer.motionShadowSmoothness
            m["motionShadowKernelSize"] = layer.motionShadowKernelSize

            m["strokeColor"] = layer.strokeColor
            m["strokeWidth"] = layer.strokeWidth
            m["doubleStrokeColor"] = layer.doubleStrokeColor
            m["doubleStrokeWidth"] = layer.doubleStrokeWidth
            m["tripleStrokeColor"] = layer.tripleStrokeColor
            m["tripleStrokeWidth"] = layer.tripleStrokeWidth
            m["isRoughStroke"] = layer.isRoughStroke
            m["roughStrokeRoughness"] = layer.roughStrokeRoughness

            m["isGradient"] = layer.isGradient
            m["gradientStartColor"] = layer.gradientStartColor
            m["gradientEndColor"] = layer.gradientEndColor
            m["gradientAngle"] = layer.gradientAngle
            m["hasMiddleColor"] = layer.hasMiddleColor
            m["gradientMiddleColor"] = layer.gradientMiddleColor
            m["gradientStartPos"] = layer.gradientStartPos
            m["gradientMiddlePos"] = layer.gradientMiddlePos
            m["gradientEndPos"] = layer.gradientEndPos
            m["gradientStrength"] = layer.gradientStrength
            m["isGradientText"] = layer.isGradientText
            m["isGradientStroke1"] = layer.isGradientStroke1
            m["isGradientStroke2"] = layer.isGradientStroke2
            m["isGradientStroke3"] = layer.isGradientStroke3
            m["isGradientShadow"] = layer.isGradientShadow

            m["patternName"] = layer.patternName
            m["patternColor"] = layer.patternColor
            m["patternAlpha"] = layer.patternAlpha
            m["patternScale"] = layer.patternScale
            m["patternRotation"] = layer.patternRotation

            m["currentEffect"] = layer.currentEffect
            m["secondaryEffect"] = layer.secondaryEffect
            m["tertiaryEffect"] = layer.tertiaryEffect

            m["chromaticShift"] = layer.chromaticShift
            m["chromaticAngle"] = layer.chromaticAngle
            m["chromaticBlur"] = layer.chromaticBlur
            m["chromaticColors"] = layer.chromaticColors.copyOf()
            m["glitchIntensity"] = layer.glitchIntensity
            m["glitchAmount"] = layer.glitchAmount
            m["glitchDistance"] = layer.glitchDistance
            m["glitchDirection"] = layer.glitchDirection
            m["pixelBlockSize"] = layer.pixelBlockSize
            m["neonRadius"] = layer.neonRadius
            m["neonColor"] = layer.neonColor
            m["neonAlpha"] = layer.neonAlpha
            m["neonInnerStrength"] = layer.neonInnerStrength
            m["neonOuterStrength"] = layer.neonOuterStrength
            m["neonKnockout"] = layer.neonKnockout
            m["neonQuality"] = layer.neonQuality
            m["longShadowLength"] = layer.longShadowLength
            m["longShadowColor"] = layer.longShadowColor
            m["longShadowAngle"] = layer.longShadowAngle
            m["blurRadius"] = layer.blurRadius
            m["isGradientBlur"] = layer.isGradientBlur
            m["blurLeft"] = layer.blurLeft
            m["blurRight"] = layer.blurRight
            m["blurAngle"] = layer.blurAngle
            m["halftoneDotSize"] = layer.halftoneDotSize
            m["halftoneDotColor"] = layer.halftoneDotColor
            m["halftoneThreshold"] = layer.halftoneThreshold
            m["halftoneType"] = layer.halftoneType
            m["halftoneAlpha"] = layer.halftoneAlpha
            m["halftoneRange"] = layer.halftoneRange
            m["halftoneDensity"] = layer.halftoneDensity
            m["halftoneFadingIntensity"] = layer.halftoneFadingIntensity
            m["halftoneShape"] = layer.halftoneShape
            m["motionBlurLength"] = layer.motionBlurLength
            m["motionBlurAngle"] = layer.motionBlurAngle
            m["motionBlurKernelSize"] = layer.motionBlurKernelSize
            m["motionBlurOffset"] = layer.motionBlurOffset
            m["motionBlurVelocityX"] = layer.motionBlurVelocityX
            m["motionBlurVelocityY"] = layer.motionBlurVelocityY
            m["motionBlurWeightMode"] = layer.motionBlurWeightMode
            m["motionBlurShaderVersion"] = layer.motionBlurShaderVersion
            m["fieryColor"] = layer.fieryColor
            m["fieryIntensity"] = layer.fieryIntensity
            m["wavyIntensity"] = layer.wavyIntensity
            m["wavyFrequency"] = layer.wavyFrequency
            m["particleSize"] = layer.particleSize
            m["particleSpread"] = layer.particleSpread
            m["particleDissolveAngle"] = layer.particleDissolveAngle
            m["multiGradientColors"] = layer.multiGradientColors.copyOf()
            m["multiGradientAngle"] = layer.multiGradientAngle
            m["multiGradientPositions"] = layer.multiGradientPositions?.copyOf()
            m["radialBlurInnerRadius"] = layer.radialBlurInnerRadius
            m["radialBlurMotionStrength"] = layer.radialBlurMotionStrength
            m["radialBlurCenterX"] = layer.radialBlurCenterX
            m["radialBlurCenterY"] = layer.radialBlurCenterY
            m["decayIntensity"] = layer.decayIntensity
            m["decayFadingLevel"] = layer.decayFadingLevel
            m["woodScratchIntensity"] = layer.woodScratchIntensity
            m["woodScratchColor"] = layer.woodScratchColor
            m["twistAngle"] = layer.twistAngle
            m["twistOffsetX"] = layer.twistOffsetX
            m["twistOffsetY"] = layer.twistOffsetY
            m["twistRadius"] = layer.twistRadius
            m["bulgeCenterX"] = layer.bulgeCenterX
            m["bulgeCenterY"] = layer.bulgeCenterY
            m["bulgeRadius"] = layer.bulgeRadius
            m["bulgeStrength"] = layer.bulgeStrength
            m["reflectionAlphaStart"] = layer.reflectionAlphaStart
            m["reflectionAlphaEnd"] = layer.reflectionAlphaEnd
            m["reflectionAmplitudeStart"] = layer.reflectionAmplitudeStart
            m["reflectionAmplitudeEnd"] = layer.reflectionAmplitudeEnd
            m["reflectionBoundary"] = layer.reflectionBoundary
            m["reflectionMirror"] = layer.reflectionMirror
            m["reflectionTime"] = layer.reflectionTime
            m["reflectionWavelengthStart"] = layer.reflectionWavelengthStart
            m["reflectionWavelengthEnd"] = layer.reflectionWavelengthEnd
            m["reflectionBlur"] = layer.reflectionBlur
            m["zoomBlurCenterX"] = layer.zoomBlurCenterX
            m["zoomBlurCenterY"] = layer.zoomBlurCenterY
            m["zoomBlurInnerRadius"] = layer.zoomBlurInnerRadius
            m["zoomBlurRadius"] = layer.zoomBlurRadius
            m["zoomBlurStrength"] = layer.zoomBlurStrength
            m["speedLineType"] = layer.speedLineType
            m["speedLineWidth"] = layer.speedLineWidth
            m["speedLineHeight"] = layer.speedLineHeight
            m["speedLineCount"] = layer.speedLineCount
            m["speedLineThickness"] = layer.speedLineThickness
            m["speedLineLength"] = layer.speedLineLength
            m["speedLineAdditional"] = layer.speedLineAdditional
            m["speedLineColor"] = layer.speedLineColor
            m["speedLineAngle"] = layer.speedLineAngle
            m["tailLength"] = layer.tailLength
            m["tailWavyIntensity"] = layer.tailWavyIntensity
            m["tailAngle"] = layer.tailAngle
            m["tailArrowPoint"] = layer.tailArrowPoint
            m["tailOffsetX"] = layer.tailOffsetX
            m["tailOffsetY"] = layer.tailOffsetY
            m["tailThickness"] = layer.tailThickness
            m["bevelDepth"] = layer.bevelDepth
            m["bevelAngle"] = layer.bevelAngle
            m["bevelHighlightAlpha"] = layer.bevelHighlightAlpha
            m["bevelShadowAlpha"] = layer.bevelShadowAlpha
            m["bevelSoftness"] = layer.bevelSoftness
            m["extrudeDepth"] = layer.extrudeDepth
            m["extrudeAngle"] = layer.extrudeAngle
            m["extrudeColor"] = layer.extrudeColor
            m["extrudeOutlineColor"] = layer.extrudeOutlineColor
            m["extrudeOutlineWidth"] = layer.extrudeOutlineWidth
            m["inkBleedIntensity"] = layer.inkBleedIntensity
            m["inkBleedRoughness"] = layer.inkBleedRoughness
            m["inkBleedSeed"] = layer.inkBleedSeed
            m["dropShadowAlpha"] = layer.dropShadowAlpha
            m["dropShadowBlur"] = layer.dropShadowBlur
            m["dropShadowColor"] = layer.dropShadowColor
            m["dropShadowOffsetX"] = layer.dropShadowOffsetX
            m["dropShadowOffsetY"] = layer.dropShadowOffsetY
            m["dropShadowPixelSizeX"] = layer.dropShadowPixelSizeX
            m["dropShadowPixelSizeY"] = layer.dropShadowPixelSizeY
            m["dropShadowQuality"] = layer.dropShadowQuality
            m["dropShadowOnly"] = layer.dropShadowOnly
        }

        if (layer is TextLayer) {
            m["fontSize"] = layer.fontSize
            m["fontPath"] = layer.fontPath
            m["typeface"] = layer.typeface
            m["textAlign"] = layer.textAlign
            m["isJustified"] = layer.isJustified
            m["letterSpacing"] = layer.letterSpacing
            m["lineSpacing"] = layer.lineSpacing
            m["caseType"] = layer.caseType
            m["boxWidth"] = layer.boxWidth
            m["fixedHeight"] = layer.fixedHeight
            m["isOval"] = layer.isOval
            m["highlightCornerRadius"] = layer.highlightCornerRadius
            m["highlightPaddingTop"] = layer.highlightPaddingTop
            m["highlightPaddingRight"] = layer.highlightPaddingRight
            m["highlightPaddingBottom"] = layer.highlightPaddingBottom
            m["highlightPaddingLeft"] = layer.highlightPaddingLeft
            m["transformTypes"] = layer.transformTypes.toSet()
            m["transformSizeMultiplier"] = layer.transformSizeMultiplier
            m["transformAngleMultiplier"] = layer.transformAngleMultiplier
            m["transformDotsMultiplier"] = layer.transformDotsMultiplier
        }
        return m
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyChangedKeys(keys: List<String>, values: Map<String, Any?>, target: Layer) {
        for (key in keys) {
            val v = values[key]
            when (key) {
                "scaleX" -> target.scaleX = v as Float
                "scaleY" -> target.scaleY = v as Float
                "rotation" -> target.rotation = v as Float
            }

            if (target is StylableLayer) {
                when (key) {
                    "color" -> target.color = v as Int
                    "opacity" -> target.opacity = v as Int
                    "blendMode" -> target.blendMode = v as String
                    "isOpacityGradient" -> target.isOpacityGradient = v as Boolean
                    "opacityStart" -> target.opacityStart = v as Int
                    "opacityEnd" -> target.opacityEnd = v as Int
                    "opacityAngle" -> target.opacityAngle = v as Int

                    "shadowColor" -> target.shadowColor = v as Int
                    "shadowRadius" -> target.shadowRadius = v as Float
                    "shadowDx" -> target.shadowDx = v as Float
                    "shadowDy" -> target.shadowDy = v as Float
                    "shadowThickness" -> target.shadowThickness = v as Float
                    "isDropShadowIncludeStroke" -> target.isDropShadowIncludeStroke = v as Boolean

                    "isMotionShadow" -> target.isMotionShadow = v as Boolean
                    "isMotionShadowIncludeStroke" -> target.isMotionShadowIncludeStroke = v as Boolean
                    "isMotionShadowTwoWay" -> target.isMotionShadowTwoWay = v as Boolean
                    "motionShadowAngle" -> target.motionShadowAngle = v as Int
                    "motionShadowDistance" -> target.motionShadowDistance = v as Float
                    "motionShadowDx" -> target.motionShadowDx = v as Float
                    "motionShadowDy" -> target.motionShadowDy = v as Float
                    "motionShadowThickness" -> target.motionShadowThickness = v as Float
                    "motionShadowSmoothness" -> target.motionShadowSmoothness = v as Int
                    "motionShadowKernelSize" -> target.motionShadowKernelSize = v as Int

                    "strokeColor" -> target.strokeColor = v as Int
                    "strokeWidth" -> target.strokeWidth = v as Float
                    "doubleStrokeColor" -> target.doubleStrokeColor = v as Int
                    "doubleStrokeWidth" -> target.doubleStrokeWidth = v as Float
                    "tripleStrokeColor" -> target.tripleStrokeColor = v as Int
                    "tripleStrokeWidth" -> target.tripleStrokeWidth = v as Float
                    "isRoughStroke" -> target.isRoughStroke = v as Boolean
                    "roughStrokeRoughness" -> target.roughStrokeRoughness = v as Float

                    "isGradient" -> target.isGradient = v as Boolean
                    "gradientStartColor" -> target.gradientStartColor = v as Int
                    "gradientEndColor" -> target.gradientEndColor = v as Int
                    "gradientAngle" -> target.gradientAngle = v as Int
                    "hasMiddleColor" -> target.hasMiddleColor = v as Boolean
                    "gradientMiddleColor" -> target.gradientMiddleColor = v as Int
                    "gradientStartPos" -> target.gradientStartPos = v as Float
                    "gradientMiddlePos" -> target.gradientMiddlePos = v as Float
                    "gradientEndPos" -> target.gradientEndPos = v as Float
                    "gradientStrength" -> target.gradientStrength = v as Float
                    "isGradientText" -> target.isGradientText = v as Boolean
                    "isGradientStroke1" -> target.isGradientStroke1 = v as Boolean
                    "isGradientStroke2" -> target.isGradientStroke2 = v as Boolean
                    "isGradientStroke3" -> target.isGradientStroke3 = v as Boolean
                    "isGradientShadow" -> target.isGradientShadow = v as Boolean

                    "patternName" -> target.patternName = v as String?
                    "patternColor" -> target.patternColor = v as Int
                    "patternAlpha" -> target.patternAlpha = v as Int
                    "patternScale" -> target.patternScale = v as Float
                    "patternRotation" -> target.patternRotation = v as Float

                    "currentEffect" -> target.currentEffect = v as TextEffectType
                    "secondaryEffect" -> target.secondaryEffect = v as TextEffectType
                    "tertiaryEffect" -> target.tertiaryEffect = v as TextEffectType

                    "chromaticShift" -> target.chromaticShift = v as Float
                    "chromaticAngle" -> target.chromaticAngle = v as Float
                    "chromaticBlur" -> target.chromaticBlur = v as Float
                    "chromaticColors" -> target.chromaticColors = (v as IntArray).copyOf()
                    "glitchIntensity" -> target.glitchIntensity = v as Float
                    "glitchAmount" -> target.glitchAmount = v as Float
                    "glitchDistance" -> target.glitchDistance = v as Float
                    "glitchDirection" -> target.glitchDirection = v as Float
                    "pixelBlockSize" -> target.pixelBlockSize = v as Float
                    "neonRadius" -> target.neonRadius = v as Float
                    "neonColor" -> target.neonColor = v as Int
                    "neonAlpha" -> target.neonAlpha = v as Float
                    "neonInnerStrength" -> target.neonInnerStrength = v as Float
                    "neonOuterStrength" -> target.neonOuterStrength = v as Float
                    "neonKnockout" -> target.neonKnockout = v as Boolean
                    "neonQuality" -> target.neonQuality = (v as Number).toFloat()
                    "longShadowLength" -> target.longShadowLength = v as Float
                    "longShadowColor" -> target.longShadowColor = v as Int
                    "longShadowAngle" -> target.longShadowAngle = v as Float
                    "blurRadius" -> target.blurRadius = v as Float
                    "isGradientBlur" -> target.isGradientBlur = v as Boolean
                    "blurLeft" -> target.blurLeft = v as Float
                    "blurRight" -> target.blurRight = v as Float
                    "blurAngle" -> target.blurAngle = v as Float
                    "halftoneDotSize" -> target.halftoneDotSize = v as Float
                    "halftoneDotColor" -> target.halftoneDotColor = v as Int
                    "halftoneThreshold" -> target.halftoneThreshold = v as Float
                    "halftoneType" -> target.halftoneType = v as String
                    "halftoneAlpha" -> target.halftoneAlpha = v as Float
                    "halftoneRange" -> target.halftoneRange = v as Float
                    "halftoneDensity" -> target.halftoneDensity = v as Float
                    "halftoneFadingIntensity" -> target.halftoneFadingIntensity = v as Float
                    "halftoneShape" -> target.halftoneShape = v as String
                    "motionBlurLength" -> target.motionBlurLength = v as Float
                    "motionBlurAngle" -> target.motionBlurAngle = v as Int
                    "motionBlurKernelSize" -> target.motionBlurKernelSize = v as Int
                    "motionBlurOffset" -> target.motionBlurOffset = v as Float
                    "motionBlurVelocityX" -> target.motionBlurVelocityX = v as Float
                    "motionBlurVelocityY" -> target.motionBlurVelocityY = v as Float
                    "motionBlurWeightMode" -> target.motionBlurWeightMode = v as Int
                    "motionBlurShaderVersion" -> target.motionBlurShaderVersion = v as Int
                    "fieryColor" -> target.fieryColor = v as Int
                    "fieryIntensity" -> target.fieryIntensity = v as Float
                    "wavyIntensity" -> target.wavyIntensity = v as Float
                    "wavyFrequency" -> target.wavyFrequency = v as Float
                    "particleSize" -> target.particleSize = v as Float
                    "particleSpread" -> target.particleSpread = v as Float
                    "particleDissolveAngle" -> target.particleDissolveAngle = v as Float
                    "multiGradientColors" -> target.multiGradientColors = (v as IntArray).copyOf()
                    "multiGradientAngle" -> target.multiGradientAngle = v as Float
                    "multiGradientPositions" -> target.multiGradientPositions = (v as FloatArray?)?.copyOf()
                    "radialBlurInnerRadius" -> target.radialBlurInnerRadius = v as Float
                    "radialBlurMotionStrength" -> target.radialBlurMotionStrength = v as Float
                    "radialBlurCenterX" -> target.radialBlurCenterX = v as Float
                    "radialBlurCenterY" -> target.radialBlurCenterY = v as Float
                    "decayIntensity" -> target.decayIntensity = v as Float
                    "decayFadingLevel" -> target.decayFadingLevel = v as Float
                    "woodScratchIntensity" -> target.woodScratchIntensity = v as Float
                    "woodScratchColor" -> target.woodScratchColor = v as Int
                    "twistAngle" -> target.twistAngle = v as Float
                    "twistOffsetX" -> target.twistOffsetX = v as Float
                    "twistOffsetY" -> target.twistOffsetY = v as Float
                    "twistRadius" -> target.twistRadius = v as Float
                    "bulgeCenterX" -> target.bulgeCenterX = v as Float
                    "bulgeCenterY" -> target.bulgeCenterY = v as Float
                    "bulgeRadius" -> target.bulgeRadius = v as Float
                    "bulgeStrength" -> target.bulgeStrength = v as Float
                    "reflectionAlphaStart" -> target.reflectionAlphaStart = v as Float
                    "reflectionAlphaEnd" -> target.reflectionAlphaEnd = v as Float
                    "reflectionAmplitudeStart" -> target.reflectionAmplitudeStart = v as Float
                    "reflectionAmplitudeEnd" -> target.reflectionAmplitudeEnd = v as Float
                    "reflectionBoundary" -> target.reflectionBoundary = v as Float
                    "reflectionMirror" -> target.reflectionMirror = v as Boolean
                    "reflectionTime" -> target.reflectionTime = v as Float
                    "reflectionWavelengthStart" -> target.reflectionWavelengthStart = v as Float
                    "reflectionWavelengthEnd" -> target.reflectionWavelengthEnd = v as Float
                    "reflectionBlur" -> target.reflectionBlur = v as Float
                    "zoomBlurCenterX" -> target.zoomBlurCenterX = v as Float
                    "zoomBlurCenterY" -> target.zoomBlurCenterY = v as Float
                    "zoomBlurInnerRadius" -> target.zoomBlurInnerRadius = v as Float
                    "zoomBlurRadius" -> target.zoomBlurRadius = v as Float
                    "zoomBlurStrength" -> target.zoomBlurStrength = v as Float
                    "speedLineType" -> target.speedLineType = v as String
                    "speedLineWidth" -> target.speedLineWidth = v as Float
                    "speedLineHeight" -> target.speedLineHeight = v as Float
                    "speedLineCount" -> target.speedLineCount = v as Int
                    "speedLineThickness" -> target.speedLineThickness = v as Float
                    "speedLineLength" -> target.speedLineLength = v as Float
                    "speedLineAdditional" -> target.speedLineAdditional = v as Int
                    "speedLineColor" -> target.speedLineColor = v as Int
                    "speedLineAngle" -> target.speedLineAngle = v as Float
                    "tailLength" -> target.tailLength = v as Float
                    "tailWavyIntensity" -> target.tailWavyIntensity = v as Float
                    "tailAngle" -> target.tailAngle = v as Float
                    "tailArrowPoint" -> target.tailArrowPoint = v as Boolean
                    "tailOffsetX" -> target.tailOffsetX = v as Float
                    "tailOffsetY" -> target.tailOffsetY = v as Float
                    "tailThickness" -> target.tailThickness = v as Float
                    "bevelDepth" -> target.bevelDepth = v as Float
                    "bevelAngle" -> target.bevelAngle = v as Int
                    "bevelHighlightAlpha" -> target.bevelHighlightAlpha = v as Float
                    "bevelShadowAlpha" -> target.bevelShadowAlpha = v as Float
                    "bevelSoftness" -> target.bevelSoftness = v as Float
                    "extrudeDepth" -> target.extrudeDepth = v as Float
                    "extrudeAngle" -> target.extrudeAngle = v as Int
                    "extrudeColor" -> target.extrudeColor = v as Int
                    "extrudeOutlineColor" -> target.extrudeOutlineColor = v as Int
                    "extrudeOutlineWidth" -> target.extrudeOutlineWidth = v as Float
                    "inkBleedIntensity" -> target.inkBleedIntensity = v as Float
                    "inkBleedRoughness" -> target.inkBleedRoughness = v as Float
                    "inkBleedSeed" -> target.inkBleedSeed = v as Int
                    "dropShadowAlpha" -> target.dropShadowAlpha = v as Float
                    "dropShadowBlur" -> target.dropShadowBlur = v as Float
                    "dropShadowColor" -> target.dropShadowColor = v as Int
                    "dropShadowOffsetX" -> target.dropShadowOffsetX = v as Float
                    "dropShadowOffsetY" -> target.dropShadowOffsetY = v as Float
                    "dropShadowPixelSizeX" -> target.dropShadowPixelSizeX = v as Float
                    "dropShadowPixelSizeY" -> target.dropShadowPixelSizeY = v as Float
                    "dropShadowQuality" -> target.dropShadowQuality = v as Int
                    "dropShadowOnly" -> target.dropShadowOnly = v as Boolean
                }
            }

            if (target is TextLayer) {
                when (key) {
                    "fontSize" -> target.fontSize = v as Float
                    "fontPath" -> target.fontPath = v as String?
                    "typeface" -> (v as? Typeface)?.let { target.typeface = it }
                    "textAlign" -> target.textAlign = v as Layout.Alignment
                    "isJustified" -> target.isJustified = v as Boolean
                    "letterSpacing" -> target.letterSpacing = v as Float
                    "lineSpacing" -> target.lineSpacing = v as Float
                    "caseType" -> target.caseType = v as String
                    "boxWidth" -> target.boxWidth = v as Float?
                    "fixedHeight" -> target.fixedHeight = v as Float?
                    "isOval" -> target.isOval = v as Boolean
                    "highlightCornerRadius" -> target.highlightCornerRadius = v as Float
                    "highlightPaddingTop" -> target.highlightPaddingTop = v as Float
                    "highlightPaddingRight" -> target.highlightPaddingRight = v as Float
                    "highlightPaddingBottom" -> target.highlightPaddingBottom = v as Float
                    "highlightPaddingLeft" -> target.highlightPaddingLeft = v as Float
                    "transformTypes" -> {
                        target.transformTypes.clear()
                        target.transformTypes.addAll(v as Set<String>)
                    }
                    "transformSizeMultiplier" -> target.transformSizeMultiplier = v as Float
                    "transformAngleMultiplier" -> target.transformAngleMultiplier = v as Float
                    "transformDotsMultiplier" -> target.transformDotsMultiplier = v as Float
                }
            }
        }
    }
}
