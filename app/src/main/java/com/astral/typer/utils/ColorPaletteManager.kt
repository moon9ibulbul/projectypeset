package com.astral.typer.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONException

object ColorPaletteManager {
    private const val PREFS_NAME = "color_palette_prefs"
    private const val KEY_PALETTE = "saved_colors"

    fun getSavedColors(context: Context): List<Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_PALETTE, null) ?: return getDefaultColors()

        val list = mutableListOf<Int>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getInt(i))
            }
        } catch (e: JSONException) {
            return getDefaultColors()
        }
        return list
    }

    fun addColor(context: Context, color: Int) {
        val colors = getSavedColors(context).toMutableList()
        // Avoid duplicates at the start? Or allow them? Usually unique is better.
        // But user might want to re-add to move to end.
        if (!colors.contains(color)) {
            colors.add(color)
            saveColors(context, colors)
        }
    }

    fun removeColor(context: Context, color: Int) {
        val colors = getSavedColors(context).toMutableList()
        colors.remove(color)
        saveColors(context, colors)
    }

    private fun saveColors(context: Context, colors: List<Int>) {
        val jsonArray = JSONArray()
        colors.forEach { jsonArray.put(it) }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PALETTE, jsonArray.toString())
            .apply()
    }

    private fun getDefaultColors(): List<Int> {
        return listOf(
            // Whites / Grays / Blacks
            Color.WHITE,
            Color.parseColor("#F5F5F5"), // White Smoke
            Color.LTGRAY,
            Color.GRAY,
            Color.DKGRAY,
            Color.BLACK,
            Color.parseColor("#212121"), // Grey 900

            // Reds
            Color.RED,
            Color.parseColor("#EF5350"), // Red 400
            Color.parseColor("#C62828"), // Red 800
            Color.parseColor("#B71C1C"), // Red 900

            // Pinks
            Color.MAGENTA,
            Color.parseColor("#F48FB1"), // Pink 200
            Color.parseColor("#EC407A"), // Pink 400
            Color.parseColor("#AD1457"), // Pink 800

            // Purples
            Color.parseColor("#CE93D8"), // Purple 200
            Color.parseColor("#AB47BC"), // Purple 400
            Color.parseColor("#6A1B9A"), // Purple 800

            // Deep Purples
            Color.parseColor("#9575CD"), // Deep Purple 300
            Color.parseColor("#512DA8"), // Deep Purple 800

            // Indigos
            Color.parseColor("#7986CB"), // Indigo 300
            Color.parseColor("#283593"), // Indigo 800

            // Blues
            Color.BLUE,
            Color.parseColor("#42A5F5"), // Blue 400
            Color.parseColor("#1565C0"), // Blue 800

            // Light Blues
            Color.parseColor("#4FC3F7"), // Light Blue 300
            Color.parseColor("#0277BD"), // Light Blue 800

            // Cyans
            Color.CYAN,
            Color.parseColor("#00838F"), // Cyan 800

            // Teals
            Color.parseColor("#4DB6AC"), // Teal 300
            Color.parseColor("#00695C"), // Teal 800

            // Greens
            Color.GREEN,
            Color.parseColor("#66BB6A"), // Green 400
            Color.parseColor("#2E7D32"), // Green 800

            // Light Greens
            Color.parseColor("#AED581"), // Light Green 300
            Color.parseColor("#558B2F"), // Light Green 800

            // Limes
            Color.parseColor("#DCE775"), // Lime 300
            Color.parseColor("#9E9D24"), // Lime 800

            // Yellows
            Color.YELLOW,
            Color.parseColor("#FBC02D"), // Yellow 700

            // Ambers
            Color.parseColor("#FFD54F"), // Amber 300
            Color.parseColor("#FF8F00"), // Amber 800

            // Oranges
            Color.parseColor("#FFB74D"), // Orange 300
            Color.parseColor("#EF6C00"), // Orange 800

            // Deep Oranges
            Color.parseColor("#FF8A65"), // Deep Orange 300
            Color.parseColor("#D84315"), // Deep Orange 800

            // Browns
            Color.parseColor("#A1887F"), // Brown 300
            Color.parseColor("#4E342E"), // Brown 800

            // Blue Greys
            Color.parseColor("#90A4AE"), // Blue Grey 300
            Color.parseColor("#37474F")  // Blue Grey 800
        )
    }
}
