package com.astral.typer

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.astral.typer.utils.StyleManager
import com.astral.typer.utils.StyleManager.StyleModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StyleAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val styles: List<StyleModel>,
    private val onApply: (StyleModel) -> Unit,
    private val onLongClick: (View, Int, StyleModel) -> Unit
) : RecyclerView.Adapter<StyleAdapter.ViewHolder>() {

    private val previewCache = mutableMapOf<String, Bitmap>()

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivPreview: ImageView = view.findViewById(R.id.ivPreview)
        val tvName: TextView = view.findViewById(R.id.tvStyleName)
        val container: View = view.findViewById(R.id.itemContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_style, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val style = styles[position]
        holder.tvName.text = style.name.ifEmpty { "Style ${position + 1}" }

        // Clear previous preview to avoid flickering
        holder.ivPreview.setImageBitmap(null)

        val cacheKey = style.hashCode().toString()
        if (previewCache.containsKey(cacheKey)) {
            holder.ivPreview.setImageBitmap(previewCache[cacheKey])
        } else {
            scope.launch(Dispatchers.IO) {
                val preview = StyleManager.getPreview(context, style)
                withContext(Dispatchers.Main) {
                    previewCache[cacheKey] = preview
                    if (holder.adapterPosition == position) {
                        holder.ivPreview.setImageBitmap(preview)
                    }
                }
            }
        }

        holder.container.setOnClickListener { onApply(style) }
        holder.container.setOnLongClickListener {
            onLongClick(it, holder.adapterPosition, style)
            true
        }
    }

    override fun getItemCount(): Int = styles.size
}
