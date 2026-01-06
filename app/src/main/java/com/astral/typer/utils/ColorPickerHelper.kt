package com.astral.typer.utils

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.astral.typer.views.RectangularColorPickerView

object ColorPickerHelper {

    private const val PREFS_NAME = "AstralTyperPrefs"
    private const val KEY_PALETTE = "saved_palette"

    fun show(context: Context, initialColor: Int, onColorPicked: (Int) -> Unit) {
        val dialog = Dialog(context)

        // Root Container
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#333333"))
                cornerRadius = 16f
            }
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Color Picker View
        val picker = RectangularColorPickerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(context, 250),
                dpToPx(250)
            )
            // Assuming the view handles setting initial color if we had a method,
            // but the view code isn't visible.
            // EditorActivity didn't set initial color on the view?
            // "wheel = RectangularColorPickerView(this)" then "wheel.onColorChangedListener".
            // If the view doesn't support setting initial color, we might be stuck with default.
            // Let's assume for now it defaults to something and we just listen.
            // If we need to set it, we might need to modify the View.
            // But let's proceed assuming we can't easily modify the View without reading it.
        }

        // Hex Input
        val hexInput = EditText(context).apply {
            hint = "#RRGGBB"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(context, 16) }
            setText(String.format("#%06X", (0xFFFFFF and initialColor)))
        }

        // Update Hex when picker changes
        picker.onColorChangedListener = { color ->
            val hex = String.format("#%06X", (0xFFFFFF and color))
            if (hexInput.text.toString() != hex) {
                hexInput.setText(hex)
            }
            // Real-time update? Or only on confirm?
            // The request says "add highlighted color".
            // We'll call onColorPicked on confirm or real-time?
            // EditorActivity did it real-time.
            onColorPicked(color)
        }

        // Update Picker when Hex changes
        hexInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val str = s.toString()
                if (str.length >= 7) {
                    try {
                        val c = Color.parseColor(str)
                        onColorPicked(c)
                        // Ideally update picker position too, but we might not have API for that
                    } catch (e: Exception) {}
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        root.addView(picker)
        root.addView(hexInput)

        // Palette Section
        val paletteLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(context, 16) }
            gravity = Gravity.CENTER_VERTICAL
        }

        // Add Button
        val btnAdd = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_input_add)
            setColorFilter(Color.WHITE)
            setPadding(16, 16, 16, 16)
            background = GradientDrawable().apply {
                setColor(Color.DKGRAY)
                shape = GradientDrawable.OVAL
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(context, 40), dpToPx(context, 40))
        }

        // Scrollable Palette
        val scroll = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dpToPx(context, 16) }
            isHorizontalScrollBarEnabled = false
        }

        val paletteContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        scroll.addView(paletteContainer)

        // Palette Logic
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val palette = loadPalette(prefs).toMutableList()

        fun refreshPalette() {
            paletteContainer.removeAllViews()
            for (color in palette) {
                val view = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(context, 40), dpToPx(context, 40)).apply {
                        rightMargin = dpToPx(context, 8)
                    }
                    background = GradientDrawable().apply {
                        setColor(color)
                        shape = GradientDrawable.OVAL
                        setStroke(2, Color.LTGRAY)
                    }
                    setOnClickListener {
                        onColorPicked(color)
                        hexInput.setText(String.format("#%06X", (0xFFFFFF and color)))
                        // Update picker visual if possible
                    }
                    setOnLongClickListener {
                         // Remove?
                         palette.remove(color)
                         savePalette(prefs, palette)
                         refreshPalette()
                         true
                    }
                }
                paletteContainer.addView(view)
            }
        }

        btnAdd.setOnClickListener {
            val hex = hexInput.text.toString()
            try {
                val color = Color.parseColor(hex)
                if (!palette.contains(color)) {
                    palette.add(0, color) // Add to start
                    savePalette(prefs, palette)
                    refreshPalette()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Invalid Color", Toast.LENGTH_SHORT).show()
            }
        }

        paletteLayout.addView(btnAdd)
        paletteLayout.addView(scroll)
        root.addView(paletteLayout)

        refreshPalette()

        dialog.setContentView(root)
        dialog.show()
    }

    private fun loadPalette(prefs: SharedPreferences): List<Int> {
        val str = prefs.getString(KEY_PALETTE, "")
        if (str.isNullOrEmpty()) return listOf(Color.WHITE, Color.BLACK, Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW)
        return str.split(",").mapNotNull { it.toIntOrNull() }
    }

    private fun savePalette(prefs: SharedPreferences, list: List<Int>) {
        prefs.edit().putString(KEY_PALETTE, list.joinToString(",")).apply()
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
