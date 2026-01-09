package com.astral.typer

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TyperTextAdapter(
    private val context: Context,
    private val textLines: List<String>,
    private val styles: List<String>
) : RecyclerView.Adapter<TyperTextAdapter.ViewHolder>() {

    data class Item(val text: String, var styleName: String, var isSelected: Boolean = false)
    private val items = textLines.map { Item(it, styles.firstOrNull() ?: "") }.toMutableList()
    private var selectedPos = -1

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvText: TextView = view.findViewById(R.id.tvTextLine)
        val spinnerStyle: Spinner = view.findViewById(R.id.spinnerStyle)
        val container: View = view.findViewById(R.id.itemContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_typer_text, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvText.text = item.text

        // Setup Spinner
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, styles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        holder.spinnerStyle.adapter = adapter

        val styleIdx = styles.indexOf(item.styleName)
        if (styleIdx >= 0) holder.spinnerStyle.setSelection(styleIdx)

        holder.spinnerStyle.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
             override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                 item.styleName = styles[pos]
             }
             override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
        }

        // Selection UI
        if (selectedPos == position) {
            holder.container.setBackgroundColor(Color.DKGRAY)
        } else {
            holder.container.setBackgroundColor(Color.TRANSPARENT)
        }

        holder.container.setOnClickListener {
            val prev = selectedPos
            selectedPos = holder.adapterPosition
            notifyItemChanged(prev)
            notifyItemChanged(selectedPos)
        }
    }

    override fun getItemCount(): Int = items.size

    fun getSelectedItem(): Item? {
        if (selectedPos >= 0 && selectedPos < items.size) return items[selectedPos]
        return null
    }

    // Helper to auto select default style logic
    // Auto-select the style named "TyperDialog" if it exists in the style list.
    // Otherwise, select the first available style as default.
    // Implemented in init: styles.firstOrNull() is used.
    // I should update it to prioritize "TyperDialog".
    init {
        val defaultStyle = styles.find { it == "TyperDialog" } ?: styles.firstOrNull() ?: ""
        items.forEach { it.styleName = defaultStyle }
    }
}
