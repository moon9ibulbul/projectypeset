package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.SpannableStringBuilder
import com.astral.typer.models.TextEffectType
import com.astral.typer.models.TextLayer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class SfxPreset(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var text: String = "BOOM!",
    var fontPath: String? = null,
    var fontSize: Float = 90f,
    var color: Int = Color.BLACK,
    var strokeColor: Int = Color.BLACK,
    var strokeWidth: Float = 0f,
    var doubleStrokeColor: Int = Color.WHITE,
    var doubleStrokeWidth: Float = 0f,
    var tripleStrokeColor: Int = Color.YELLOW,
    var tripleStrokeWidth: Float = 0f,
    var isGradientText: Boolean = false,
    var gradientStartColor: Int = Color.RED,
    var gradientEndColor: Int = Color.BLUE,
    var gradientAngle: Float = 0f,
    var letterWarpMeshes: Map<String, List<Float>>? = null,
    var letterWarpRows: Map<String, Int>? = null,
    var letterWarpCols: Map<String, Int>? = null,
    var warpMesh: List<Float>? = null,
    var warpRows: Int = 2,
    var warpCols: Int = 2,
    var isWarp: Boolean = true,
    var currentEffect: String = "NONE",
    var secondaryEffect: String = "NONE",
    var tertiaryEffect: String = "NONE",
    var glitchAmount: Float = 0f,
    var isCustom: Boolean = false
)

object SfxPresetManager {

    private const val PREFS_NAME = "sfx_preset_prefs"
    private const val KEY_CUSTOM_SFX_PRESETS = "custom_sfx_presets"

    private val customPresets = mutableListOf<SfxPreset>()

    val builtinPresets: List<SfxPreset> = listOf(
        SfxPreset(
            id = "sfx_boom",
            name = "Impact BOOM",
            text = "BOOM!",
            fontSize = 110f,
            color = Color.BLACK,
            strokeWidth = 0f,
            doubleStrokeWidth = 0f,
            tripleStrokeWidth = 0f,
            isWarp = true,
            letterWarpRows = mapOf("0" to 2, "1" to 2, "2" to 2, "3" to 2, "4" to 2),
            letterWarpCols = mapOf("0" to 2, "1" to 2, "2" to 2, "3" to 2, "4" to 2)
        ),
        SfxPreset(
            id = "sfx_shock",
            name = "Electric Shock",
            text = "SHOCK!",
            fontSize = 100f,
            color = Color.BLACK,
            strokeWidth = 0f,
            doubleStrokeWidth = 0f,
            isWarp = true
        ),
        SfxPreset(
            id = "sfx_slash",
            name = "Speed Slash",
            text = "SLASH!",
            fontSize = 105f,
            color = Color.BLACK,
            strokeWidth = 0f,
            doubleStrokeWidth = 0f,
            isWarp = true
        ),
        SfxPreset(
            id = "sfx_bang",
            name = "Explosive Bang",
            text = "BANG!",
            fontSize = 120f,
            color = Color.BLACK,
            strokeWidth = 0f,
            doubleStrokeWidth = 0f,
            tripleStrokeWidth = 0f,
            isWarp = true
        )
    )

    fun init(context: Context) {
        loadCustomPresets(context)
    }

    fun reload(context: Context) {
        loadCustomPresets(context)
    }

    private fun loadCustomPresets(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CUSTOM_SFX_PRESETS, null)
        customPresets.clear()
        if (json != null) {
            try {
                val type = object : TypeToken<List<SfxPreset>>() {}.type
                val loaded: List<SfxPreset> = Gson().fromJson(json, type)
                customPresets.addAll(loaded)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveCustomPresets(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = Gson().toJson(customPresets)
        prefs.edit().putString(KEY_CUSTOM_SFX_PRESETS, json).apply()
    }

    fun getPresets(): List<SfxPreset> {
        return builtinPresets + customPresets
    }

    fun addCustomPreset(context: Context, preset: SfxPreset) {
        preset.isCustom = true
        customPresets.add(preset)
        saveCustomPresets(context)
    }

    fun renamePreset(context: Context, id: String, newName: String) {
        val preset = customPresets.find { it.id == id }
        if (preset != null) {
            preset.name = newName
            saveCustomPresets(context)
        }
    }

    fun deletePreset(context: Context, id: String) {
        val removed = customPresets.removeAll { it.id == id }
        if (removed) {
            saveCustomPresets(context)
        }
    }

    fun createPresetFromLayer(layer: TextLayer, name: String): SfxPreset {
        val letterWarpMeshesMap = mutableMapOf<String, List<Float>>()
        layer.letterWarpMeshes.forEach { (k, v) ->
            letterWarpMeshesMap[k.toString()] = v.toList()
        }

        val letterWarpRowsMap = mutableMapOf<String, Int>()
        layer.letterWarpRows.forEach { (k, v) ->
            letterWarpRowsMap[k.toString()] = v
        }

        val letterWarpColsMap = mutableMapOf<String, Int>()
        layer.letterWarpCols.forEach { (k, v) ->
            letterWarpColsMap[k.toString()] = v
        }

        return SfxPreset(
            id = UUID.randomUUID().toString(),
            name = name,
            text = layer.text.toString(),
            fontPath = layer.fontPath,
            fontSize = layer.fontSize,
            color = Color.BLACK,
            strokeColor = Color.BLACK,
            strokeWidth = 0f,
            doubleStrokeColor = Color.WHITE,
            doubleStrokeWidth = 0f,
            tripleStrokeColor = Color.YELLOW,
            tripleStrokeWidth = 0f,
            isGradientText = false,
            letterWarpMeshes = if (letterWarpMeshesMap.isNotEmpty()) letterWarpMeshesMap else null,
            letterWarpRows = if (letterWarpRowsMap.isNotEmpty()) letterWarpRowsMap else null,
            letterWarpCols = if (letterWarpColsMap.isNotEmpty()) letterWarpColsMap else null,
            warpMesh = layer.mainWarpMesh?.toList(),
            warpRows = layer.mainWarpRows,
            warpCols = layer.mainWarpCols,
            isWarp = layer.isWarp,
            currentEffect = "NONE",
            secondaryEffect = "NONE",
            tertiaryEffect = "NONE",
            glitchAmount = 0f,
            isCustom = true
        )
    }

    fun applyPresetToLayer(preset: SfxPreset, layer: TextLayer, context: Context? = null) {
        layer.text = SpannableStringBuilder(preset.text)
        layer.fontSize = preset.fontSize
        layer.color = preset.color
        layer.strokeColor = preset.strokeColor
        layer.strokeWidth = preset.strokeWidth
        layer.doubleStrokeColor = preset.doubleStrokeColor
        layer.doubleStrokeWidth = preset.doubleStrokeWidth
        layer.tripleStrokeColor = preset.tripleStrokeColor
        layer.tripleStrokeWidth = preset.tripleStrokeWidth
        layer.isGradientText = preset.isGradientText
        layer.gradientStartColor = preset.gradientStartColor
        layer.gradientEndColor = preset.gradientEndColor
        layer.gradientAngle = preset.gradientAngle.toInt()

        layer.isWarp = preset.isWarp
        if (preset.warpMesh != null) {
            layer.warpMesh = preset.warpMesh!!.toFloatArray()
            layer.warpRows = preset.warpRows
            layer.warpCols = preset.warpCols
        }

        layer.letterWarpMeshes.clear()
        preset.letterWarpMeshes?.forEach { (k, v) ->
            val idx = k.toIntOrNull()
            if (idx != null) {
                layer.letterWarpMeshes[idx] = v.toFloatArray()
            }
        }

        layer.letterWarpRows.clear()
        preset.letterWarpRows?.forEach { (k, v) ->
            val idx = k.toIntOrNull()
            if (idx != null) {
                layer.letterWarpRows[idx] = v
            }
        }

        layer.letterWarpCols.clear()
        preset.letterWarpCols?.forEach { (k, v) ->
            val idx = k.toIntOrNull()
            if (idx != null) {
                layer.letterWarpCols[idx] = v
            }
        }

        try {
            layer.currentEffect = TextEffectType.valueOf(preset.currentEffect)
        } catch (e: Exception) {
            layer.currentEffect = TextEffectType.NONE
        }
        try {
            layer.secondaryEffect = TextEffectType.valueOf(preset.secondaryEffect)
        } catch (e: Exception) {
            layer.secondaryEffect = TextEffectType.NONE
        }
        try {
            layer.tertiaryEffect = TextEffectType.valueOf(preset.tertiaryEffect)
        } catch (e: Exception) {
            layer.tertiaryEffect = TextEffectType.NONE
        }

        layer.glitchAmount = preset.glitchAmount

        if (!preset.fontPath.isNullOrEmpty() && context != null) {
            val allFonts = FontManager.getStandardFonts(context) + FontManager.getCustomFonts(context)
            val fontItem = allFonts.find {
                (it.isCustom && it.path == preset.fontPath) ||
                (!it.isCustom && (it.path == preset.fontPath || it.name == preset.fontPath))
            }
            if (fontItem != null) {
                layer.typeface = fontItem.typeface
                layer.fontPath = if (fontItem.isCustom) fontItem.path else fontItem.name
            } else {
                layer.typeface = android.graphics.Typeface.DEFAULT
                layer.fontPath = null
            }
        } else {
            layer.typeface = android.graphics.Typeface.DEFAULT
            layer.fontPath = null
        }
    }

    fun generateThumbnail(context: Context, preset: SfxPreset, widthPx: Int = 220, heightPx: Int = 120): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = android.graphics.Paint().apply {
            color = Color.WHITE
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), bgPaint)

        val tempLayer = TextLayer(preset.text, preset.color)
        applyPresetToLayer(preset, tempLayer, context)
        tempLayer.fontSize = (heightPx * 0.4f).coerceIn(24f, 64f)

        canvas.save()
        canvas.translate(widthPx / 2f, heightPx / 2f)
        tempLayer.draw(canvas, skipEffects = true)
        canvas.restore()

        return bitmap
    }
}
