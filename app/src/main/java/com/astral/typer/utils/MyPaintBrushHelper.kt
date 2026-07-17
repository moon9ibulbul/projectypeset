package com.astral.typer.utils

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

object MyPaintBrushHelper {

    data class BrushPreset(
        val name: String,
        val opaque: Float = 1.0f,
        val hardness: Float = 0.5f,
        val radiusLog: Float = 0.5f,
        val isEraser: Boolean = false
    )

    fun loadPreset(context: Context, assetPath: String): BrushPreset {
        val name = assetPath.substringAfterLast("/").substringBeforeLast(".")
        try {
            val inputStream = context.assets.open(assetPath)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonStr = reader.use { it.readText() }
            val root = JSONObject(jsonStr)
            val settings = root.optJSONObject("settings") ?: return BrushPreset(name)

            val opaqueObj = settings.optJSONObject("opaque")
            val opaque = opaqueObj?.optDouble("base_value", 1.0)?.toFloat() ?: 1.0f

            val hardnessObj = settings.optJSONObject("hardness")
            val hardness = hardnessObj?.optDouble("base_value", 0.5)?.toFloat() ?: 0.5f

            val radiusObj = settings.optJSONObject("radius_logarithmic")
            val radiusLog = radiusObj?.optDouble("base_value", 0.5)?.toFloat() ?: 0.5f

            val eraserObj = settings.optJSONObject("eraser")
            val isEraserVal = eraserObj?.optDouble("base_value", 0.0) ?: 0.0
            val isEraser = isEraserVal > 0.0 || name.contains("eraser", ignoreCase = true)

            return BrushPreset(name, opaque, hardness, radiusLog, isEraser)
        } catch (e: Exception) {
            e.printStackTrace()
            return BrushPreset(name)
        }
    }
}
