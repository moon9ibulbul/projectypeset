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
            Color.BLACK, Color.WHITE, Color.RED, Color.GREEN, Color.BLUE,
            Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.GRAY, Color.DKGRAY
        )
    }
}
