package com.astral.typer.utils

import android.content.SharedPreferences
import android.widget.LinearLayout
import com.astral.typer.R

/**
 * Persists and applies the user's preferred order for the text-layer property menus
 * in the editor's bottom bar. The brush entry is intentionally excluded because it
 * only belongs to BrushLayer.
 */
object BottomMenuOrder {

    const val PREFS_NAME = "settings_prefs"
    private const val KEY_ORDER = "text_bottom_menu_order"

    data class Entry(val key: String, val viewId: Int, val label: String)

    val entries: List<Entry> = listOf(
        Entry("quick_edit", R.id.btnPropQuickEdit, "Quick Edit"),
        Entry("style", R.id.btnPropStyle, "Style"),
        Entry("font", R.id.btnPropFont, "Font"),
        Entry("format", R.id.btnPropFormat, "Formatting"),
        Entry("color", R.id.btnPropColor, "Color"),
        Entry("spacing", R.id.btnPropSpacing, "Spacing"),
        Entry("stroke", R.id.btnPropStroke, "Stroke"),
        Entry("double_stroke", R.id.btnPropDoubleStroke, "Double Stroke"),
        Entry("shadow", R.id.btnPropShadow, "Shadow"),
        Entry("gradation", R.id.btnPropGradation, "Gradation"),
        Entry("effect", R.id.btnPropEffect, "Effect"),
        Entry("texture", R.id.btnPropTexture, "Texture"),
        Entry("opacity", R.id.btnPropOpacity, "Opacity"),
        Entry("erase", R.id.btnPropErase, "Erase"),
        Entry("perspective", R.id.btnPropPerspective, "Perspective"),
        Entry("warp", R.id.btnPropWarp, "Warp"),
        Entry("puppet_warp", R.id.btnPropPuppetWarp, "Puppet Warp")
    )

    val defaultOrder: List<String> = entries.map { it.key }

    private val byKey: Map<String, Entry> = entries.associateBy { it.key }

    fun loadOrder(prefs: SharedPreferences): List<String> {
        val saved = prefs.getString(KEY_ORDER, null) ?: return defaultOrder
        val parsed = saved.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && byKey.containsKey(it) }
            .distinct()
        // Keep saved entries first, then append any newly added menu entries.
        return parsed + defaultOrder.filter { it !in parsed }
    }

    fun saveOrder(prefs: SharedPreferences, keys: List<String>) {
        prefs.edit().putString(KEY_ORDER, keys.joinToString(",")).apply()
    }

    fun resetOrder(prefs: SharedPreferences) {
        prefs.edit().remove(KEY_ORDER).apply()
    }

    /**
     * Reorders only the managed menu views inside [container]. Unmanaged children
     * (e.g. the Brush property) keep their current slots.
     */
    fun applyOrder(container: LinearLayout, prefs: SharedPreferences) {
        if (container.childCount == 0) return

        val order = loadOrder(prefs)
        val original = (0 until container.childCount).map { container.getChildAt(it) }
        val layoutParams = original.associateWith { it.layoutParams }
        val managedViewIds = entries.map { it.viewId }.toSet()
        val slots = original.indices.filter { original[it].id in managedViewIds }
        if (slots.size != order.size) return

        val orderedViews = order.mapNotNull { key ->
            val entry = byKey[key] ?: return@mapNotNull null
            original.firstOrNull { it.id == entry.viewId }
        }
        if (orderedViews.size != order.size) return

        val reordered = original.toMutableList()
        slots.forEachIndexed { index, slot -> reordered[slot] = orderedViews[index] }

        container.removeAllViews()
        for (view in reordered) {
            container.addView(view, layoutParams[view])
        }
    }
}
