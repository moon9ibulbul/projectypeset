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
        val textView: android.widget.TextView = (view as ViewGroup).getChildAt(1) as android.widget.TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER
            setPadding(4, 8, 4, 8)
        }

        val imageView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (100 * context.resources.displayMetrics.density).toInt(),
                (100 * context.resources.displayMetrics.density).toInt()
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.DKGRAY)
                cornerRadius = 4 * context.resources.displayMetrics.density
            }
        }
        container.addView(imageView)

        val textView = android.widget.TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 4, 0, 0)
        }
        container.addView(textView)

        return ViewHolder(container)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pattern = patterns[position]
        val context = holder.itemView.context

        val filename = pattern.substringAfterLast("/").substringBeforeLast(".")
        holder.textView.text = filename

        if (pattern == selectedPattern) {
            holder.itemView.setBackgroundColor(Color.parseColor("#444444"))
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }

        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                PatternManager.getPatternSampleBitmap(context, pattern, Color.WHITE, 200)
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
