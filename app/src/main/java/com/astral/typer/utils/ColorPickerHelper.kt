package com.astral.typer.utils

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.astral.typer.R
import com.astral.typer.views.RectangularColorPickerView

object ColorPickerHelper {

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    /**
     * Creates a palette view (Horizontal Scroll) with a (+) button and saved colors.
     */
    fun createPaletteView(
        context: Context,
        onColorSelected: (Int) -> Unit,
        onAddCurrentColor: (() -> Int)? = null, // If provided, shows + button which gets color from callback
        selectedColor: Int? = null // Logic to highlight current color
    ): View {
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 8)
            gravity = Gravity.CENTER_VERTICAL
        }

        // Add Button
        if (onAddCurrentColor != null) {
            val btnAdd = TextView(context).apply {
                text = "+"
                setTextColor(Color.WHITE)
                textSize = 24f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.DKGRAY)
                    cornerRadius = dpToPx(context, 8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dpToPx(context, 40), dpToPx(context, 40)).apply {
                    setMargins(0, 0, 16, 0)
                }
                setOnClickListener {
                    val colorToSave = onAddCurrentColor()
                    ColorPaletteManager.addColor(context, colorToSave)
                    refreshPaletteList(context, list, onColorSelected, onAddCurrentColor, selectedColor)
                }
            }
            list.addView(btnAdd)
        }

        refreshPaletteList(context, list, onColorSelected, onAddCurrentColor, selectedColor)

        scroll.addView(list)
        return scroll
    }

    private fun refreshPaletteList(
        context: Context,
        container: LinearLayout,
        onColorSelected: (Int) -> Unit,
        onAddCurrentColor: (() -> Int)?,
        selectedColor: Int?
    ) {
        // Keep the first child (the add button) if it exists
        val childCount = container.childCount
        val startIndex = if (onAddCurrentColor != null) 1 else 0

        if (childCount > startIndex) {
            container.removeViews(startIndex, childCount - startIndex)
        }

        val colors = ColorPaletteManager.getSavedColors(context)
        for (color in colors) {
            val wrapper = android.widget.FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(context, 46), dpToPx(context, 46)).apply {
                     setMargins(4, 0, 4, 0)
                }
            }

            // Selection indicator (Ring)
            if (selectedColor != null && selectedColor == color) {
                val ring = View(context).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(dpToPx(context, 3), Color.CYAN)
                    }
                }
                wrapper.addView(ring)
            }

            val item = View(context).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    dpToPx(context, 40),
                    dpToPx(context, 40),
                    Gravity.CENTER
                )
                background = GradientDrawable().apply {
                    setColor(color)
                    shape = GradientDrawable.OVAL
                    setStroke(2, Color.LTGRAY)
                }
                setOnClickListener {
                    onColorSelected(color)
                    // Refresh to show selection moving
                    refreshPaletteList(context, container, onColorSelected, onAddCurrentColor, color)
                }
                setOnLongClickListener {
                    ColorPaletteManager.removeColor(context, color)
                    refreshPaletteList(context, container, onColorSelected, onAddCurrentColor, selectedColor)
                    true
                }
            }
            wrapper.addView(item)
            container.addView(wrapper)
        }
    }

    /**
     * Shows the dialog containing Rectangular Picker + Palette
     */
    fun showColorPickerDialog(
        context: Context,
        initialColor: Int,
        onColorSelected: (Int) -> Unit
    ) {
        val dialog = android.app.Dialog(context)
        var currentColor = initialColor

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#333333"))
            }
            gravity = Gravity.CENTER
        }

        // Picker
        val wheel = RectangularColorPickerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(context, 250), dpToPx(context, 200))
            setColor(initialColor)
        }

        // Hex Input
        val hexInput = EditText(context).apply {
            hint = "#RRGGBB"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(context, 16)
            }
            setText(String.format("#%06X", (0xFFFFFF and initialColor)))
        }

        // Callback
        wheel.onColorChangedListener = { color ->
             currentColor = color
             val hex = String.format("#%06X", (0xFFFFFF and color))
             if (!hexInput.hasFocus()) {
                hexInput.setText(hex)
             }
        }

        // Hex TextWatcher
        hexInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (hexInput.hasFocus()) {
                    try {
                        val colorStr = s.toString()
                        if (colorStr.length >= 7) {
                            val newColor = Color.parseColor(colorStr)
                            currentColor = newColor
                            wheel.setColor(newColor)
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Palette
        val palette = createPaletteView(context, { color ->
             currentColor = color
             wheel.setColor(color)
             val hex = String.format("#%06X", (0xFFFFFF and color))
             hexInput.setText(hex)
             // Dialog palette selection should likely also refresh to show ring?
             // Since we use the same helper, it will refresh internally if we set the listener correctly.
             // But inside Dialog, the `onColorSelected` does NOT close the dialog, it just updates `currentColor`.
             // So visually highlighting the selected one in the palette list is good.
        }, { currentColor }, initialColor)

        // Add views
        root.addView(wheel)
        root.addView(hexInput)
        root.addView(palette)

        // Buttons
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(context, 16)
            }
        }

        val btnCancel = TextView(context).apply {
             text = "Cancel"
             setTextColor(Color.WHITE)
             setPadding(16,16,16,16)
             setOnClickListener { dialog.dismiss() }
        }
        val btnOk = TextView(context).apply {
             text = "OK"
             setTextColor(Color.CYAN)
             setPadding(16,16,16,16)
             setOnClickListener {
                 onColorSelected(currentColor)
                 dialog.dismiss()
             }
        }
        buttons.addView(btnCancel)
        buttons.addView(btnOk)
        root.addView(buttons)

        dialog.setContentView(root)
        dialog.show()
    }
}
