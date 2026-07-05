package com.astral.typer

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.astral.typer.utils.ProjectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RecentActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: RecentGridAdapter
    private var actionMode: ActionMode? = null
    private var currentFolder: File? = null

    private val importImageLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            importImageToFolder(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recent)

        val folderPath = intent.getStringExtra("FOLDER_PATH")
        if (folderPath != null) {
            currentFolder = File(folderPath)
            title = currentFolder?.name
        }

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, 3)

        adapter = RecentGridAdapter(
            onItemClick = { file ->
                if (actionMode != null) {
                    toggleSelection(file)
                } else {
                    if (file.isDirectory) {
                        openFolder(file)
                    } else {
                        openProject(file)
                    }
                }
            },
            onItemLongClick = { file ->
                if (actionMode == null) {
                    actionMode = startSupportActionMode(actionModeCallback)
                }
                toggleSelection(file)
            }
        )
        recyclerView.adapter = adapter

        loadProjects()
    }

    private fun loadProjects() {
        lifecycleScope.launch(Dispatchers.IO) {
            val projects = ProjectManager.getRecentProjects(this@RecentActivity, currentFolder)
            withContext(Dispatchers.Main) {
                adapter.submitList(projects)
            }
        }
    }

    private fun openProject(file: File) {
        val intent = Intent(this, EditorActivity::class.java).apply {
            putExtra("PROJECT_PATH", file.absolutePath)
        }
        startActivity(intent)
    }

    private fun openFolder(file: File) {
        val intent = Intent(this, RecentActivity::class.java).apply {
            putExtra("FOLDER_PATH", file.absolutePath)
        }
        startActivity(intent)
    }

    private fun toggleSelection(file: File) {
        adapter.toggleSelection(file)
        val count = adapter.selectedItems.size
        if (count == 0) {
            actionMode?.finish()
        } else {
            actionMode?.title = "$count Selected"
            // Update menu visibility if needed
            actionMode?.invalidate()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.recent_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val isFolder = currentFolder != null
        menu.findItem(R.id.action_rename).isVisible = isFolder
        menu.findItem(R.id.action_export_pdf).isVisible = isFolder
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            R.id.action_add -> {
                if (currentFolder == null) {
                    showAddFolderDialog()
                } else {
                    importImageLauncher.launch("image/*")
                }
                return true
            }
            R.id.action_rename -> {
                showRenameFolderDialog()
                return true
            }
            R.id.action_export_pdf -> {
                exportFolderToPdf()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showAddFolderDialog() {
        val input = android.widget.EditText(this)
        input.hint = "Folder Name"
        android.app.AlertDialog.Builder(this)
            .setTitle("New Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString()
                if (name.isNotBlank()) {
                    ProjectManager.createFolder(this, currentFolder, name)
                    loadProjects()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameFolderDialog() {
        val folder = currentFolder ?: return
        val input = android.widget.EditText(this)
        input.setText(folder.name)
        android.app.AlertDialog.Builder(this)
            .setTitle("Rename Folder")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString()
                if (name.isNotBlank()) {
                    if (ProjectManager.renameFile(folder, name)) {
                        title = name
                        currentFolder = File(folder.parentFile, name)
                        loadProjects()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importImageToFolder(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    val name = "Image_${System.currentTimeMillis()}"
                    ProjectManager.saveProject(
                        this@RecentActivity,
                        emptyList(),
                        bitmap.width,
                        bitmap.height,
                        Color.TRANSPARENT,
                        bitmap,
                        name,
                        bitmap,
                        currentFolder?.name
                    )
                    withContext(Dispatchers.Main) {
                        loadProjects()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun exportFolderToPdf() {
        val folder = currentFolder ?: return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_import_loading, null)
        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.importProgressBar)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvImportStatus)
        tvStatus.text = "Exporting PDF..."

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val settingsPrefs = getSharedPreferences("settings_prefs", MODE_PRIVATE)
            val pdfQuality = settingsPrefs.getInt("pdf_quality", 80)

            val tempPdf = File(cacheDir, "export_temp.pdf")
            val success = ProjectManager.exportFolderToPdf(this@RecentActivity, folder, tempPdf, pdfQuality) { current, total ->
                runOnUiThread {
                    progressBar.max = total
                    progressBar.progress = current
                    tvStatus.text = "Processing page $current/$total..."
                }
            }

            if (success) {
                val fileName = "${folder.name}.pdf"
                var savedPath = ""

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Documents/AstralTyper")
                    }
                    val uri = contentResolver.insert(android.provider.MediaStore.Files.getContentUri("external"), contentValues)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { output ->
                            tempPdf.inputStream().use { input -> input.copyTo(output) }
                        }
                        savedPath = "Documents/AstralTyper/$fileName"
                    }
                } else {
                    val publicDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "AstralTyper")
                    if (!publicDir.exists()) publicDir.mkdirs()
                    val targetFile = File(publicDir, fileName)
                    tempPdf.copyTo(targetFile, true)
                    savedPath = targetFile.absolutePath
                }

                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Toast.makeText(this@RecentActivity, "PDF Exported to $savedPath", Toast.LENGTH_LONG).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Toast.makeText(this@RecentActivity, "Failed to export PDF", Toast.LENGTH_SHORT).show()
                }
            }
            tempPdf.delete()
        }
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(0, 1, 0, "Share").setIcon(android.R.drawable.ic_menu_share).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, 2, 0, "Remove").setIcon(android.R.drawable.ic_menu_delete).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                1 -> { // Share
                    shareSelectedProjects()
                    mode.finish()
                    true
                }
                2 -> { // Remove
                    removeSelectedProjects()
                    mode.finish()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            adapter.clearSelection()
            actionMode = null
        }
    }

    private fun shareSelectedProjects() {
        val files = adapter.selectedItems.toList()
        if (files.isEmpty()) return

        val uris = ArrayList<Uri>()
        val tempDir = File(cacheDir, "share_temp")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        for (file in files) {
            try {
                if (file.isDirectory) {
                    // Zip folder first
                    val zipFile = File(tempDir, "${file.name}.zip")
                    if (ProjectManager.zipProjectFolder(file, zipFile)) {
                        uris.add(FileProvider.getUriForFile(this, "${packageName}.provider", zipFile))
                    }
                } else {
                    uris.add(FileProvider.getUriForFile(this, "${packageName}.provider", file))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (uris.isNotEmpty()) {
            val intent = Intent().apply {
                if (uris.size == 1) {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                } else {
                    action = Intent.ACTION_SEND_MULTIPLE
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
                type = "application/zip"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Projects"))
        }
    }

    private fun removeSelectedProjects() {
        val files = adapter.selectedItems.toList()
        var deletedCount = 0

        lifecycleScope.launch(Dispatchers.IO) {
            files.forEach { file ->
                if (file.isDirectory) {
                    if (file.deleteRecursively()) {
                        deletedCount++
                    }
                } else {
                    if (file.delete()) {
                        deletedCount++
                    }
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@RecentActivity, "Removed $deletedCount projects", Toast.LENGTH_SHORT).show()
                loadProjects()
            }
        }
    }

    // Inner Adapter Class
    inner class RecentGridAdapter(
        private val onItemClick: (File) -> Unit,
        private val onItemLongClick: (File) -> Unit
    ) : RecyclerView.Adapter<RecentGridAdapter.ViewHolder>() {

        private var items = listOf<File>()
        val selectedItems = mutableSetOf<File>()

        fun submitList(newItems: List<File>) {
            items = newItems
            notifyDataSetChanged()
        }

        fun toggleSelection(file: File) {
            if (selectedItems.contains(file)) {
                selectedItems.remove(file)
            } else {
                selectedItems.add(file)
            }
            notifyDataSetChanged()
        }

        fun clearSelection() {
            selectedItems.clear()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recent_grid, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = items[position]
            holder.bind(file, selectedItems.contains(file))
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val nameText: TextView = itemView.findViewById(R.id.projectName)
            private val selectionOverlay: View = itemView.findViewById(R.id.selectionOverlay)
            private val checkIcon: View = itemView.findViewById(R.id.checkIcon)
            private val previewImage: ImageView = itemView.findViewById(R.id.projectPreview)

            fun bind(file: File, isSelected: Boolean) {
                nameText.text = file.nameWithoutExtension

                selectionOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
                checkIcon.visibility = if (isSelected) View.VISIBLE else View.GONE

                // Placeholder preview default
                if (file.isDirectory) {
                    previewImage.setImageResource(android.R.drawable.ic_menu_directions)
                } else {
                    previewImage.setImageResource(android.R.drawable.ic_menu_gallery)
                }
                previewImage.setColorFilter(Color.DKGRAY)

                // Async Load Thumbnail
                lifecycleScope.launch(Dispatchers.IO) {
                    val bmp = if (file.isDirectory) {
                        ProjectManager.loadFolderThumbnail(this@RecentActivity, file)
                    } else {
                        ProjectManager.loadThumbnail(this@RecentActivity, file)
                    }
                    withContext(Dispatchers.Main) {
                         if (bmp != null) {
                             previewImage.setImageBitmap(bmp)
                             previewImage.clearColorFilter()
                             previewImage.scaleType = ImageView.ScaleType.CENTER_CROP
                         }
                    }
                }

                itemView.setOnClickListener { onItemClick(file) }
                itemView.setOnLongClickListener {
                    onItemLongClick(file)
                    true
                }
            }
        }
    }
}
