package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.astral.typer.R
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class WarpPreset(
    val id: String,
    var name: String,
    val rows: Int = 2,
    val cols: Int = 2,
    val isCustom: Boolean = false,
    val normalizedMesh: FloatArray? = null,
    val meshGenerator: ((w: Float, h: Float) -> WarpPresetResult)? = null
) {
    fun generateMesh(w: Float, h: Float): WarpPresetResult {
        if (meshGenerator != null) {
            return meshGenerator.invoke(w, h)
        }
        val norm = normalizedMesh
        if (norm != null) {
            val count = (rows + 1) * (cols + 1)
            val mesh = FloatArray(count * 2)
            for (i in 0 until minOf(mesh.size, norm.size)) {
                if (i % 2 == 0) {
                    mesh[i] = norm[i] * w
                } else {
                    mesh[i] = norm[i] * h
                }
            }
            return WarpPresetResult(rows, cols, mesh)
        }
        // Fallback default grid
        val count = (rows + 1) * (cols + 1)
        val mesh = FloatArray(count * 2)
        var idx = 0
        for (r in 0..rows) {
            val v = r / rows.toFloat()
            for (c in 0..cols) {
                val u = c / cols.toFloat()
                mesh[idx++] = -w / 2f + w * u
                mesh[idx++] = -h / 2f + h * v
            }
        }
        return WarpPresetResult(rows, cols, mesh)
    }
}

data class WarpPresetResult(
    val rows: Int,
    val cols: Int,
    val mesh: FloatArray
)

data class CustomWarpPresetModel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val rows: Int,
    val cols: Int,
    val normalizedMesh: List<Float>
)

object WarpPresetManager {

    private const val PREFS_NAME = "warp_preset_prefs"
    private const val KEY_CUSTOM_PRESETS = "custom_warp_presets"

    private val customPresets = mutableListOf<WarpPreset>()

    val builtinPresets: List<WarpPreset> = listOf(
        WarpPreset("reset", "Reset", 2, 2, meshGenerator = { w, h ->
            createGridMesh(2, 2, w, h) { _, _, x, y -> Pair(x, y) }
        }),
        WarpPreset("arc_up", "Arc Up", 2, 2, meshGenerator = { w, h ->
            createGridMesh(2, 2, w, h) { u, _, x, y ->
                val dy = -h * 0.4f * (4f * u * (1f - u))
                Pair(x, y + dy)
            }
        }),
        WarpPreset("arc_down", "Arc Down", 2, 2, meshGenerator = { w, h ->
            createGridMesh(2, 2, w, h) { u, _, x, y ->
                val dy = h * 0.4f * (4f * u * (1f - u))
                Pair(x, y + dy)
            }
        }),
        WarpPreset("arch", "Arch SFX", 2, 2, meshGenerator = { w, h ->
            createGridMesh(2, 2, w, h) { u, v, x, y ->
                val topFactor = (1f - v)
                val dy = -h * 0.45f * (4f * u * (1f - u)) * topFactor
                Pair(x, y + dy)
            }
        }),
        WarpPreset("bulge", "Bulge SFX", 2, 2, meshGenerator = { w, h ->
            createGridMesh(2, 2, w, h) { u, v, x, y ->
                val du = u - 0.5f
                val dv = v - 0.5f
                val dx = du * w * 0.5f * (1f - 4f * dv * dv)
                val dy = dv * h * 0.5f * (1f - 4f * du * du)
                Pair(x + dx, y + dy)
            }
        }),
        WarpPreset("pinch", "Pinch SFX", 2, 2, meshGenerator = { w, h ->
            createGridMesh(2, 2, w, h) { u, v, x, y ->
                val du = u - 0.5f
                val dv = v - 0.5f
                val dx = -du * w * 0.4f * (1f - 4f * dv * dv)
                val dy = -dv * h * 0.4f * (1f - 4f * du * du)
                Pair(x + dx, y + dy)
            }
        }),
        WarpPreset("wave", "Wave SFX", 2, 3, meshGenerator = { w, h ->
            createGridMesh(2, 3, w, h) { u, _, x, y ->
                val dy = Math.sin(u * Math.PI * 2.0).toFloat() * h * 0.3f
                Pair(x, y + dy)
            }
        }),
        WarpPreset("flag", "Flag SFX", 2, 2, meshGenerator = { w, h ->
            createGridMesh(2, 2, w, h) { u, _, x, y ->
                val dy = Math.sin(u * Math.PI * 1.5).toFloat() * h * 0.3f
                Pair(x, y + dy)
            }
        }),
        WarpPreset("tilt_left", "Tilt Left", 2, 2, meshGenerator = { w, h ->
            createGridMesh(2, 2, w, h) { u, _, x, y ->
                val scale = 1.4f * (1f - u) + 0.6f * u
                Pair(x, y * scale)
            }
        }),
        WarpPreset("tilt_right", "Tilt Right", 2, 2, meshGenerator = { w, h ->
            createGridMesh(2, 2, w, h) { u, _, x, y ->
                val scale = 0.6f * (1f - u) + 1.4f * u
                Pair(x, y * scale)
            }
        }),
        WarpPreset("cone_up", "Cone Up", 2, 2, meshGenerator = { w, h ->
            createGridMesh(2, 2, w, h) { _, v, x, y ->
                val scale = 1.4f * (1f - v) + 0.6f * v
                Pair(x * scale, y)
            }
        }),
        WarpPreset("cone_down", "Cone Down", 2, 2, meshGenerator = { w, h ->
            createGridMesh(2, 2, w, h) { _, v, x, y ->
                val scale = 0.6f * (1f - v) + 1.4f * v
                Pair(x * scale, y)
            }
        }),
        WarpPreset("fisheye", "Fisheye", 2, 2, meshGenerator = { w, h ->
            createGridMesh(2, 2, w, h) { u, _, x, y ->
                val du = u - 0.5f
                val factor = 1f + 0.6f * (1f - 4f * du * du).coerceAtLeast(0f)
                Pair(x, y * factor)
            }
        }),
        WarpPreset("s_curve", "S-Curve", 3, 2, meshGenerator = { w, h ->
            createGridMesh(3, 2, w, h) { _, v, x, y ->
                val dx = Math.sin(v * Math.PI * 2.0).toFloat() * w * 0.3f
                Pair(x + dx, y)
            }
        }),
        WarpPreset("tremble", "Tremble SFX", 3, 3, meshGenerator = { w, h ->
            createGridMesh(3, 3, w, h) { _, v, x, y ->
                val rIndex = Math.round(v * 3f)
                val dx = if (rIndex % 2 == 1) w * 0.15f else -w * 0.15f
                Pair(x + dx, y)
            }
        })
    )

    val presets: List<WarpPreset>
        get() = builtinPresets + customPresets

    fun init(context: Context) {
        loadCustomPresets(context)
    }

    fun reload(context: Context) {
        loadCustomPresets(context)
    }

    private fun loadCustomPresets(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CUSTOM_PRESETS, null)
        customPresets.clear()
        if (json != null) {
            try {
                val type = object : TypeToken<List<CustomWarpPresetModel>>() {}.type
                val models: List<CustomWarpPresetModel> = Gson().fromJson(json, type)
                for (model in models) {
                    customPresets.add(
                        WarpPreset(
                            id = model.id,
                            name = model.name,
                            rows = model.rows,
                            cols = model.cols,
                            isCustom = true,
                            normalizedMesh = model.normalizedMesh.toFloatArray()
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveCustomPresets(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val models = customPresets.map { preset ->
            CustomWarpPresetModel(
                id = preset.id,
                name = preset.name,
                rows = preset.rows,
                cols = preset.cols,
                normalizedMesh = preset.normalizedMesh?.toList() ?: emptyList()
            )
        }
        val json = Gson().toJson(models)
        prefs.edit().putString(KEY_CUSTOM_PRESETS, json).apply()
    }

    fun addCustomPreset(
        context: Context,
        name: String,
        rows: Int,
        cols: Int,
        rawMesh: FloatArray,
        width: Float,
        height: Float
    ): WarpPreset {
        val w = if (width <= 0f) 1f else width
        val h = if (height <= 0f) 1f else height
        val normalized = FloatArray(rawMesh.size)
        for (i in rawMesh.indices) {
            if (i % 2 == 0) {
                normalized[i] = rawMesh[i] / w
            } else {
                normalized[i] = rawMesh[i] / h
            }
        }
        val newPreset = WarpPreset(
            id = UUID.randomUUID().toString(),
            name = name,
            rows = rows,
            cols = cols,
            isCustom = true,
            normalizedMesh = normalized
        )
        customPresets.add(newPreset)
        saveCustomPresets(context)
        return newPreset
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

    private fun createGridMesh(
        rows: Int,
        cols: Int,
        w: Float,
        h: Float,
        transform: (u: Float, v: Float, x: Float, y: Float) -> Pair<Float, Float>
    ): WarpPresetResult {
        val count = (rows + 1) * (cols + 1)
        val mesh = FloatArray(count * 2)
        var idx = 0
        for (r in 0..rows) {
            val v = r / rows.toFloat()
            for (c in 0..cols) {
                val u = c / cols.toFloat()
                val origX = -w / 2f + w * u
                val origY = -h / 2f + h * v
                val (tx, ty) = transform(u, v, origX, origY)
                mesh[idx++] = tx
                mesh[idx++] = ty
            }
        }
        return WarpPresetResult(rows, cols, mesh)
    }

    fun generateThumbnail(
        context: Context,
        preset: WarpPreset,
        widthPx: Int = 120,
        heightPx: Int = 90
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val strokeColor = ThemeUtils.getColorFromAttr(context, R.attr.appTextColorPrimary)
        val accentColor = ThemeUtils.getColorFromAttr(context, R.attr.appTextColorSecondary)

        val gridW = widthPx * 0.65f
        val gridH = heightPx * 0.55f
        val presetResult = preset.generateMesh(gridW, gridH)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (strokeColor and 0x00FFFFFF) or 0x20000000
            style = Paint.Style.FILL
        }

        val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.FILL
        }

        val cx = widthPx / 2f
        val cy = heightPx / 2f

        val outPoint = FloatArray(2)
        val steps = 16

        // Evaluate outer boundary path and fill
        val outerPath = Path()
        // Top edge: v = 0, u from 0 to 1
        for (step in 0..steps) {
            val u = step / steps.toFloat()
            evaluateMesh(presetResult.mesh, presetResult.rows, presetResult.cols, u, 0f, outPoint)
            val px = cx + outPoint[0]
            val py = cy + outPoint[1]
            if (step == 0) outerPath.moveTo(px, py) else outerPath.lineTo(px, py)
        }
        // Right edge: u = 1, v from 0 to 1
        for (step in 1..steps) {
            val v = step / steps.toFloat()
            evaluateMesh(presetResult.mesh, presetResult.rows, presetResult.cols, 1f, v, outPoint)
            outerPath.lineTo(cx + outPoint[0], cy + outPoint[1])
        }
        // Bottom edge: v = 1, u from 1 to 0
        for (step in 1..steps) {
            val u = 1f - step / steps.toFloat()
            evaluateMesh(presetResult.mesh, presetResult.rows, presetResult.cols, u, 1f, outPoint)
            outerPath.lineTo(cx + outPoint[0], cy + outPoint[1])
        }
        // Left edge: u = 0, v from 1 to 0
        for (step in 1..steps) {
            val v = 1f - step / steps.toFloat()
            evaluateMesh(presetResult.mesh, presetResult.rows, presetResult.cols, 0f, v, outPoint)
            outerPath.lineTo(cx + outPoint[0], cy + outPoint[1])
        }
        outerPath.close()

        canvas.drawPath(outerPath, fillPaint)
        canvas.drawPath(outerPath, paint)

        // Draw inner grid lines
        val innerPaint = Paint(paint).apply {
            strokeWidth = 1.5f
            alpha = 180
        }

        for (r in 1 until presetResult.rows) {
            val v = r / presetResult.rows.toFloat()
            val linePath = Path()
            for (step in 0..steps) {
                val u = step / steps.toFloat()
                evaluateMesh(presetResult.mesh, presetResult.rows, presetResult.cols, u, v, outPoint)
                val px = cx + outPoint[0]
                val py = cy + outPoint[1]
                if (step == 0) linePath.moveTo(px, py) else linePath.lineTo(px, py)
            }
            canvas.drawPath(linePath, innerPaint)
        }

        for (c in 1 until presetResult.cols) {
            val u = c / presetResult.cols.toFloat()
            val linePath = Path()
            for (step in 0..steps) {
                val v = step / steps.toFloat()
                evaluateMesh(presetResult.mesh, presetResult.rows, presetResult.cols, u, v, outPoint)
                val px = cx + outPoint[0]
                val py = cy + outPoint[1]
                if (step == 0) linePath.moveTo(px, py) else linePath.lineTo(px, py)
            }
            canvas.drawPath(linePath, innerPaint)
        }

        // Draw control points
        for (r in 0..presetResult.rows) {
            val v = r / presetResult.rows.toFloat()
            for (c in 0..presetResult.cols) {
                val u = c / presetResult.cols.toFloat()
                evaluateMesh(presetResult.mesh, presetResult.rows, presetResult.cols, u, v, outPoint)
                canvas.drawCircle(cx + outPoint[0], cy + outPoint[1], 3f, pointPaint)
            }
        }

        return bitmap
    }

    private fun evaluateMesh(
        mesh: FloatArray,
        rows: Int,
        cols: Int,
        u: Float,
        v: Float,
        outPoint: FloatArray
    ) {
        var x = 0f
        var y = 0f
        for (i in 0..rows) {
            for (j in 0..cols) {
                val bi = bernstein(rows, i, v)
                val bj = bernstein(cols, j, u)
                val basis = bi * bj
                val idx = (i * (cols + 1) + j) * 2
                x += mesh[idx] * basis
                y += mesh[idx + 1] * basis
            }
        }
        outPoint[0] = x
        outPoint[1] = y
    }

    private fun bernstein(n: Int, i: Int, t: Float): Float {
        var coeff = 1f
        for (k in 1..i) {
            coeff = coeff * (n - k + 1) / k
        }
        return coeff * Math.pow(t.toDouble(), i.toDouble()).toFloat() * Math.pow((1f - t).toDouble(), (n - i).toDouble()).toFloat()
    }
}
