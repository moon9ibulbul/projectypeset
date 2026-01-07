package com.astral.typer

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.astral.typer.databinding.ActivityMainBinding
import com.astral.typer.databinding.DialogNewProjectBinding
import com.astral.typer.utils.ColorPickerHelper
import com.astral.typer.utils.ProjectManager
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { openEditorWithImage(it) }
    }

    // Open Project (.atd) picker
    private val openProjectLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            // Need to copy file to cache to open? Or just read from stream?
            // ProjectManager.loadProject expects a File.
            // Copy uri content to temp file.
            try {
                val inputStream = contentResolver.openInputStream(it)
                val tempFile = File(cacheDir, "temp_open.atd")
                tempFile.outputStream().use { out ->
                    inputStream?.copyTo(out)
                }
                openProject(tempFile)
            } catch(e: Exception) {
                Toast.makeText(this, "Failed to open project file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnNewProject.setOnClickListener {
            showNewProjectDialog()
        }

        binding.btnOpenProject.setOnClickListener {
            openProjectLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }

        binding.btnImportImage.setOnClickListener {
            getContent.launch("image/*")
        }
    }

    override fun onResume() {
        super.onResume()
        setupRecentProjects()
    }

    private fun setupRecentProjects() {
        val projects = ProjectManager.getRecentProjects()

        binding.recentRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recentRecycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val layout = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = RecyclerView.LayoutParams(
                        (100 * resources.displayMetrics.density).toInt(),
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ).apply { setMargins(8,0,8,0) }
                    background = GradientDrawable().apply {
                         setColor(Color.DKGRAY)
                         cornerRadius = 16f
                         setStroke(2, Color.LTGRAY)
                    }
                    setPadding(8,8,8,8)
                }

                val img = ImageView(parent.context).apply {
                    id = View.generateViewId()
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }

                val text = TextView(parent.context).apply {
                    id = View.generateViewId()
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    gravity = Gravity.CENTER
                    textColor = Color.WHITE
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                layout.addView(img)
                layout.addView(text)

                return object : RecyclerView.ViewHolder(layout) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val file = projects[position]
                val layout = holder.itemView as LinearLayout
                val img = layout.getChildAt(0) as ImageView
                val text = layout.getChildAt(1) as TextView

                text.text = file.nameWithoutExtension
                if (file.nameWithoutExtension == "autosave") {
                    text.text = "Auto Save"
                    text.setTextColor(Color.YELLOW)
                } else {
                    text.setTextColor(Color.WHITE)
                }

                // Try to load thumbnail?
                // Currently .atd is a zip. We'd need to unzip to show thumb.
                // Too heavy for main thread list.
                // For now, just show generic icon or placeholder.
                img.setImageResource(android.R.drawable.ic_menu_gallery)
                img.setColorFilter(Color.GRAY)

                holder.itemView.setOnClickListener {
                    openProject(file)
                }
            }

            override fun getItemCount() = projects.size
        }
    }

    private fun openProject(file: File) {
        val intent = Intent(this, EditorActivity::class.java).apply {
            putExtra("PROJECT_PATH", file.absolutePath)
        }
        startActivity(intent)
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
        ColorPickerHelper.showColorPickerDialog(this, initialColor, onColorSelected)
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
