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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recent)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, 3)

        adapter = RecentGridAdapter(
            onItemClick = { file ->
                if (actionMode != null) {
                    toggleSelection(file)
                } else {
                    openProject(file)
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
            val projects = ProjectManager.getRecentProjects(this@RecentActivity)
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
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
        for (file in files) {
            try {
                val uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.provider",
                    file
                )
                uris.add(uri)
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
                if (file.delete()) {
                    deletedCount++
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@RecentActivity, "Removed $deletedCount projects", Toast.LENGTH_SHORT).show()
                loadProjects()
            }
        }
    }

    // Inner Adapter Class
    class RecentGridAdapter(
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

                // Placeholder preview
                previewImage.setImageResource(android.R.drawable.ic_menu_gallery)
                previewImage.setColorFilter(Color.DKGRAY)

                itemView.setOnClickListener { onItemClick(file) }
                itemView.setOnLongClickListener {
                    onItemLongClick(file)
                    true
                }
            }
        }
    }
}
