package com.astral.typer

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.astral.typer.models.LayerRow
import com.astral.typer.models.TextLayer

class LayerAdapter(
    private val rowsProvider: () -> List<LayerRow>,
    private val layersProvider: () -> MutableList<com.astral.typer.models.Layer>,
    private val onItemClick: (LayerRow) -> Unit,
    private val onDeleteClick: (LayerRow) -> Unit,
    private val onDeleteGroup: (LayerRow) -> Unit,
    private val onToggleExpand: (LayerRow) -> Unit,
    private val onGroupVisibleToggle: (LayerRow) -> Unit,
    private val onGroupLockToggle: (LayerRow) -> Unit,
    private val onItemLongClick: ((LayerRow) -> Unit)? = null
) : RecyclerView.Adapter<LayerAdapter.LayerViewHolder>() {

    private var rows: List<LayerRow> = rowsProvider()

    /** Re-reads the rows, e.g. after a drag reorder or a folder expand/collapse. */
    fun refresh() {
        rows = rowsProvider()
        notifyDataSetChanged()
    }

    inner class LayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvChevron: TextView = itemView.findViewById(6)
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

        val tvChevron = TextView(context).apply {
            id = 6
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appTextColorPrimary))
            layoutParams = LinearLayout.LayoutParams(dp16, ViewGroup.LayoutParams.WRAP_CONTENT)
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

        layout.addView(tvChevron)
        layout.addView(tvName)
        layout.addView(btnVisible)
        layout.addView(btnLock)
        layout.addView(btnClip)
        layout.addView(btnDelete)

        return LayerViewHolder(layout)
    }

    override fun onBindViewHolder(holder: LayerViewHolder, position: Int) {
        val row = rows[position]
        val context = holder.itemView.context
        val dp8 = (8 * context.resources.displayMetrics.density).toInt()
        val dp16 = (16 * context.resources.displayMetrics.density).toInt()
        val iconTint = com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appIconTint)

        holder.container.setPadding(dp8, dp8, dp8, dp8)
        holder.btnClip.visibility = View.VISIBLE

        if (row.isGroupHeader) {
            holder.tvChevron.visibility = View.VISIBLE
            holder.tvChevron.text = if (row.isExpanded) "\u25BE" else "\u25B8"
            holder.tvChevron.setOnClickListener { onToggleExpand(row) }

            holder.tvName.setPadding(0, 0, 0, 0)
            holder.tvName.text = row.groupName ?: "Folder"

            holder.btnVisible.alpha = if (row.isGroupVisibleAll) 1.0f else 0.5f
            holder.btnLock.alpha = if (row.isGroupLockedAll) 1.0f else 0.5f
            holder.btnLock.setColorFilter(if (row.isGroupLockedAll) Color.RED else iconTint)
            holder.btnClip.visibility = View.INVISIBLE
            holder.btnVisible.setOnClickListener { onGroupVisibleToggle(row) }
            holder.btnLock.setOnClickListener { onGroupLockToggle(row) }
            holder.btnDelete.setOnClickListener { onDeleteGroup(row) }

            if (row.isGroupSelected) {
                val strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
                val rRadius = (4 * context.resources.displayMetrics.density)
                holder.container.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#202196F3"))
                    setStroke(strokeWidth, Color.parseColor("#2196F3"))
                    cornerRadius = rRadius
                }
            } else {
                holder.container.background = null
                holder.container.setBackgroundColor(Color.TRANSPARENT)
            }

            holder.itemView.setOnClickListener {
                onItemClick(row)
                refresh()
            }
            holder.itemView.setOnLongClickListener {
                if (onItemLongClick != null) {
                    onItemLongClick.invoke(row)
                    notifyDataSetChanged()
                    true
                } else {
                    false
                }
            }
            return
        }

        val layer = row.layer ?: return
        holder.tvChevron.visibility = View.GONE
        holder.tvChevron.setOnClickListener(null)

        val baseName = if (layer is TextLayer) "Text: ${layer.text.take(10)}" else layer.name
        val indent = dp16 * row.depth
        if (layer.isClipped) {
            holder.tvName.setPadding(indent + dp16, 0, 0, 0)
            holder.tvName.text = "\u21B3 $baseName"
        } else {
            holder.tvName.setPadding(indent, 0, 0, 0)
            holder.tvName.text = baseName
        }

        // Highlight selection
        if (layer.isSelected) {
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
        holder.btnLock.setColorFilter(if (layer.isLocked) Color.RED else iconTint)

        val layerIndex = canvasIndexOf(layer)
        if (layerIndex == 0) {
            holder.btnClip.visibility = View.INVISIBLE
        } else {
            holder.btnClip.visibility = View.VISIBLE
            holder.btnClip.alpha = if (layer.isClipped) 1.0f else 0.5f
            holder.btnClip.setColorFilter(if (layer.isClipped) Color.CYAN else iconTint)
        }

        holder.itemView.setOnClickListener {
             onItemClick(row)
             refresh()
        }

        holder.itemView.setOnLongClickListener {
             if (onItemLongClick != null) {
                 onItemLongClick.invoke(row)
                 notifyDataSetChanged()
                 true
             } else {
                 false
             }
        }

        holder.btnVisible.setOnClickListener {
             layer.isVisible = !layer.isVisible
             notifyItemChanged(position)
             onItemClick(row)
        }

        holder.btnLock.setOnClickListener {
             layer.isLocked = !layer.isLocked
             notifyItemChanged(position)
             onItemClick(row)
        }

        holder.btnClip.setOnClickListener {
             com.astral.typer.utils.UndoManager.saveState(layersProvider())
             layer.isClipped = !layer.isClipped
             notifyItemChanged(position)
             onItemClick(row)
        }

        holder.btnDelete.setOnClickListener {
            onDeleteClick(row)
        }
    }

    /** Index of the layer in the flat list, used to hide clipping on the bottom layer. */
    private fun canvasIndexOf(layer: com.astral.typer.models.Layer): Int {
        return layersProvider().indexOfFirst { it === layer }
    }

    override fun getItemCount(): Int = rows.size
}
