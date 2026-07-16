package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import com.google.gson.Gson
import java.io.InputStreamReader

object MyPaintBrushHelper {

    data class MyPaintBrushSetting(val base_value: Float = 0f)
    data class MyPaintBrushData(val settings: Map<String, MyPaintBrushSetting> = emptyMap())

    private val gson = Gson()
    private val brushCache = mutableMapOf<String, MyPaintBrushData>()

    fun getBrushData(context: Context, assetPath: String): MyPaintBrushData {
        if (brushCache.containsKey(assetPath)) {
            return brushCache[assetPath]!!
        }
        try {
            context.assets.open("brushes/$assetPath.myb").use { input ->
                val reader = InputStreamReader(input)
                val data = gson.fromJson(reader, MyPaintBrushData::class.java)
                if (data != null) {
                    brushCache[assetPath] = data
                    return data
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return MyPaintBrushData()
    }

    fun drawStroke(
        canvas: Canvas,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        brushData: MyPaintBrushData,
        baseColor: Int,
        sizeOverride: Float,
        hardnessOverride: Float,
        opacityOverride: Int
    ) {
        // Resolve brush settings from myb data combined with overrides
        val isEraser = (brushData.settings["eraser"]?.base_value ?: 0f) > 0.5f

        // Hardness base
        val baseHardness = brushData.settings["hardness"]?.base_value ?: 0.8f
        val hardness = (baseHardness * (hardnessOverride / 100f)).coerceIn(0f, 1f)

        // Opacity base
        val baseOpaque = brushData.settings["opaque"]?.base_value ?: 1.0f
        val opacity = ((baseOpaque * 255) * (opacityOverride / 255f)).toInt().coerceIn(0, 255)

        // Spacing/Dabs per radius
        val dabsPerRadius = brushData.settings["dabs_per_actual_radius"]?.base_value ?: 2.0f
        val radius = sizeOverride / 2f
        // Spacing: spacing between consecutive dabs. Lower is smoother.
        val spacing = (radius / dabsPerRadius.coerceAtLeast(0.1f)).coerceAtLeast(1f)

        val dx = x2 - x1
        val dy = y2 - y1
        val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        if (dist <= 0f) {
            drawDab(canvas, x2, y2, baseColor, radius, hardness, opacity, isEraser)
            return
        }

        var t = 0f
        while (t <= 1.0f) {
            val cx = x1 + dx * t
            drawDab(canvas, cx, y1 + dy * t, baseColor, radius, hardness, opacity, isEraser)
            t += spacing / dist
        }
        // Always ensure we draw at the final point
        drawDab(canvas, x2, y2, baseColor, radius, hardness, opacity, isEraser)
    }

    private fun drawDab(
        canvas: Canvas,
        x: Float, y: Float,
        color: Int,
        radius: Float,
        hardness: Float,
        opacity: Int,
        isEraser: Boolean
    ) {
        if (radius <= 0f) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL

        val baseAlpha = Color.alpha(color)
        val finalAlpha = ((baseAlpha / 255f) * opacity).toInt().coerceIn(0, 255)
        val resolvedColor = (color and 0x00FFFFFF) or (finalAlpha shl 24)

        if (isEraser) {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            // Use white with alpha fade for soft erase
            val eraseColor = Color.WHITE
            val resolvedEraseColor = (eraseColor and 0x00FFFFFF) or (opacity shl 24)
            if (hardness >= 0.95f) {
                paint.color = resolvedEraseColor
                canvas.drawCircle(x, y, radius, paint)
            } else {
                val colors = intArrayOf(resolvedEraseColor, resolvedEraseColor, Color.TRANSPARENT)
                val stops = floatArrayOf(0f, hardness.coerceIn(0f, 0.9f), 1.0f)
                paint.shader = RadialGradient(x, y, radius, colors, stops, Shader.TileMode.CLAMP)
                canvas.drawCircle(x, y, radius, paint)
            }
        } else {
            if (hardness >= 0.95f) {
                paint.color = resolvedColor
                canvas.drawCircle(x, y, radius, paint)
            } else {
                val colors = intArrayOf(resolvedColor, resolvedColor, (color and 0x00FFFFFF))
                val stops = floatArrayOf(0f, hardness.coerceIn(0f, 0.9f), 1.0f)
                paint.shader = RadialGradient(x, y, radius, colors, stops, Shader.TileMode.CLAMP)
                canvas.drawCircle(x, y, radius, paint)
            }
        }
    }
}
