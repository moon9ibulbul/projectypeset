package com.astral.typer

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.astral.typer.models.StyleModel
import com.astral.typer.utils.StyleManager

class TyperTextAdapter(
    private val context: Context,
    private val textLines: List<String>,
    private val availableStyles: List<StyleModel>,
    private val onStyleSelected: (Int, StyleModel?) -> Unit // Position, Style
) : RecyclerView.Adapter<TyperTextAdapter.ViewHolder>() {

    private var selectedPos = 0
    private val selectedStyles = mutableMapOf<Int, Int>() // Position -> StyleIndex in availableStyles

    // Default: try to find "TyperDialog" or use first
    private val defaultStyleIndex: Int

    init {
        var idx = availableStyles.indexOfFirst { it.name == "TyperDialog" }
        if (idx == -1 && availableStyles.isNotEmpty()) {
            idx = 0
        }
        defaultStyleIndex = idx
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvText: TextView = view.findViewById(R.id.tvTextContent)
        val spinnerStyle: android.widget.Spinner = view.findViewById(R.id.spinnerStyle)
        val container: View = view.findViewById(R.id.itemContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // We can dynamically create the layout xml string or file,
        // but given the constraints I will create a simple layout file or construct programmatically?
        // Actually, let's assume I need to create a layout resource or use a simple one.
        // I will create `item_typer_text.xml` next.
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_typer_text, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val text = textLines[position]
        holder.tvText.text = text

        // Setup Spinner
        val styleNames = availableStyles.map { it.name }
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, styleNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        holder.spinnerStyle.adapter = adapter

        // Set selection
        val currentStyleIdx = selectedStyles.getOrDefault(position, defaultStyleIndex)
        if (currentStyleIdx != -1 && currentStyleIdx < availableStyles.size) {
            holder.spinnerStyle.setSelection(currentStyleIdx)
        }

        // Highlight selected
        if (position == selectedPos) {
            holder.container.setBackgroundColor(Color.parseColor("#444444"))
        } else {
            holder.container.setBackgroundColor(Color.TRANSPARENT)
        }

        // Listeners
        holder.spinnerStyle.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                selectedStyles[holder.adapterPosition] = pos
                // If this is the active row, update logic
                if (holder.adapterPosition == selectedPos) {
                     onStyleSelected(holder.adapterPosition, availableStyles[pos])
                }
            }
            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
        }

        holder.container.setOnClickListener {
            val old = selectedPos
            selectedPos = holder.adapterPosition
            notifyItemChanged(old)
            notifyItemChanged(selectedPos)

            val styleIdx = selectedStyles.getOrDefault(selectedPos, defaultStyleIndex)
            val style = if (styleIdx != -1 && styleIdx < availableStyles.size) availableStyles[styleIdx] else null
            onStyleSelected(selectedPos, style)
        }
    }

    override fun getItemCount(): Int = textLines.size

    fun getSelectedText(): String? {
        if (selectedPos in textLines.indices) return textLines[selectedPos]
        return null
    }

    fun getSelectedStyle(): StyleModel? {
        if (availableStyles.isEmpty()) return null
        val idx = selectedStyles.getOrDefault(selectedPos, defaultStyleIndex)
        if (idx != -1 && idx < availableStyles.size) return availableStyles[idx]
        return null
    }

    fun advanceSelection() {
        if (selectedPos < textLines.size - 1) {
            val old = selectedPos
            selectedPos++
            notifyItemChanged(old)
            notifyItemChanged(selectedPos)

            // Trigger callback?
            // The logic in EditorActivity will likely pull current selection when clicked.
        }
    }
}
