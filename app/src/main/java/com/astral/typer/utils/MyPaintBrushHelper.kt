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
        val isEraser: Boolean = false,
        val dabsPerActualRadius: Float = 4.0f,
        val dabsPerBasicRadius: Float = 0.0f,
        val dabsPerSecond: Float = 0.0f,
        val offsetByRandom: Float = 0.0f,
        val radiusByRandom: Float = 0.0f,
        val ellipticalDabRatio: Float = 1.0f,
        val ellipticalDabAngle: Float = 90.0f
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

            val dabsPerActualRadiusObj = settings.optJSONObject("dabs_per_actual_radius")
            val dabsPerActualRadius = dabsPerActualRadiusObj?.optDouble("base_value", 4.0)?.toFloat() ?: 4.0f

            val dabsPerBasicRadiusObj = settings.optJSONObject("dabs_per_basic_radius")
            val dabsPerBasicRadius = dabsPerBasicRadiusObj?.optDouble("base_value", 0.0)?.toFloat() ?: 0.0f

            val dabsPerSecondObj = settings.optJSONObject("dabs_per_second")
            val dabsPerSecond = dabsPerSecondObj?.optDouble("base_value", 0.0)?.toFloat() ?: 0.0f

            val offsetByRandomObj = settings.optJSONObject("offset_by_random")
            val offsetByRandom = offsetByRandomObj?.optDouble("base_value", 0.0)?.toFloat() ?: 0.0f

            val radiusByRandomObj = settings.optJSONObject("radius_by_random")
            val radiusByRandom = radiusByRandomObj?.optDouble("base_value", 0.0)?.toFloat() ?: 0.0f

            val ellipticalDabRatioObj = settings.optJSONObject("elliptical_dab_ratio")
            val ellipticalDabRatio = ellipticalDabRatioObj?.optDouble("base_value", 1.0)?.toFloat() ?: 1.0f

            val ellipticalDabAngleObj = settings.optJSONObject("elliptical_dab_angle")
            val ellipticalDabAngle = ellipticalDabAngleObj?.optDouble("base_value", 90.0)?.toFloat() ?: 90.0f

            return BrushPreset(
                name = name,
                opaque = opaque,
                hardness = hardness,
                radiusLog = radiusLog,
                isEraser = isEraser,
                dabsPerActualRadius = dabsPerActualRadius,
                dabsPerBasicRadius = dabsPerBasicRadius,
                dabsPerSecond = dabsPerSecond,
                offsetByRandom = offsetByRandom,
                radiusByRandom = radiusByRandom,
                ellipticalDabRatio = ellipticalDabRatio,
                ellipticalDabAngle = ellipticalDabAngle
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return BrushPreset(name)
        }
    }
}
