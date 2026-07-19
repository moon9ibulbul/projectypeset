package com.astral.typer.utils

import android.content.Context
import android.graphics.PointF
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

object MyPaintBrushHelper {

    class CurveMapping(
        val baseValue: Float = 0f,
        val inputs: Map<String, List<PointF>> = emptyMap()
    ) {
        fun calculate(inputValues: Map<String, Float>): Float {
            var result = baseValue
            if (inputs.isEmpty()) return result

            for ((inputName, points) in inputs) {
                if (points.isEmpty()) continue
                val x = inputValues[inputName] ?: 0f
                if (points.size < 2) {
                    result += points[0].y
                    continue
                }
                var x0 = points[0].x
                var y0 = points[0].y
                var x1 = points[1].x
                var y1 = points[1].y

                for (i in 2 until points.size) {
                    if (x <= x1) break
                    x0 = x1
                    y0 = y1
                    x1 = points[i].x
                    y1 = points[i].y
                }

                val y = if (x0 == x1 || y0 == y1) {
                    y0
                } else {
                    (y1 * (x - x0) + y0 * (x1 - x)) / (x1 - x0)
                }
                result += y
            }
            return result
        }
    }

    data class BrushPreset(
        val name: String,
        val settings: Map<String, CurveMapping> = emptyMap(),
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
        val ellipticalDabAngle: Float = 90.0f,
        val smudge: Float = 0.0f,
        val smudgeLength: Float = 0.5f,
        val slowTracking: Float = 1.0f
    ) {
        fun getSettingValue(settingName: String, inputValues: Map<String, Float>): Float {
            return settings[settingName]?.calculate(inputValues) ?: getDefaultBaseValue(settingName)
        }

        private fun getDefaultBaseValue(settingName: String): Float {
            return when (settingName) {
                "opaque" -> opaque
                "hardness" -> hardness
                "radius_logarithmic" -> radiusLog
                "eraser" -> if (isEraser) 1.0f else 0.0f
                "dabs_per_actual_radius" -> dabsPerActualRadius
                "dabs_per_basic_radius" -> dabsPerBasicRadius
                "dabs_per_second" -> dabsPerSecond
                "offset_by_random" -> offsetByRandom
                "radius_by_random" -> radiusByRandom
                "elliptical_dab_ratio" -> ellipticalDabRatio
                "elliptical_dab_angle" -> ellipticalDabAngle
                "smudge" -> smudge
                "smudge_length" -> smudgeLength
                "slow_tracking" -> slowTracking
                else -> 0f
            }
        }
    }

    fun loadPreset(context: Context, assetPath: String): BrushPreset {
        val name = assetPath.substringAfterLast("/").substringBeforeLast(".")
        val settingsMap = mutableMapOf<String, CurveMapping>()
        try {
            val inputStream = context.assets.open(assetPath)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonStr = reader.use { it.readText() }
            val root = JSONObject(jsonStr)
            val settings = root.optJSONObject("settings")
            if (settings != null) {
                val keys = settings.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val settingObj = settings.optJSONObject(key) ?: continue
                    val baseValue = settingObj.optDouble("base_value", 0.0).toFloat()
                    val inputsMap = mutableMapOf<String, List<PointF>>()
                    val inputsObj = settingObj.optJSONObject("inputs")
                    if (inputsObj != null) {
                        val inputKeys = inputsObj.keys()
                        while (inputKeys.hasNext()) {
                            val inputKey = inputKeys.next()
                            val pointsArr = inputsObj.optJSONArray(inputKey) ?: continue
                            val pointsList = mutableListOf<PointF>()
                            for (i in 0 until pointsArr.length()) {
                                val ptArr = pointsArr.optJSONArray(i) ?: continue
                                if (ptArr.length() >= 2) {
                                    val px = ptArr.optDouble(0, 0.0).toFloat()
                                    val py = ptArr.optDouble(1, 0.0).toFloat()
                                    pointsList.add(PointF(px, py))
                                }
                            }
                            inputsMap[inputKey] = pointsList.sortedBy { it.x }
                        }
                    }
                    settingsMap[key] = CurveMapping(baseValue, inputsMap)
                }
            }

            // Expose basic properties for backward compatibility
            val opaque = settingsMap["opaque"]?.baseValue ?: 1.0f
            val hardness = settingsMap["hardness"]?.baseValue ?: 0.5f
            val radiusLog = settingsMap["radius_logarithmic"]?.baseValue ?: 0.5f
            val isEraserVal = settingsMap["eraser"]?.baseValue ?: 0.0f
            val isEraser = isEraserVal > 0.0f || name.contains("eraser", ignoreCase = true)
            val dabsPerActualRadius = settingsMap["dabs_per_actual_radius"]?.baseValue ?: 4.0f
            val dabsPerBasicRadius = settingsMap["dabs_per_basic_radius"]?.baseValue ?: 0.0f
            val dabsPerSecond = settingsMap["dabs_per_second"]?.baseValue ?: 0.0f
            val offsetByRandom = settingsMap["offset_by_random"]?.baseValue ?: 0.0f
            val radiusByRandom = settingsMap["radius_by_random"]?.baseValue ?: 0.0f
            val ellipticalDabRatio = settingsMap["elliptical_dab_ratio"]?.baseValue ?: 1.0f
            val ellipticalDabAngle = settingsMap["elliptical_dab_angle"]?.baseValue ?: 90.0f
            val smudge = settingsMap["smudge"]?.baseValue ?: 0.0f
            val smudgeLength = settingsMap["smudge_length"]?.baseValue ?: 0.5f
            val slowTracking = settingsMap["slow_tracking"]?.baseValue ?: 1.0f

            return BrushPreset(
                name = name,
                settings = settingsMap,
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
                ellipticalDabAngle = ellipticalDabAngle,
                smudge = smudge,
                smudgeLength = smudgeLength,
                slowTracking = slowTracking
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return BrushPreset(name)
        }
    }
}
