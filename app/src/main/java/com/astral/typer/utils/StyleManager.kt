package com.astral.typer.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
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
        // Ensure name is set if missing (default to generic name logic later or current layer name)
        // If layer name is default "Layer", maybe we want "Style X"?
        // For now, keep layer name.
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
        val opacityAngle: Int
    )

    fun toModel(l: TextLayer): StyleModel {
        return StyleModel(
            l.name,
            l.color, l.fontSize, l.fontPath, l.opacity,
            l.shadowColor, l.shadowRadius, l.shadowDx, l.shadowDy,
            l.strokeColor, l.strokeWidth, l.doubleStrokeColor, l.doubleStrokeWidth,
            l.isGradient, l.gradientStartColor, l.gradientEndColor, l.gradientAngle,
            l.isGradientText, l.isGradientStroke, l.isGradientShadow,
            l.letterSpacing, l.lineSpacing,
            l.isMotionShadow, l.motionShadowAngle, l.motionShadowDistance,
            l.blendMode, l.isOpacityGradient, l.opacityStart, l.opacityEnd, l.opacityAngle
        )
    }

    private fun fromModel(m: StyleModel): TextLayer {
        val l = TextLayer("Abc")
        l.name = m.name
        l.color = m.color
        l.fontSize = m.fontSize
        l.fontPath = m.fontPath
        // Resolve Font?
        // We can't resolve Context here easily for typeface loading.
        // We'll leave typeface as default and let EditorActivity resolve it if applied?
        // Or StyleManager just holds properties.
        // When applying style, we copy properties.

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
        previewLayer.text = SpannableStringBuilder("Abc")
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
