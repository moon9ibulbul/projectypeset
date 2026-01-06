package com.astral.typer

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.astral.typer.databinding.ActivityMainBinding
import com.astral.typer.databinding.DialogNewProjectBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { openEditorWithImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnNewProject.setOnClickListener {
            showNewProjectDialog()
        }

        binding.btnOpenProject.setOnClickListener {
            Toast.makeText(this, "Open Project Not Implemented Yet", Toast.LENGTH_SHORT).show()
        }

        binding.btnImportImage.setOnClickListener {
            getContent.launch("image/*")
        }
    }

    private fun openEditorWithImage(uri: Uri) {
        // We need to get dimensions first to set canvas size
        val options = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, options)
        }

        val width = options.outWidth
        val height = options.outHeight

        if (width > 0 && height > 0) {
            val intent = Intent(this, EditorActivity::class.java).apply {
                putExtra("CANVAS_WIDTH", width)
                putExtra("CANVAS_HEIGHT", height)
                putExtra("CANVAS_COLOR", Color.TRANSPARENT)
                putExtra("IMAGE_URI", uri.toString())
            }
            startActivity(intent)
        } else {
             Toast.makeText(this, "Failed to load image dimensions", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showColorPickerDialog(initialColor: Int, onColorSelected: (Int) -> Unit) {
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val previewView = android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 200
            ).apply { bottomMargin = 30 }
            setBackgroundColor(initialColor)
        }
        dialogView.addView(previewView)

        val hexInput = android.widget.EditText(this).apply {
            hint = "#RRGGBB"
            setText(String.format("#%06X", (0xFFFFFF and initialColor)))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        dialogView.addView(hexInput)

        var currentColor = initialColor

        val seekBars = mutableListOf<android.widget.SeekBar>()
        val colors = listOf("Red" to Color.red(initialColor), "Green" to Color.green(initialColor), "Blue" to Color.blue(initialColor))

        colors.forEachIndexed { index, (name, value) ->
            val label = android.widget.TextView(this).apply { text = name }
            dialogView.addView(label)
            val seekBar = android.widget.SeekBar(this).apply {
                max = 255
                progress = value
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(p0: android.widget.SeekBar?, p1: Int, p2: Boolean) {
                        val r = seekBars[0].progress
                        val g = seekBars[1].progress
                        val b = seekBars[2].progress
                        val newColor = Color.rgb(r, g, b)
                        currentColor = newColor
                        previewView.setBackgroundColor(newColor)
                        if (p2) { // Only update text if user moved slider
                             hexInput.setText(String.format("#%06X", (0xFFFFFF and newColor)))
                        }
                    }
                    override fun onStartTrackingTouch(p0: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(p0: android.widget.SeekBar?) {}
                })
            }
            seekBars.add(seekBar)
            dialogView.addView(seekBar)
        }

        // Handle Hex Input
        hexInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                try {
                    val colorStr = s.toString()
                    if (colorStr.length >= 7) { // #RRGGBB
                        val newColor = Color.parseColor(colorStr)
                        currentColor = newColor
                        previewView.setBackgroundColor(newColor)
                        seekBars[0].progress = Color.red(newColor)
                        seekBars[1].progress = Color.green(newColor)
                        seekBars[2].progress = Color.blue(newColor)
                    }
                } catch (e: Exception) {
                    // Ignore invalid hex
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        AlertDialog.Builder(this)
            .setTitle("Pick Color")
            .setView(dialogView)
            .setPositiveButton("Select") { _, _ -> onColorSelected(currentColor) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNewProjectDialog() {
        val dialogBinding = DialogNewProjectBinding.inflate(LayoutInflater.from(this))

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Ratio Buttons Logic
        dialogBinding.btnRatio11.setOnClickListener {
            dialogBinding.etWidth.setText("1080")
            dialogBinding.etHeight.setText("1080")
        }
        dialogBinding.btnRatio916.setOnClickListener {
            dialogBinding.etWidth.setText("1080")
            dialogBinding.etHeight.setText("1920")
        }
        dialogBinding.btnRatio169.setOnClickListener {
            dialogBinding.etWidth.setText("1920")
            dialogBinding.etHeight.setText("1080")
        }
         dialogBinding.btnRatio34.setOnClickListener {
            dialogBinding.etWidth.setText("3000")
            dialogBinding.etHeight.setText("4000")
        }

        // Color Logic
        var selectedColor = Color.WHITE
        var isTransparent = false

        dialogBinding.viewColorPreview.setBackgroundColor(selectedColor)
        dialogBinding.viewColorPreview.setOnClickListener {
            if (!isTransparent) {
                showColorPickerDialog(selectedColor) { newColor ->
                    selectedColor = newColor
                    dialogBinding.viewColorPreview.setBackgroundColor(newColor)
                }
            }
        }

        dialogBinding.cbTransparent.setOnCheckedChangeListener { _, isChecked ->
            isTransparent = isChecked
            dialogBinding.viewColorPreview.alpha = if (isChecked) 0.2f else 1.0f
            dialogBinding.viewColorPreview.isEnabled = !isChecked
        }

        dialogBinding.btnCreate.setOnClickListener {
            val widthStr = dialogBinding.etWidth.text.toString()
            val heightStr = dialogBinding.etHeight.text.toString()

            if (widthStr.isNotEmpty() && heightStr.isNotEmpty()) {
                val width = widthStr.toInt()
                val height = heightStr.toInt()

                // Pass config to Editor
                val intent = Intent(this, EditorActivity::class.java).apply {
                    putExtra("CANVAS_WIDTH", width)
                    putExtra("CANVAS_HEIGHT", height)
                    putExtra("CANVAS_COLOR", if (isTransparent) Color.TRANSPARENT else selectedColor)
                }
                startActivity(intent)
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Invalid dimensions", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }
}
