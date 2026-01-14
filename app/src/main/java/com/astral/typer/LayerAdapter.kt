package com.astral.typer

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.astral.typer.models.Layer
import com.astral.typer.models.TextLayer

class LayerAdapter(
    private val layers: MutableList<Layer>,
    private val onItemClick: (Layer) -> Unit,
    private val onDeleteClick: (Layer) -> Unit
) : RecyclerView.Adapter<LayerAdapter.LayerViewHolder>() {

    inner class LayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(1) // ID set below
        val btnVisible: ImageView = itemView.findViewById(2)
        val btnLock: ImageView = itemView.findViewById(3)
        val btnDelete: ImageView = itemView.findViewById(4)
        val container: LinearLayout = itemView as LinearLayout
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LayerViewHolder {
        val context = parent.context
        val dp16 = (16 * context.resources.displayMetrics.density).toInt()
        val dp8 = (8 * context.resources.displayMetrics.density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(dp8, dp8, dp8, dp8)
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvName = TextView(context).apply {
            id = 1
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnVisible = ImageView(context).apply {
            id = 2
            layoutParams = LinearLayout.LayoutParams(dp16 * 2, dp16 * 2)
            setPadding(4,4,4,4)
            setImageResource(android.R.drawable.ic_menu_view) // Built-in eye icon? Or close enough
            // Using generic resources for simplicity or color filters
            setColorFilter(Color.WHITE)
        }

        val btnLock = ImageView(context).apply {
            id = 3
            layoutParams = LinearLayout.LayoutParams(dp16 * 2, dp16 * 2)
            setPadding(4,4,4,4)
            setImageResource(android.R.drawable.ic_lock_idle_lock) // Or generic lock
            setColorFilter(Color.WHITE)
        }

        val btnDelete = ImageView(context).apply {
            id = 4
            layoutParams = LinearLayout.LayoutParams(dp16 * 2, dp16 * 2)
            setPadding(4,4,4,4)
            setImageResource(android.R.drawable.ic_menu_delete) // Or generic trash
            setColorFilter(Color.WHITE)
        }

        layout.addView(tvName)
        layout.addView(btnVisible)
        layout.addView(btnLock)
        layout.addView(btnDelete)

        return LayerViewHolder(layout)
    }

    override fun onBindViewHolder(holder: LayerViewHolder, position: Int) {
        // Reverse order so top layer is at top of list?
        // Usually Layer 0 is bottom.
        // Prompt says "Duplicate text always on top".
        // Layers at end of list are drawn last (on top).
        // So List should show End -> Top, Start -> Bottom.
        val layer = layers[layers.size - 1 - position]

        holder.tvName.text = if (layer is TextLayer) "Text: ${layer.text.take(10)}" else layer.name

        // Highlight selection
        if (layer.isSelected) {
            holder.container.setBackgroundColor(Color.parseColor("#444444"))
        } else {
             holder.container.setBackgroundColor(Color.TRANSPARENT)
        }

        holder.btnVisible.alpha = if (layer.isVisible) 1.0f else 0.5f
        holder.btnLock.alpha = if (layer.isLocked) 1.0f else 0.5f
        holder.btnLock.setColorFilter(if (layer.isLocked) Color.RED else Color.WHITE)

        holder.itemView.setOnClickListener {
             onItemClick(layer)
             notifyDataSetChanged()
        }

        holder.btnVisible.setOnClickListener {
             layer.isVisible = !layer.isVisible
             notifyItemChanged(position)
             // Force redraw main canvas?
             // We need a callback or reference to canvas view invalidation.
             // But clicking item selects it which invalidates.
             // Here we might need to trigger invalidation manually if it doesn't select.
             onItemClick(layer)
        }

        holder.btnLock.setOnClickListener {
             layer.isLocked = !layer.isLocked
             notifyItemChanged(position)
             onItemClick(layer)
        }

        holder.btnDelete.setOnClickListener {
            onDeleteClick(layer)
        }
    }

    override fun getItemCount(): Int = layers.size
}
