package com.astral.typer

import android.app.Activity
import android.content.Context

object ThemeHelper {

    fun applyTheme(activity: Activity) {
        val prefs = activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val themeName = prefs.getString("app_theme", "Dark Grey")

        when (themeName) {
            "Dark Grey" -> activity.setTheme(R.style.Theme_AstralTyper)
            "Pitch Black" -> activity.setTheme(R.style.Theme_AstralTyper_PitchBlack)
            "Light Grey" -> activity.setTheme(R.style.Theme_AstralTyper_LightGrey)
            "Light" -> activity.setTheme(R.style.Theme_AstralTyper_Light)
            "Cream" -> activity.setTheme(R.style.Theme_AstralTyper_Cream)
            "Sunset" -> activity.setTheme(R.style.Theme_AstralTyper_Sunset)
            "Pink" -> activity.setTheme(R.style.Theme_AstralTyper_Pink)
            else -> activity.setTheme(R.style.Theme_AstralTyper)
        }
    }
}
