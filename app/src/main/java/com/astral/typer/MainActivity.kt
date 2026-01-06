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
