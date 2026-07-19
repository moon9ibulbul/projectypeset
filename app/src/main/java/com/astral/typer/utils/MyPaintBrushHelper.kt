package com.astral.typer.utils

import android.content.Context
import android.graphics.PointF
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

object MyPaintBrushHelper {

    data class SettingMapping(
        val baseValue: Float = 0f,
        val inputs: Map<String, List<PointF>> = emptyMap()
    ) {
        fun calculate(inputValues: Map<String, Float>): Float {
            var value = baseValue
            for ((inputName, points) in inputs) {
                val inputValue = inputValues[inputName] ?: 0f
                value += interpolateMapping(points, inputValue)
            }
            return value
        }

        private fun interpolateMapping(points: List<PointF>, x: Float): Float {
            if (points.isEmpty()) return 0f
            if (points.size == 1) return points[0].y

            val sorted = points.sortedBy { it.x }

            // If x is before the first point, use the first segment
            if (x <= sorted[0].x) {
                val p0 = sorted[0]
                val p1 = sorted[1]
                if (p1.x == p0.x) return p0.y
                return p0.y + (p1.y - p0.y) * (x - p0.x) / (p1.x - p0.x)
            }

            // Find the segment containing x
            var p0 = sorted[0]
            var p1 = sorted[1]
            for (i in 1 until sorted.size) {
                p0 = sorted[i - 1]
                p1 = sorted[i]
                if (x <= p1.x) {
                    break
                }
            }

            if (p1.x == p0.x) return p0.y
            return p0.y + (p1.y - p0.y) * (x - p0.x) / (p1.x - p0.x)
        }
    }

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
        val ellipticalDabAngle: Float = 90.0f,
        val smudge: Float = 0.0f,
        val smudgeLength: Float = 0.5f,
        val slowTracking: Float = 1.0f,

        // Dynamic Mapped Settings
        val changeColorH: SettingMapping = SettingMapping(),
        val changeColorL: SettingMapping = SettingMapping(),
        val changeColorHslS: SettingMapping = SettingMapping(),
        val changeColorV: SettingMapping = SettingMapping(),
        val changeColorHsvS: SettingMapping = SettingMapping(),
        val colorH: SettingMapping = SettingMapping(),
        val colorS: SettingMapping = SettingMapping(),
        val colorV: SettingMapping = SettingMapping(),
        val colorize: SettingMapping = SettingMapping(),
        val strokeDurationLogarithmic: SettingMapping = SettingMapping(4.0f),
        val strokeHoldtime: SettingMapping = SettingMapping(0.0f),
        val customInput: SettingMapping = SettingMapping(),
        val customInputSlowness: SettingMapping = SettingMapping(),
        val speed1Slowness: SettingMapping = SettingMapping(),
        val speed1Gamma: SettingMapping = SettingMapping(),
        val speed2Slowness: SettingMapping = SettingMapping(),
        val speed2Gamma: SettingMapping = SettingMapping(),

        val opaqueMapping: SettingMapping = SettingMapping(1.0f),
        val hardnessMapping: SettingMapping = SettingMapping(0.5f),
        val radiusLogMapping: SettingMapping = SettingMapping(0.5f),
        val offsetByRandomMapping: SettingMapping = SettingMapping(),
        val radiusByRandomMapping: SettingMapping = SettingMapping(),
        val ellipticalDabRatioMapping: SettingMapping = SettingMapping(1.0f),
        val ellipticalDabAngleMapping: SettingMapping = SettingMapping(90.0f),
        val smudgeMapping: SettingMapping = SettingMapping(),
        val smudgeLengthMapping: SettingMapping = SettingMapping(0.5f)
    )

    private fun parseSettingMapping(settings: JSONObject, key: String, defaultBase: Float = 0f): SettingMapping {
        val obj = settings.optJSONObject(key) ?: return SettingMapping(defaultBase)
        val baseValue = obj.optDouble("base_value", defaultBase.toDouble()).toFloat()
        val inputsObj = obj.optJSONObject("inputs") ?: return SettingMapping(baseValue)
        val inputs = mutableMapOf<String, List<PointF>>()
        val keys = inputsObj.keys()
        while (keys.hasNext()) {
            val inputName = keys.next()
            val arr = inputsObj.optJSONArray(inputName)
            if (arr != null) {
                val list = mutableListOf<PointF>()
                for (i in 0 until arr.length()) {
                    val ptArr = arr.optJSONArray(i)
                    if (ptArr != null && ptArr.length() >= 2) {
                        val px = ptArr.optDouble(0, 0.0).toFloat()
                        val py = ptArr.optDouble(1, 0.0).toFloat()
                        list.add(PointF(px, py))
                    }
                }
                inputs[inputName] = list
            }
        }
        return SettingMapping(baseValue, inputs)
    }

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

            val smudgeObj = settings.optJSONObject("smudge")
            val smudge = smudgeObj?.optDouble("base_value", 0.0)?.toFloat() ?: 0.0f

            val smudgeLengthObj = settings.optJSONObject("smudge_length")
            val smudgeLength = smudgeLengthObj?.optDouble("base_value", 0.5)?.toFloat() ?: 0.5f

            val slowTrackingObj = settings.optJSONObject("slow_tracking")
            val slowTracking = slowTrackingObj?.optDouble("base_value", 1.0)?.toFloat() ?: 1.0f

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
                ellipticalDabAngle = ellipticalDabAngle,
                smudge = smudge,
                smudgeLength = smudgeLength,
                slowTracking = slowTracking,

                // Parse the setting mappings dynamically!
                changeColorH = parseSettingMapping(settings, "change_color_h", 0f),
                changeColorL = parseSettingMapping(settings, "change_color_l", 0f),
                changeColorHslS = parseSettingMapping(settings, "change_color_hsl_s", 0f),
                changeColorV = parseSettingMapping(settings, "change_color_v", 0f),
                changeColorHsvS = parseSettingMapping(settings, "change_color_hsv_s", 0f),
                colorH = parseSettingMapping(settings, "color_h", 0f),
                colorS = parseSettingMapping(settings, "color_s", 0f),
                colorV = parseSettingMapping(settings, "color_v", 0f),
                colorize = parseSettingMapping(settings, "colorize", 0f),
                strokeDurationLogarithmic = parseSettingMapping(settings, "stroke_duration_logarithmic", 4.0f),
                strokeHoldtime = parseSettingMapping(settings, "stroke_holdtime", 0.0f),
                customInput = parseSettingMapping(settings, "custom_input", 0f),
                customInputSlowness = parseSettingMapping(settings, "custom_input_slowness", 0f),
                speed1Slowness = parseSettingMapping(settings, "speed1_slowness", 0f),
                speed1Gamma = parseSettingMapping(settings, "speed1_gamma", 0f),
                speed2Slowness = parseSettingMapping(settings, "speed2_slowness", 0f),
                speed2Gamma = parseSettingMapping(settings, "speed2_gamma", 0f),

                opaqueMapping = parseSettingMapping(settings, "opaque", 1.0f),
                hardnessMapping = parseSettingMapping(settings, "hardness", 0.5f),
                radiusLogMapping = parseSettingMapping(settings, "radius_logarithmic", 0.5f),
                offsetByRandomMapping = parseSettingMapping(settings, "offset_by_random", 0f),
                radiusByRandomMapping = parseSettingMapping(settings, "radius_by_random", 0f),
                ellipticalDabRatioMapping = parseSettingMapping(settings, "elliptical_dab_ratio", 1.0f),
                ellipticalDabAngleMapping = parseSettingMapping(settings, "elliptical_dab_angle", 90.0f),
                smudgeMapping = parseSettingMapping(settings, "smudge", 0f),
                smudgeLengthMapping = parseSettingMapping(settings, "smudge_length", 0.5f)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return BrushPreset(name)
        }
    }
}