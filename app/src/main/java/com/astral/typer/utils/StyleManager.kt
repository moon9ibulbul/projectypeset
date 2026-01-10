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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object StyleManager {
    private val savedStyles = mutableListOf<TextLayer>()
    var clipboardStyle: TextLayer? = null

    // Previews Cache (optional, but good for performance)
    private val stylePreviews = mutableMapOf<TextLayer, Bitmap>()
    private const val PREFS_NAME = "style_prefs"
    private const val KEY_STYLES = "saved_styles"

    fun init(context: Context) {
        if (savedStyles.isEmpty()) {
            loadStyles(context)
        }
    }

    fun copyStyle(layer: TextLayer) {
        clipboardStyle = layer.clone() as TextLayer
    }

    fun saveStyle(context: Context, layer: TextLayer) {
        val newStyle = layer.clone() as TextLayer
        // Reset position properties to make it generic
        newStyle.text = SpannableStringBuilder("Abc")
        newStyle.boxWidth = null

        // Ensure we capture the formatting spans from the original layer into the "Abc" text
        // Actually, layer.clone() copies text spans. But we just replaced text with "Abc".
        // We need to re-apply the formatting flags to "Abc" so the preview looks correct.
        // And when saving to model, we extract flags.

        // Check flags from original layer
        val isBold = layer.text.getSpans(0, layer.text.length, StyleSpan::class.java).any { it.style == Typeface.BOLD || it.style == Typeface.BOLD_ITALIC } || layer.typeface.isBold
        val isItalic = layer.text.getSpans(0, layer.text.length, StyleSpan::class.java).any { it.style == Typeface.ITALIC || it.style == Typeface.BOLD_ITALIC } || layer.typeface.isItalic
        val isUnderline = layer.text.getSpans(0, layer.text.length, UnderlineSpan::class.java).isNotEmpty()
        val isStrike = layer.text.getSpans(0, layer.text.length, StrikethroughSpan::class.java).isNotEmpty()

        if (isBold) newStyle.text.setSpan(StyleSpan(Typeface.BOLD), 0, newStyle.text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (isItalic) newStyle.text.setSpan(StyleSpan(Typeface.ITALIC), 0, newStyle.text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (isUnderline) newStyle.text.setSpan(UnderlineSpan(), 0, newStyle.text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (isStrike) newStyle.text.setSpan(StrikethroughSpan(), 0, newStyle.text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        savedStyles.add(newStyle)
        persistStyles(context)
    }

    fun getSavedStyles(): List<TextLayer> {
        return savedStyles
    }

    fun deleteStyle(context: Context, index: Int) {
        if (index in 0 until savedStyles.size) {
            val removed = savedStyles.removeAt(index)
            stylePreviews.remove(removed)
            persistStyles(context)
        }
    }

    fun renameStyle(context: Context, index: Int, newName: String) {
        if (index in 0 until savedStyles.size) {
            savedStyles[index].name = newName
            persistStyles(context)
        }
    }

    private fun persistStyles(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val list = savedStyles.map { toModel(it) }
        val json = Gson().toJson(list)
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
                savedStyles.addAll(list.map { fromModel(it) })
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
        val isStrike: Boolean = false
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
            isBold, isItalic, isUnderline, isStrike
        )
    }

    private fun fromModel(m: StyleModel): TextLayer {
        val l = TextLayer("Abc")
        l.name = m.name
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

    fun getPreview(layer: TextLayer): Bitmap {
        if (stylePreviews.containsKey(layer)) {
            return stylePreviews[layer]!!
        }

        // Generate Preview
        val w = 150
        val h = 150
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Draw checkered background? Or just dark gray
        canvas.drawColor(Color.DKGRAY)

        val previewLayer = layer.clone() as TextLayer
        // Ensure preview text has formatting spans
        // Cloning copies spans, so if layer has spans on "Abc", they are preserved.
        previewLayer.text = SpannableStringBuilder("Abc")

        // Re-apply spans based on layer properties (since we reset text to Abc)
        // Wait, if layer text was already "Abc" with spans, good.
        // But if layer was "Hello World" with spans, clone copies Hello World.
        // Then we set text = "Abc" -> spans lost.
        // We need to re-apply spans from the source layer (or check if source has them).

        // However, this getPreview is called usually after fromModel or saveStyle (which sets text to Abc and adds spans).
        // So previewLayer is likely already correct.
        // Just in case, let's copy spans from input layer if it is "Abc"
        if (layer.text.toString() == "Abc") {
            previewLayer.text = SpannableStringBuilder(layer.text)
        }

        previewLayer.fontSize = 60f
        previewLayer.x = w/2f
        previewLayer.y = h/2f
        previewLayer.rotation = 0f
        previewLayer.scaleX = 1f
        previewLayer.scaleY = 1f

        // Disable perspective for preview simplicity
        previewLayer.isPerspective = false

        previewLayer.draw(canvas)

        stylePreviews[layer] = bmp
        return bmp
    }
}
