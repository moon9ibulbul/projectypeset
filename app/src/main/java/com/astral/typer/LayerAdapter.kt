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
        val btnClip: ImageView = itemView.findViewById(5) // New clip button ID set below
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
            setTextColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appTextColorPrimary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnVisible = ImageView(context).apply {
            id = 2
            layoutParams = LinearLayout.LayoutParams(dp16 * 2, dp16 * 2)
            setPadding(4,4,4,4)
            setImageResource(android.R.drawable.ic_menu_view) // Built-in eye icon? Or close enough
            // Using generic resources for simplicity or color filters
            setColorFilter(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appIconTint))
        }

        val btnLock = ImageView(context).apply {
            id = 3
            layoutParams = LinearLayout.LayoutParams(dp16 * 2, dp16 * 2)
            setPadding(4,4,4,4)
            setImageResource(android.R.drawable.ic_lock_idle_lock) // Or generic lock
            setColorFilter(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appIconTint))
        }

        val btnClip = ImageView(context).apply {
            id = 5
            layoutParams = LinearLayout.LayoutParams(dp16 * 2, dp16 * 2)
            setPadding(4,4,4,4)
            setImageResource(android.R.drawable.ic_menu_directions) // Icon for Clip to Layer Below
            setColorFilter(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appIconTint))
        }

        val btnDelete = ImageView(context).apply {
            id = 4
            layoutParams = LinearLayout.LayoutParams(dp16 * 2, dp16 * 2)
            setPadding(4,4,4,4)
            setImageResource(android.R.drawable.ic_menu_delete) // Or generic trash
            setColorFilter(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appIconTint))
        }

        layout.addView(tvName)
        layout.addView(btnVisible)
        layout.addView(btnLock)
        layout.addView(btnClip)
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
        val layerIndex = layers.size - 1 - position

        val baseName = if (layer is TextLayer) "Text: ${layer.text.take(10)}" else layer.name
        if (layer.isClipped) {
            val dp16 = (16 * holder.itemView.context.resources.displayMetrics.density).toInt()
            holder.tvName.setPadding(dp16, 0, 0, 0)
            holder.tvName.text = "↳ $baseName"
        } else {
            holder.tvName.setPadding(0, 0, 0, 0)
            holder.tvName.text = baseName
        }

        // Highlight selection
        if (layer.isSelected) {
            val context = holder.itemView.context
            val strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
            val rRadius = (4 * context.resources.displayMetrics.density)
            val drawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#202196F3"))
                setStroke(strokeWidth, Color.parseColor("#2196F3"))
                cornerRadius = rRadius
            }
            holder.container.background = drawable
        } else {
            holder.container.background = null
            holder.container.setBackgroundColor(Color.TRANSPARENT)
        }

        holder.btnVisible.alpha = if (layer.isVisible) 1.0f else 0.5f
        holder.btnLock.alpha = if (layer.isLocked) 1.0f else 0.5f
        holder.btnLock.setColorFilter(if (layer.isLocked) Color.RED else com.astral.typer.utils.ThemeUtils.getColorFromAttr(holder.itemView.context, com.astral.typer.R.attr.appIconTint))

        if (layerIndex == 0) {
            holder.btnClip.visibility = View.INVISIBLE
        } else {
            holder.btnClip.visibility = View.VISIBLE
            holder.btnClip.alpha = if (layer.isClipped) 1.0f else 0.5f
            holder.btnClip.setColorFilter(if (layer.isClipped) Color.CYAN else com.astral.typer.utils.ThemeUtils.getColorFromAttr(holder.itemView.context, com.astral.typer.R.attr.appIconTint))
        }

        holder.itemView.setOnClickListener {
             onItemClick(layer)
             notifyDataSetChanged()
        }

        holder.btnVisible.setOnClickListener {
             layer.isVisible = !layer.isVisible
             notifyItemChanged(position)
             onItemClick(layer)
        }

        holder.btnLock.setOnClickListener {
             layer.isLocked = !layer.isLocked
             notifyItemChanged(position)
             onItemClick(layer)
        }

        holder.btnClip.setOnClickListener {
             com.astral.typer.utils.UndoManager.saveState(layers)
             layer.isClipped = !layer.isClipped
             notifyItemChanged(position)
             onItemClick(layer)
        }

        holder.btnDelete.setOnClickListener {
            onDeleteClick(layer)
        }
    }

    override fun getItemCount(): Int = layers.size
}
