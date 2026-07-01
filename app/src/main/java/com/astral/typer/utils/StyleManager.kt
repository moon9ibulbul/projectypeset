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

object StyleManager {
    private val savedStyles = mutableListOf<StyleModel>()
    var clipboardStyle: StyleModel? = null

    private const val PREFS_NAME = "style_prefs"
    private const val KEY_STYLES = "saved_styles"

    fun init(context: Context) {
        if (savedStyles.isEmpty()) {
            loadStyles(context)
        }
    }

    fun copyStyle(layer: TextLayer) {
        clipboardStyle = toModel(layer)
    }

    fun saveStyle(context: Context, layer: TextLayer) {
        val model = toModel(layer)
        // Ensure generic preview name/text if needed?
        // The original code reset text to "Abc".
        val genericModel = model.copy(name = model.name)

        savedStyles.add(genericModel)
        persistStyles(context)
    }

    fun getSavedStyles(): List<StyleModel> {
        return savedStyles
    }

    fun deleteStyle(context: Context, index: Int) {
        if (index in 0 until savedStyles.size) {
            savedStyles.removeAt(index)
            persistStyles(context)
        }
    }

    fun renameStyle(context: Context, index: Int, newName: String) {
        if (index in 0 until savedStyles.size) {
            savedStyles[index] = savedStyles[index].copy(name = newName)
            persistStyles(context)
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

    data class StyleModel(
        val name: String = "Style",
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
        val isGradient: Boolean,
        val gradientStart: Int,
        val gradientEnd: Int,
        val gradientAngle: Int,
        val isGradientText: Boolean,
        val isGradientStroke: Boolean,
        val isGradientShadow: Boolean,
        val letterSpacing: Float,
        val lineSpacing: Float,
        // Motion Shadow
        val isMotionShadow: Boolean,
        val motionAngle: Int,
        val motionDist: Float,
        // Opacity
        val blendMode: String,
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

        // Perspective
        val isPerspective: Boolean = false,
        val perspectivePoints: FloatArray? = null,
        // Warp
        val isWarp: Boolean = false,
        val warpRows: Int = 2,
        val warpCols: Int = 2,
        val warpMesh: FloatArray? = null,

        // Built-in Pattern
        val patternName: String? = null,
        val patternColor: Int? = Color.BLACK,
        val patternAlpha: Int? = 255,
        val patternScale: Float? = 1.0f,
        val patternRotation: Float? = 0f
    )

    fun toModel(l: TextLayer): StyleModel {
        // Detect Formatting
        val isBold = l.text.getSpans(0, l.text.length, StyleSpan::class.java).any { it.style == Typeface.BOLD || it.style == Typeface.BOLD_ITALIC } || l.typeface.isBold
        val isItalic = l.text.getSpans(0, l.text.length, StyleSpan::class.java).any { it.style == Typeface.ITALIC || it.style == Typeface.BOLD_ITALIC } || l.typeface.isItalic
        val isUnderline = l.text.getSpans(0, l.text.length, UnderlineSpan::class.java).isNotEmpty()
        val isStrike = l.text.getSpans(0, l.text.length, StrikethroughSpan::class.java).isNotEmpty()

        return StyleModel(
            l.name,
            l.color, l.fontSize, l.fontPath, l.opacity,
            l.shadowColor, l.shadowRadius, l.shadowDx, l.shadowDy,
            l.strokeColor, l.strokeWidth, l.doubleStrokeColor, l.doubleStrokeWidth,
            l.isGradient, l.gradientStartColor, l.gradientEndColor, l.gradientAngle,
            l.isGradientText, l.isGradientStroke, l.isGradientShadow,
            l.letterSpacing, l.lineSpacing,
            l.isMotionShadow, l.motionShadowAngle, l.motionShadowDistance,
            l.blendMode, l.isOpacityGradient, l.opacityStart, l.opacityEnd, l.opacityAngle,
            l.textAlign.ordinal, l.isJustified,
            isBold, isItalic, isUnderline, isStrike,
            l.radialBlurInnerRadius, l.radialBlurMotionStrength,
            l.isPerspective, l.perspectivePoints,
            l.isWarp, l.warpRows, l.warpCols, l.warpMesh,
            l.patternName, l.patternColor, l.patternAlpha, l.patternScale, l.patternRotation
        )
    }

    fun fromModel(context: Context, m: StyleModel): TextLayer {
        val l = TextLayer("Abc")
        l.name = m.name

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

        l.isGradient = m.isGradient
        l.gradientStartColor = m.gradientStart
        l.gradientEndColor = m.gradientEnd
        l.gradientAngle = m.gradientAngle
        l.isGradientText = m.isGradientText
        l.isGradientStroke = m.isGradientStroke
        l.isGradientShadow = m.isGradientShadow

        l.letterSpacing = m.letterSpacing
        l.lineSpacing = m.lineSpacing

        l.isMotionShadow = m.isMotionShadow
        l.motionShadowAngle = m.motionAngle
        l.motionShadowDistance = m.motionDist

        l.blendMode = m.blendMode
        l.isOpacityGradient = m.isOpacityGradient
        l.opacityStart = m.opacityStart
        l.opacityEnd = m.opacityEnd
        l.opacityAngle = m.opacityAngle

        l.radialBlurInnerRadius = m.radialBlurInnerRadius
        l.radialBlurMotionStrength = m.radialBlurMotionStrength

        l.isPerspective = m.isPerspective
        l.perspectivePoints = m.perspectivePoints
        l.isWarp = m.isWarp
        l.warpRows = m.warpRows
        l.warpCols = m.warpCols
        l.warpMesh = m.warpMesh

        m.patternName?.let { l.patternName = it }
        m.patternColor?.let { l.patternColor = it }
        m.patternAlpha?.let { l.patternAlpha = it }
        m.patternScale?.let { l.patternScale = it }
        m.patternRotation?.let { l.patternRotation = it }

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

        // Draw dark gray background
        canvas.drawColor(Color.DKGRAY)

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
