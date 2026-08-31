package com.astral.typer.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.content.Context
import com.astral.typer.models.TextLayer
import com.astral.typer.models.TextEffectType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

object StyleManager {
    private val savedStyles = mutableListOf<StyleModel>()
    private val savedFolders = mutableListOf<StyleFolder>()
    var clipboardStyle: StyleModel? = null

    private const val PREFS_NAME = "style_prefs"
    private const val KEY_STYLES = "saved_styles"
    private const val KEY_FOLDERS = "saved_style_folders"

    fun init(context: Context) {
        if (savedStyles.isEmpty()) {
            loadStyles(context)
        }
        if (savedFolders.isEmpty()) {
            loadFolders(context)
        }
    }

    fun reload(context: Context) {
        loadStyles(context)
        loadFolders(context)
    }

    fun copyStyle(layer: TextLayer) {
        clipboardStyle = toModel(layer)
    }

    fun saveStyle(context: Context, layer: TextLayer, folderId: String? = null, customName: String? = null) {
        val model = toModel(layer)
        val styleName = if (!customName.isNullOrBlank()) customName else model.name
        val genericModel = model.copy(name = styleName, folderId = folderId)

        savedStyles.add(genericModel)
        persistStyles(context)
    }

    fun getSavedStyles(): List<StyleModel> {
        return savedStyles
    }

    fun getFolders(): List<StyleFolder> {
        return savedFolders
    }

    fun addFolder(context: Context, name: String): StyleFolder {
        val folder = StyleFolder(name = name)
        savedFolders.add(folder)
        persistFolders(context)
        return folder
    }

    fun renameFolder(context: Context, id: String, newName: String) {
        val index = savedFolders.indexOfFirst { it.id == id }
        if (index != -1) {
            savedFolders[index] = savedFolders[index].copy(name = newName)
            persistFolders(context)
        }
    }

    fun deleteFolder(context: Context, id: String) {
        savedFolders.removeAll { it.id == id }
        var modified = false
        for (i in savedStyles.indices) {
            if (savedStyles[i].folderId == id) {
                savedStyles[i] = savedStyles[i].copy(folderId = null)
                modified = true
            }
        }
        persistFolders(context)
        if (modified) {
            persistStyles(context)
        }
    }

    fun assignStyleToFolder(context: Context, index: Int, folderId: String?) {
        if (index in 0 until savedStyles.size) {
            savedStyles[index] = savedStyles[index].copy(folderId = folderId)
            persistStyles(context)
        }
    }

    fun assignStyleToFolderByStyle(context: Context, style: StyleModel, folderId: String?) {
        val index = savedStyles.indexOf(style)
        if (index != -1) {
            assignStyleToFolder(context, index, folderId)
        } else {
            val altIndex = savedStyles.indexOfFirst { it == style }
            if (altIndex != -1) {
                assignStyleToFolder(context, altIndex, folderId)
            }
        }
    }

    fun deleteStyle(context: Context, index: Int) {
        if (index in 0 until savedStyles.size) {
            savedStyles.removeAt(index)
            persistStyles(context)
        }
    }

    fun deleteStyleByModel(context: Context, style: StyleModel) {
        val index = savedStyles.indexOf(style)
        if (index != -1) {
            deleteStyle(context, index)
        }
    }

    fun moveStyle(context: Context, fromIndex: Int, toIndex: Int) {
        if (fromIndex in 0 until savedStyles.size && toIndex in 0 until savedStyles.size) {
            val style = savedStyles.removeAt(fromIndex)
            savedStyles.add(toIndex, style)
            persistStyles(context)
        }
    }

    fun renameStyle(context: Context, index: Int, newName: String) {
        if (index in 0 until savedStyles.size) {
            savedStyles[index] = savedStyles[index].copy(name = newName)
            persistStyles(context)
        }
    }

    fun renameStyleByModel(context: Context, style: StyleModel, newName: String) {
        val index = savedStyles.indexOf(style)
        if (index != -1) {
            renameStyle(context, index, newName)
        }
    }

    private fun persistStyles(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = Gson().toJson(savedStyles)
        prefs.edit().putString(KEY_STYLES, json).apply()
    }

    private fun loadStyles(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_STYLES, null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<StyleModel>>() {}.type
                val list: List<StyleModel> = Gson().fromJson(json, type)
                savedStyles.clear()
                savedStyles.addAll(list)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun persistFolders(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = Gson().toJson(savedFolders)
        prefs.edit().putString(KEY_FOLDERS, json).apply()
    }

    private fun loadFolders(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_FOLDERS, null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<StyleFolder>>() {}.type
                val list: List<StyleFolder> = Gson().fromJson(json, type)
                savedFolders.clear()
                savedFolders.addAll(list)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    data class StyleFolder(
        val id: String = UUID.randomUUID().toString(),
        val name: String
    )

    data class StyleModel(
        val name: String? = "Style",
        val folderId: String? = null,
        val color: Int,
        val fontSize: Float,
        val fontPath: String?,
        val opacity: Int,
        val shadowColor: Int,
        val shadowRadius: Float,
        val shadowDx: Float,
        val shadowDy: Float,
        val strokeColor: Int,
        val strokeWidth: Float,
        val doubleStrokeColor: Int,
        val doubleStrokeWidth: Float,
        val tripleStrokeColor: Int? = null,
        val tripleStrokeWidth: Float? = null,
        val isRoughStroke: Boolean? = false,
        val roughStrokeRoughness: Float? = 3f,
        val isGradient: Boolean,
        val gradientStart: Int,
        val gradientEnd: Int,
        val gradientAngle: Int,
        val hasMiddleColor: Boolean = false,
        val gradientMiddleColor: Int = Color.GREEN,
        val gradientStartPos: Float? = null,
        val gradientMiddlePos: Float? = null,
        val gradientEndPos: Float? = null,
        val gradientStrength: Float = 1.0f,
        val isGradientText: Boolean,
        val isGradientStroke: Boolean,
        val isGradientShadow: Boolean,
        val letterSpacing: Float,
        val lineSpacing: Float,
        // Motion Shadow
        val isMotionShadow: Boolean,
        val motionAngle: Int,
        val motionDist: Float,
        val motionThickness: Float = 4f,
        // Opacity
        val blendMode: String?,
        val isOpacityGradient: Boolean,
        val opacityStart: Int,
        val opacityEnd: Int,
        val opacityAngle: Int,

        // Formatting
        val textAlign: Int = 0, // 0=Left, 1=Center, 2=Right
        val isJustified: Boolean = false,
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val isUnderline: Boolean = false,
        val isStrike: Boolean = false,

        // Radial Blur
        val radialBlurInnerRadius: Float = 0f,
        val radialBlurMotionStrength: Float = 0f,
        val radialBlurCenterX: Float = 0.5f,
        val radialBlurCenterY: Float = 0.5f,

        val decayIntensity: Float = 0.5f,
        val decayFadingLevel: Float = 0.5f,
        val woodScratchIntensity: Float = 0.5f,
        val woodScratchColor: Int = Color.TRANSPARENT,

        // Twist
        val twistAngle: Float = 4.0f,
        val twistOffsetX: Float = 0.0f,
        val twistOffsetY: Float = 0.0f,
        val twistRadius: Float = 200.0f,

        // Bulge & Pinch
        val bulgeCenterX: Float = 0.5f,
        val bulgeCenterY: Float = 0.5f,
        val bulgeRadius: Float = 100.0f,
        val bulgeStrength: Float = 1.0f,

        // Reflection
        val reflectionAlphaStart: Float = 1.0f,
        val reflectionAlphaEnd: Float = 1.0f,
        val reflectionAmplitudeStart: Float = 0.0f,
        val reflectionAmplitudeEnd: Float = 20.0f,
        val reflectionBoundary: Float = 0.5f,
        val reflectionMirror: Boolean = true,
        val reflectionTime: Float = 0.0f,
        val reflectionWavelengthStart: Float = 30.0f,
        val reflectionWavelengthEnd: Float = 100.0f,

        // Zoom Blur
        val zoomBlurCenterX: Float = 0.5f,
        val zoomBlurCenterY: Float = 0.5f,
        val zoomBlurInnerRadius: Float = 0f,
        val zoomBlurRadius: Float = -1f,
        val zoomBlurStrength: Float = 0.1f,

        // Speed Line
        val speedLineType: String? = "RADIAL",
        val speedLineWidth: Float? = 300f,
        val speedLineHeight: Float? = 300f,
        val speedLineCount: Int? = 50,
        val speedLineThickness: Float? = 2f,
        val speedLineLength: Float? = 40f,
        val speedLineAdditional: Int? = 9,
        val speedLineColor: Int? = Color.BLACK,
        val speedLineAngle: Float? = 0f,

        // Perspective
        val isPerspective: Boolean = false,
        val perspectivePoints: FloatArray? = null,
        // Warp
        val isWarp: Boolean = false,
        val warpRows: Int = 2,
        val warpCols: Int = 2,
        val warpMesh: FloatArray? = null,

        // Effects configuration
        val currentEffect: String? = "NONE",
        val secondaryEffect: String? = "NONE",
        val tertiaryEffect: String? = "NONE",
        val wavyIntensity: Float = 0.5f,
        val wavyFrequency: Float = 5f,
        val fieryColor: Int = Color.RED,
        val fieryIntensity: Float = 0.5f,
        val neonRadius: Float = 30f,
        val neonColor: Int = Color.CYAN,
        val neonAlpha: Float = 1.0f,
        val neonInnerStrength: Float = 0.0f,
        val neonOuterStrength: Float = 4.0f,
        val neonKnockout: Boolean = false,
        val neonQuality: Float = 0.1f,

        // Halftone Configuration
        val halftoneDotSize: Float? = 10f,
        val halftoneDotColor: Int? = Color.BLACK,
        val halftoneThreshold: Float? = 0.5f,
        val halftoneType: String? = "INNER",
        val halftoneAlpha: Float? = 1.0f,
        val halftoneRange: Float? = 20f,
        val halftoneDensity: Float? = 10f,
        val halftoneFadingIntensity: Float? = 1.0f,
        val halftoneShape: String? = "DOT",

        // Built-in Pattern
        val patternName: String? = null,
        val patternColor: Int? = Color.BLACK,
        val patternAlpha: Int? = 255,
        val patternScale: Float? = 1.0f,
        val patternRotation: Float? = 0f,
        val caseType: String? = "NORMAL",
        val transformTypes: Set<String>? = null,
        val transformSizeMultiplier: Float? = null,
        val transformAngleMultiplier: Float? = null,
        val transformDotsMultiplier: Float? = null,
        val chromaticAngle: Float? = 0f,
        val shadowThickness: Float? = 0f,
        val tailLength: Float? = 0f,
        val tailWavyIntensity: Float? = 0f,
        val tailAngle: Float? = 0f,
        val tailArrowPoint: Boolean? = false,
        val tailOffsetX: Float? = 0f,
        val tailOffsetY: Float? = 0f,
        val tailThickness: Float? = 10f,
        val tailSeed: Long? = 0L,
        val spikeIntensity: Float? = 0.5f,
        val spikeMaxLength: Float? = 30f,
        val spikeSeed: Long? = 0L,
        val glitchAmount: Float? = 20f,
        val glitchDistance: Float? = 15f,
        val glitchDirection: Float? = 0f
    )

    fun toModel(l: TextLayer): StyleModel {
        // Detect Formatting
        val isBold = l.text.getSpans(0, l.text.length, StyleSpan::class.java).any { it.style == Typeface.BOLD || it.style == Typeface.BOLD_ITALIC } || l.typeface.isBold
        val isItalic = l.text.getSpans(0, l.text.length, StyleSpan::class.java).any { it.style == Typeface.ITALIC || it.style == Typeface.BOLD_ITALIC } || l.typeface.isItalic
        val isUnderline = l.text.getSpans(0, l.text.length, UnderlineSpan::class.java).isNotEmpty()
        val isStrike = l.text.getSpans(0, l.text.length, StrikethroughSpan::class.java).isNotEmpty()

        return StyleModel(
            name = l.name,
            folderId = null,
            color = l.color,
            fontSize = l.fontSize,
            fontPath = l.fontPath,
            opacity = l.opacity,
            shadowColor = l.shadowColor,
            shadowRadius = l.shadowRadius,
            shadowDx = l.shadowDx,
            shadowDy = l.shadowDy,
            strokeColor = l.strokeColor,
            strokeWidth = l.strokeWidth,
            doubleStrokeColor = l.doubleStrokeColor,
            doubleStrokeWidth = l.doubleStrokeWidth,
            tripleStrokeColor = l.tripleStrokeColor,
            tripleStrokeWidth = l.tripleStrokeWidth,
            isRoughStroke = l.isRoughStroke,
            roughStrokeRoughness = l.roughStrokeRoughness,
            isGradient = l.isGradient,
            gradientStart = l.gradientStartColor,
            gradientEnd = l.gradientEndColor,
            gradientAngle = l.gradientAngle,
            hasMiddleColor = l.hasMiddleColor,
            gradientMiddleColor = l.gradientMiddleColor,
            gradientStartPos = l.gradientStartPos,
            gradientMiddlePos = l.gradientMiddlePos,
            gradientEndPos = l.gradientEndPos,
            gradientStrength = l.gradientStrength,
            isGradientText = l.isGradientText,
            isGradientStroke = l.isGradientStroke,
            isGradientShadow = l.isGradientShadow,
            letterSpacing = l.letterSpacing,
            lineSpacing = l.lineSpacing,
            isMotionShadow = l.isMotionShadow,
            motionAngle = l.motionShadowAngle,
            motionDist = l.motionShadowDistance,
            motionThickness = l.motionShadowThickness,
            blendMode = l.blendMode,
            isOpacityGradient = l.isOpacityGradient,
            opacityStart = l.opacityStart,
            opacityEnd = l.opacityEnd,
            opacityAngle = l.opacityAngle,
            textAlign = l.textAlign.ordinal,
            isJustified = l.isJustified,
            isBold = isBold,
            isItalic = isItalic,
            isUnderline = isUnderline,
            isStrike = isStrike,
            radialBlurInnerRadius = l.radialBlurInnerRadius,
            radialBlurMotionStrength = l.radialBlurMotionStrength,
            radialBlurCenterX = l.radialBlurCenterX,
            radialBlurCenterY = l.radialBlurCenterY,
            decayIntensity = l.decayIntensity,
            decayFadingLevel = l.decayFadingLevel,
            woodScratchIntensity = l.woodScratchIntensity,
            woodScratchColor = l.woodScratchColor,
            twistAngle = l.twistAngle,
            twistOffsetX = l.twistOffsetX,
            twistOffsetY = l.twistOffsetY,
            twistRadius = l.twistRadius,
            bulgeCenterX = l.bulgeCenterX,
            bulgeCenterY = l.bulgeCenterY,
            bulgeRadius = l.bulgeRadius,
            bulgeStrength = l.bulgeStrength,
            reflectionAlphaStart = l.reflectionAlphaStart,
            reflectionAlphaEnd = l.reflectionAlphaEnd,
            reflectionAmplitudeStart = l.reflectionAmplitudeStart,
            reflectionAmplitudeEnd = l.reflectionAmplitudeEnd,
            reflectionBoundary = l.reflectionBoundary,
            reflectionMirror = l.reflectionMirror,
            reflectionTime = l.reflectionTime,
            reflectionWavelengthStart = l.reflectionWavelengthStart,
            reflectionWavelengthEnd = l.reflectionWavelengthEnd,
            zoomBlurCenterX = l.zoomBlurCenterX,
            zoomBlurCenterY = l.zoomBlurCenterY,
            zoomBlurInnerRadius = l.zoomBlurInnerRadius,
            zoomBlurRadius = l.zoomBlurRadius,
            zoomBlurStrength = l.zoomBlurStrength,
            speedLineType = l.speedLineType,
            speedLineWidth = l.speedLineWidth,
            speedLineHeight = l.speedLineHeight,
            speedLineCount = l.speedLineCount,
            speedLineThickness = l.speedLineThickness,
            speedLineLength = l.speedLineLength,
            speedLineAdditional = l.speedLineAdditional,
            speedLineColor = l.speedLineColor,
            speedLineAngle = l.speedLineAngle,
            isPerspective = l.isPerspective,
            perspectivePoints = l.perspectivePoints,
            isWarp = l.isWarp,
            warpRows = l.warpRows,
            warpCols = l.warpCols,
            warpMesh = l.warpMesh,
            currentEffect = l.currentEffect.name,
            secondaryEffect = l.secondaryEffect.name,
            tertiaryEffect = l.tertiaryEffect.name,
            wavyIntensity = l.wavyIntensity,
            wavyFrequency = l.wavyFrequency,
            fieryColor = l.fieryColor,
            fieryIntensity = l.fieryIntensity,
            neonRadius = l.neonRadius,
            neonColor = l.neonColor,
            neonAlpha = l.neonAlpha,
            neonInnerStrength = l.neonInnerStrength,
            neonOuterStrength = l.neonOuterStrength,
            neonKnockout = l.neonKnockout,
            neonQuality = l.neonQuality,
            halftoneDotSize = l.halftoneDotSize,
            halftoneDotColor = l.halftoneDotColor,
            halftoneThreshold = l.halftoneThreshold,
            halftoneType = l.halftoneType,
            halftoneAlpha = l.halftoneAlpha,
            halftoneRange = l.halftoneRange,
            halftoneDensity = l.halftoneDensity,
            halftoneFadingIntensity = l.halftoneFadingIntensity,
            halftoneShape = l.halftoneShape,
            patternName = l.patternName,
            patternColor = l.patternColor,
            patternAlpha = l.patternAlpha,
            patternScale = l.patternScale,
            patternRotation = l.patternRotation,
            caseType = l.caseType,
            transformTypes = l.transformTypes,
            transformSizeMultiplier = l.transformSizeMultiplier,
            transformAngleMultiplier = l.transformAngleMultiplier,
            transformDotsMultiplier = l.transformDotsMultiplier,
            chromaticAngle = l.chromaticAngle,
            shadowThickness = l.shadowThickness,
            tailLength = l.tailLength,
            tailWavyIntensity = l.tailWavyIntensity,
            tailAngle = l.tailAngle,
            tailArrowPoint = l.tailArrowPoint,
            tailOffsetX = l.tailOffsetX,
            tailOffsetY = l.tailOffsetY,
            tailThickness = l.tailThickness,
            tailSeed = l.tailSeed,
            spikeIntensity = l.spikeIntensity,
            spikeMaxLength = l.spikeMaxLength,
            spikeSeed = l.spikeSeed,
            glitchAmount = l.glitchAmount,
            glitchDistance = l.glitchDistance,
            glitchDirection = l.glitchDirection
        )
    }

    fun fromModel(context: Context, m: StyleModel): TextLayer {
        val l = TextLayer("Abc")
        l.caseType = m.caseType ?: "NORMAL"
        m.transformTypes?.let { l.transformTypes = it.toMutableSet() }
        m.transformSizeMultiplier?.let { l.transformSizeMultiplier = it }
        m.transformAngleMultiplier?.let { l.transformAngleMultiplier = it }
        m.transformDotsMultiplier?.let { l.transformDotsMultiplier = it }
        l.name = m.name ?: "Style"

        // Resolve Typeface from fontPath to ensure custom fonts are loaded
        if (!m.fontPath.isNullOrEmpty()) {
            val stdFonts = FontManager.getStandardFonts(context)
            val customFonts = FontManager.getCustomFonts(context)
            val found = stdFonts.find { it.name == m.fontPath }
                ?: customFonts.find { it.path == m.fontPath }

            if (found != null) {
                l.typeface = found.typeface
            }
        }
        l.color = m.color
        l.fontSize = m.fontSize
        l.fontPath = m.fontPath

        l.opacity = m.opacity
        l.shadowColor = m.shadowColor
        l.shadowRadius = m.shadowRadius
        l.shadowDx = m.shadowDx
        l.shadowDy = m.shadowDy

        l.strokeColor = m.strokeColor
        l.strokeWidth = m.strokeWidth
        l.doubleStrokeColor = m.doubleStrokeColor
        l.doubleStrokeWidth = m.doubleStrokeWidth
        l.tripleStrokeColor = m.tripleStrokeColor ?: Color.WHITE
        l.tripleStrokeWidth = m.tripleStrokeWidth ?: 0f
        l.isRoughStroke = m.isRoughStroke ?: false
        l.roughStrokeRoughness = m.roughStrokeRoughness ?: 3f

        l.isGradient = m.isGradient
        l.gradientStartColor = m.gradientStart
        l.gradientEndColor = m.gradientEnd
        l.gradientAngle = m.gradientAngle
        l.hasMiddleColor = m.hasMiddleColor
        l.gradientMiddleColor = m.gradientMiddleColor
        l.gradientStartPos = m.gradientStartPos ?: 0.0f
        l.gradientMiddlePos = m.gradientMiddlePos ?: 0.5f
        l.gradientEndPos = m.gradientEndPos ?: 1.0f
        l.gradientStrength = m.gradientStrength
        l.isGradientText = m.isGradientText
        l.isGradientStroke = m.isGradientStroke
        l.isGradientShadow = m.isGradientShadow

        l.letterSpacing = m.letterSpacing
        l.lineSpacing = m.lineSpacing

        l.isMotionShadow = m.isMotionShadow
        l.motionShadowAngle = m.motionAngle
        l.motionShadowDistance = m.motionDist
        l.motionShadowThickness = m.motionThickness
        l.shadowThickness = m.shadowThickness ?: 0f

        l.blendMode = m.blendMode ?: "NORMAL"
        l.isOpacityGradient = m.isOpacityGradient
        l.opacityStart = m.opacityStart
        l.opacityEnd = m.opacityEnd
        l.opacityAngle = m.opacityAngle

        l.radialBlurInnerRadius = m.radialBlurInnerRadius
        l.radialBlurMotionStrength = m.radialBlurMotionStrength
        l.radialBlurCenterX = m.radialBlurCenterX
        l.radialBlurCenterY = m.radialBlurCenterY
        l.decayIntensity = m.decayIntensity
        l.decayFadingLevel = m.decayFadingLevel
        l.woodScratchIntensity = m.woodScratchIntensity
        l.woodScratchColor = m.woodScratchColor

        // Twist
        l.twistAngle = m.twistAngle
        l.twistOffsetX = m.twistOffsetX
        l.twistOffsetY = m.twistOffsetY
        l.twistRadius = m.twistRadius

        // Bulge & Pinch
        l.bulgeCenterX = m.bulgeCenterX
        l.bulgeCenterY = m.bulgeCenterY
        l.bulgeRadius = m.bulgeRadius
        l.bulgeStrength = m.bulgeStrength

        // Reflection
        l.reflectionAlphaStart = m.reflectionAlphaStart
        l.reflectionAlphaEnd = m.reflectionAlphaEnd
        l.reflectionAmplitudeStart = m.reflectionAmplitudeStart
        l.reflectionAmplitudeEnd = m.reflectionAmplitudeEnd
        l.reflectionBoundary = m.reflectionBoundary
        l.reflectionMirror = m.reflectionMirror
        l.reflectionTime = m.reflectionTime
        l.reflectionWavelengthStart = m.reflectionWavelengthStart
        l.reflectionWavelengthEnd = m.reflectionWavelengthEnd

        // Zoom Blur
        l.zoomBlurCenterX = m.zoomBlurCenterX
        l.zoomBlurCenterY = m.zoomBlurCenterY
        l.zoomBlurInnerRadius = m.zoomBlurInnerRadius
        l.zoomBlurRadius = m.zoomBlurRadius
        l.zoomBlurStrength = m.zoomBlurStrength

        // Speed Line
        m.speedLineType?.let { l.speedLineType = it }
        m.speedLineWidth?.let { l.speedLineWidth = it }
        m.speedLineHeight?.let { l.speedLineHeight = it }
        m.speedLineCount?.let { l.speedLineCount = it }
        m.speedLineThickness?.let { l.speedLineThickness = it }
        m.speedLineLength?.let { l.speedLineLength = it }
        m.speedLineAdditional?.let { l.speedLineAdditional = it }
        m.speedLineColor?.let { l.speedLineColor = it }
        m.speedLineAngle?.let { l.speedLineAngle = it }

        l.isPerspective = m.isPerspective
        l.perspectivePoints = m.perspectivePoints
        l.isWarp = m.isWarp
        l.warpRows = if (m.warpRows > 0) m.warpRows else 2
        l.warpCols = if (m.warpCols > 0) m.warpCols else 2
        l.warpMesh = m.warpMesh

        try { l.currentEffect = TextEffectType.valueOf(m.currentEffect ?: "NONE") } catch (e: Exception) {}
        try { l.secondaryEffect = TextEffectType.valueOf(m.secondaryEffect ?: "NONE") } catch (e: Exception) {}
        try { l.tertiaryEffect = TextEffectType.valueOf(m.tertiaryEffect ?: "NONE") } catch (e: Exception) {}
        l.wavyIntensity = m.wavyIntensity
        l.wavyFrequency = m.wavyFrequency
        l.fieryColor = m.fieryColor
        l.fieryIntensity = m.fieryIntensity
        l.neonRadius = m.neonRadius
        l.neonColor = m.neonColor
        l.neonAlpha = m.neonAlpha
        l.neonInnerStrength = m.neonInnerStrength
        l.neonOuterStrength = m.neonOuterStrength
        l.neonKnockout = m.neonKnockout
        l.neonQuality = m.neonQuality
        m.chromaticAngle?.let { l.chromaticAngle = it }

        // Halftone Restore
        m.halftoneDotSize?.let { l.halftoneDotSize = it }
        m.halftoneDotColor?.let { l.halftoneDotColor = it }
        m.halftoneThreshold?.let { l.halftoneThreshold = it }
        m.halftoneType?.let { l.halftoneType = it }
        m.halftoneAlpha?.let { l.halftoneAlpha = it }
        m.halftoneRange?.let { l.halftoneRange = it }
        m.halftoneDensity?.let { l.halftoneDensity = it }
        m.halftoneFadingIntensity?.let { l.halftoneFadingIntensity = it }
        m.halftoneShape?.let { l.halftoneShape = it }

        m.patternName?.let { l.patternName = it }
        m.patternColor?.let { l.patternColor = it }
        m.patternAlpha?.let { l.patternAlpha = it }
        m.patternScale?.let { l.patternScale = it }
        m.patternRotation?.let { l.patternRotation = it }

        // Text Tail Restore
        m.tailLength?.let { l.tailLength = it }
        m.tailWavyIntensity?.let { l.tailWavyIntensity = it }
        m.tailAngle?.let { l.tailAngle = it }
        m.tailArrowPoint?.let { l.tailArrowPoint = it }
        m.tailOffsetX?.let { l.tailOffsetX = it }
        m.tailOffsetY?.let { l.tailOffsetY = it }
        m.tailThickness?.let { l.tailThickness = it }
        m.tailSeed?.let { l.tailSeed = it }

        // Spike Restore
        m.spikeIntensity?.let { l.spikeIntensity = it }
        m.spikeMaxLength?.let { l.spikeMaxLength = it }
        m.spikeSeed?.let { l.spikeSeed = it }

        // Glitch Restore
        m.glitchAmount?.let { l.glitchAmount = it }
        m.glitchDistance?.let { l.glitchDistance = it }
        m.glitchDirection?.let { l.glitchDirection = it }

        // Formatting
        if (m.textAlign >= 0 && m.textAlign < Layout.Alignment.values().size) {
            l.textAlign = Layout.Alignment.values()[m.textAlign]
        }
        l.isJustified = m.isJustified

        if (m.isBold) l.text.setSpan(StyleSpan(Typeface.BOLD), 0, l.text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (m.isItalic) l.text.setSpan(StyleSpan(Typeface.ITALIC), 0, l.text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (m.isUnderline) l.text.setSpan(UnderlineSpan(), 0, l.text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (m.isStrike) l.text.setSpan(StrikethroughSpan(), 0, l.text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        return l
    }

    fun getPreview(context: Context, model: StyleModel): Bitmap {
        // Generate Preview
        val w = 150
        val h = 150
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Draw neutral mid-grey checkerboard background pattern so white text/stroke and black text are both clearly visible
        val tileSize = 15
        val paintLight = android.graphics.Paint().apply { color = 0xFFB8B8B8.toInt() }
        val paintDark = android.graphics.Paint().apply { color = 0xFF787878.toInt() }

        for (y in 0 until h step tileSize) {
            for (x in 0 until w step tileSize) {
                val isDarkSquare = ((x / tileSize) + (y / tileSize)) % 2 == 0
                val paint = if (isDarkSquare) paintDark else paintLight
                canvas.drawRect(
                    x.toFloat(),
                    y.toFloat(),
                    (x + tileSize).coerceAtMost(w).toFloat(),
                    (y + tileSize).coerceAtMost(h).toFloat(),
                    paint
                )
            }
        }

        val previewLayer = fromModel(context, model)
        previewLayer.text = SpannableStringBuilder("Abc")

        // Re-apply spans to "Abc"
        if (model.isBold) previewLayer.text.setSpan(StyleSpan(Typeface.BOLD), 0, previewLayer.text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (model.isItalic) previewLayer.text.setSpan(StyleSpan(Typeface.ITALIC), 0, previewLayer.text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (model.isUnderline) previewLayer.text.setSpan(UnderlineSpan(), 0, previewLayer.text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (model.isStrike) previewLayer.text.setSpan(StrikethroughSpan(), 0, previewLayer.text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        previewLayer.fontSize = 60f
        previewLayer.x = w/2f
        previewLayer.y = h/2f
        previewLayer.rotation = 0f
        previewLayer.scaleX = 1f
        previewLayer.scaleY = 1f

        previewLayer.draw(canvas)

        return bmp
    }
}
