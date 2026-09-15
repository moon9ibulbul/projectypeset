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

data class SfxFolder(
    val id: String = UUID.randomUUID().toString(),
    val name: String
)

data class SfxPreset(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var folderId: String? = null,
    var folderIds: List<String>? = null,
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
) {
    fun getSfxFolderIds(): List<String> {
        if (!folderIds.isNullOrEmpty()) {
            return folderIds!!
        }
        if (!folderId.isNullOrEmpty()) {
            return listOf(folderId!!)
        }
        return emptyList()
    }
}

object SfxPresetManager {

    private const val PREFS_NAME = "sfx_preset_prefs"
    private const val KEY_CUSTOM_SFX_PRESETS = "custom_sfx_presets"
    private const val KEY_FOLDERS = "saved_sfx_folders"

    private val customPresets = mutableListOf<SfxPreset>()
    private val savedFolders = mutableListOf<SfxFolder>()

    val builtinPresets: List<SfxPreset> = emptyList()

    fun init(context: Context) {
        loadCustomPresets(context)
        loadFolders(context)
    }

    fun reload(context: Context) {
        loadCustomPresets(context)
        loadFolders(context)
    }

    private fun loadFolders(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_FOLDERS, null)
        savedFolders.clear()
        if (json != null) {
            try {
                val type = object : TypeToken<List<SfxFolder>>() {}.type
                val loaded: List<SfxFolder> = Gson().fromJson(json, type)
                savedFolders.addAll(loaded)
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

    fun getFolders(): List<SfxFolder> {
        return savedFolders
    }

    fun addFolder(context: Context, name: String): SfxFolder {
        val folder = SfxFolder(name = name)
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
        for (i in customPresets.indices) {
            val preset = customPresets[i]
            val currentFolderIds = preset.getSfxFolderIds()
            if (currentFolderIds.contains(id)) {
                val updatedList = currentFolderIds.filter { it != id }
                val newFolderId = if (preset.folderId == id) updatedList.firstOrNull() else preset.folderId
                preset.folderIds = updatedList
                preset.folderId = newFolderId
                modified = true
            }
        }
        persistFolders(context)
        if (modified) {
            saveCustomPresets(context)
        }
    }

    fun assignPresetToFolders(context: Context, presetId: String, folderIds: List<String>) {
        val preset = customPresets.find { it.id == presetId }
        if (preset != null) {
            val primaryFolderId = folderIds.firstOrNull()
            preset.folderIds = folderIds
            preset.folderId = primaryFolderId
            saveCustomPresets(context)
        }
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
        val all = builtinPresets + customPresets
        return all.sortedWith { a, b -> AlphanumComparator.compare(a.name, b.name) }
    }

    fun addCustomPreset(context: Context, preset: SfxPreset) {
        preset.isCustom = true
        customPresets.add(preset)
        saveCustomPresets(context)
    }

    fun updateCustomPreset(context: Context, preset: SfxPreset) {
        val index = customPresets.indexOfFirst { it.id == preset.id }
        if (index != -1) {
            preset.isCustom = true
            customPresets[index] = preset
            saveCustomPresets(context)
        }
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

    fun createPresetFromLayer(
        layer: TextLayer,
        name: String,
        existingId: String? = null,
        folderId: String? = null,
        folderIds: List<String>? = null
    ): SfxPreset {
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

        val actualFolderIds = folderIds ?: if (folderId != null) listOf(folderId) else null

        return SfxPreset(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name,
            folderId = folderId ?: actualFolderIds?.firstOrNull(),
            folderIds = actualFolderIds,
            text = layer.text.toString(),
            fontPath = layer.fontPath,
            fontSize = layer.fontSize,
            color = layer.color,
            strokeColor = layer.strokeColor,
            strokeWidth = layer.strokeWidth,
            doubleStrokeColor = layer.doubleStrokeColor,
            doubleStrokeWidth = layer.doubleStrokeWidth,
            tripleStrokeColor = layer.tripleStrokeColor,
            tripleStrokeWidth = layer.tripleStrokeWidth,
            isGradientText = layer.isGradientText,
            gradientStartColor = layer.gradientStartColor,
            gradientEndColor = layer.gradientEndColor,
            gradientAngle = layer.gradientAngle.toFloat(),
            letterWarpMeshes = if (letterWarpMeshesMap.isNotEmpty()) letterWarpMeshesMap else null,
            letterWarpRows = if (letterWarpRowsMap.isNotEmpty()) letterWarpRowsMap else null,
            letterWarpCols = if (letterWarpColsMap.isNotEmpty()) letterWarpColsMap else null,
            warpMesh = layer.mainWarpMesh?.toList() ?: layer.warpMesh?.toList(),
            warpRows = layer.mainWarpRows,
            warpCols = layer.mainWarpCols,
            isWarp = layer.isWarp,
            currentEffect = layer.currentEffect.name,
            secondaryEffect = layer.secondaryEffect.name,
            tertiaryEffect = layer.tertiaryEffect.name,
            glitchAmount = layer.glitchAmount,
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
            val fontItem = FontManager.findMatchingFont(context, preset.fontPath)
            if (fontItem != null) {
                layer.typeface = fontItem.typeface ?: android.graphics.Typeface.DEFAULT
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

    fun generateThumbnail(context: Context, preset: SfxPreset, widthPx: Int = 200, heightPx: Int = 200): Bitmap {
        val size = Math.min(widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = android.graphics.Paint().apply {
            color = Color.WHITE
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        val tempLayer = TextLayer(preset.text, preset.color)
        applyPresetToLayer(preset, tempLayer, context)

        val lw = tempLayer.getWidth().coerceAtLeast(10f)
        val lh = tempLayer.getHeight().coerceAtLeast(10f)
        val maxDim = Math.max(lw, lh)
        val fitScale = (size.toFloat() * 0.75f) / maxDim
        tempLayer.scaleX = fitScale
        tempLayer.scaleY = fitScale

        canvas.save()
        canvas.translate(size / 2f, size / 2f)
        tempLayer.draw(canvas, skipEffects = true)
        canvas.restore()

        return bitmap
    }
}
