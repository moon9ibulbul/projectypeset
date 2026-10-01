package com.astral.typer

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.astral.typer.utils.WarpPreset
import com.astral.typer.utils.WarpPresetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WarpPresetAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val presets: List<WarpPreset>,
    private val onApply: (WarpPreset) -> Unit,
    private val onLongClick: (View, WarpPreset) -> Unit
) : RecyclerView.Adapter<WarpPresetAdapter.ViewHolder>() {

    private val previewCache = mutableMapOf<String, Bitmap>()

    fun clearCache() {
        previewCache.clear()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivPreview: ImageView = view.findViewById(R.id.ivPreview)
        val tvName: TextView = view.findViewById(R.id.tvPresetName)
        val container: View = view.findViewById(R.id.itemContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_warp_preset, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val preset = presets[position]
        holder.tvName.text = preset.name

        holder.ivPreview.setImageBitmap(null)

        val cacheKey = preset.id
        if (previewCache.containsKey(cacheKey)) {
            holder.ivPreview.setImageBitmap(previewCache[cacheKey])
        } else {
            val density = context.resources.displayMetrics.density
            val widthPx = (64 * density).toInt()
            val heightPx = (48 * density).toInt()
            scope.launch(Dispatchers.IO) {
                val preview = WarpPresetManager.generateThumbnail(context, preset, widthPx, heightPx)
                withContext(Dispatchers.Main) {
                    previewCache[cacheKey] = preview
                    if (holder.adapterPosition == position) {
                        holder.ivPreview.setImageBitmap(preview)
                    }
                }
            }
        }

        holder.container.setOnClickListener { onApply(preset) }
        holder.container.setOnLongClickListener {
            onLongClick(it, preset)
            true
        }
    }

    override fun getItemCount(): Int = presets.size
}
