package com.astral.typer

import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
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
    private val onItemLongClick: ((LayerRow) -> Unit)? = null,
    private val onDragHandleTouch: ((RecyclerView.ViewHolder) -> Unit)? = null
) : RecyclerView.Adapter<LayerAdapter.LayerViewHolder>() {

    private var rows: List<LayerRow> = rowsProvider()

    /** Re-reads the rows, e.g. after a drag reorder or a folder expand/collapse. */
    fun refresh() {
        rows = rowsProvider()
        notifyDataSetChanged()
    }

    inner class LayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val btnDrag: ImageView = itemView.findViewById(7)
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
        val rowHeight = dp(context, 26)
        val iconSize = dp(context, 22)

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(dp(context, 6), dp(context, 2), dp(context, 4), dp(context, 2))
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnDrag = ImageView(context).apply {
            id = 7
            layoutParams = LinearLayout.LayoutParams(dp(context, 22), dp(context, 22))
            setPadding(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2))
            setImageResource(com.astral.typer.R.drawable.ic_drag_indicator)
            setColorFilter(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appIconTint))
            alpha = 0.55f
            isClickable = true
            contentDescription = "Drag to reorder layer"
        }

        val tvChevron = TextView(context).apply {
            id = 6
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appTextColorPrimary))
            layoutParams = LinearLayout.LayoutParams(dp(context, 14), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val tvName = TextView(context).apply {
            id = 1
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appTextColorPrimary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val iconTint = com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appIconTint)

        fun rowIcon(id: Int, res: Int, description: String) = ImageView(context).apply {
            this.id = id
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(res)
            setColorFilter(iconTint)
            contentDescription = description
        }

        val btnVisible = rowIcon(2, com.astral.typer.R.drawable.ic_visibility, "Toggle layer visibility")
        val btnLock = rowIcon(3, com.astral.typer.R.drawable.ic_lock, "Toggle layer lock")
        val btnClip = rowIcon(5, com.astral.typer.R.drawable.ic_layer_clip, "Clip to layer below")
        val btnDelete = rowIcon(4, com.astral.typer.R.drawable.ic_trash, "Delete layer")

        layout.minimumHeight = rowHeight
        layout.addView(btnDrag)
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
        val iconTint = com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appIconTint)

        holder.btnClip.visibility = View.VISIBLE

        // A dedicated handle avoids clashing with long-press multi-select.
        holder.btnDrag.alpha = 0.55f
        holder.btnDrag.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onDragHandleTouch?.invoke(holder)
            }
            false
        }

        if (row.isGroupHeader) {
            holder.tvChevron.visibility = View.VISIBLE
            holder.tvChevron.text = if (row.isExpanded) "\u25BE" else "\u25B8"
            holder.tvChevron.setOnClickListener { onToggleExpand(row) }

            holder.tvName.setPadding(0, 0, 0, 0)
            holder.tvName.text = row.groupName ?: "Folder"

            holder.btnVisible.alpha = if (row.isGroupVisibleAll) 1.0f else 0.4f
            holder.btnLock.alpha = if (row.isGroupLockedAll) 1.0f else 0.4f
            holder.btnLock.setColorFilter(if (row.isGroupLockedAll) Color.RED else iconTint)
            holder.btnClip.visibility = View.INVISIBLE
            holder.btnVisible.setOnClickListener { onGroupVisibleToggle(row) }
            holder.btnLock.setOnClickListener { onGroupLockToggle(row) }
            holder.btnDelete.setOnClickListener { onDeleteGroup(row) }

            if (row.isGroupSelected) {
                holder.container.background = selectionBackground(context)
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
        holder.tvName.setPadding(dp(context, 10) * row.depth, 0, 0, 0)
        holder.tvName.text = baseName

        // Highlight selection
        if (layer.isSelected) {
            holder.container.background = selectionBackground(context)
        } else {
            holder.container.background = null
            holder.container.setBackgroundColor(Color.TRANSPARENT)
        }

        holder.btnVisible.alpha = if (layer.isVisible) 1.0f else 0.4f
        holder.btnLock.alpha = if (layer.isLocked) 1.0f else 0.4f
        holder.btnLock.setColorFilter(if (layer.isLocked) Color.RED else iconTint)

        val layerIndex = canvasIndexOf(layer)
        if (layerIndex == 0) {
            holder.btnClip.visibility = View.INVISIBLE
        } else {
            holder.btnClip.visibility = View.VISIBLE
            holder.btnClip.alpha = if (layer.isClipped) 1.0f else 0.4f
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
             // Toggling clipping does not change the selection, but onItemClick can select this
             // layer and deselect another one, so every row has to be rebound.
             onItemClick(row)
             refresh()
        }

        holder.btnDelete.setOnClickListener {
            onDeleteClick(row)
        }
    }

    private fun selectionBackground(context: android.content.Context): android.graphics.drawable.GradientDrawable {
        val strokeWidth = dp(context, 1)
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#202196F3"))
            setStroke(strokeWidth, Color.parseColor("#2196F3"))
            cornerRadius = dp(context, 4).toFloat()
        }
    }

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** Index of the layer in the flat list, used to hide clipping on the bottom layer. */
    private fun canvasIndexOf(layer: com.astral.typer.models.Layer): Int {
        return layersProvider().indexOfFirst { it === layer }
    }

    override fun getItemCount(): Int = rows.size
}
