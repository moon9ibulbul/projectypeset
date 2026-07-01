package com.astral.typer

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.astral.typer.utils.PatternManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PatternAdapter(
    private val scope: CoroutineScope,
    private val patterns: List<String>,
    private val selectedPattern: String?,
    private val onPatternSelected: (String) -> Unit
) : RecyclerView.Adapter<PatternAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = (view as ViewGroup).getChildAt(0) as ImageView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context
        val container = LinearLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (100 * context.resources.displayMetrics.density).toInt()
            )
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
        }

        val imageView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        container.addView(imageView)
        return ViewHolder(container)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pattern = patterns[position]
        val context = holder.itemView.context

        if (pattern == selectedPattern) {
            holder.itemView.setBackgroundColor(Color.parseColor("#444444"))
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }

        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                PatternManager.getPatternBitmap(context, pattern, Color.WHITE, 150)
            }
            withContext(Dispatchers.Main) {
                holder.imageView.setImageBitmap(bitmap)
            }
        }

        holder.itemView.setOnClickListener {
            onPatternSelected(pattern)
        }
    }

    override fun getItemCount() = patterns.size
}
