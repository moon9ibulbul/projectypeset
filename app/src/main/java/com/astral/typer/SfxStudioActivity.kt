package com.astral.typer

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.astral.typer.models.TextEffectType
import com.astral.typer.utils.ColorPickerHelper
import com.astral.typer.utils.FontManager
import com.astral.typer.utils.SfxPresetManager
import com.astral.typer.utils.ThemeUtils
import com.astral.typer.views.SfxCanvasView

class SfxStudioActivity : AppCompatActivity() {

    private lateinit var sfxCanvasView: SfxCanvasView
    private lateinit var tvModeIndicator: TextView
    private lateinit var rgMode: RadioGroup
    private lateinit var rbVectorMode: RadioButton
    private lateinit var rbMoveRotateMode: RadioButton
    private lateinit var btnResetAllPoints: Button
    private lateinit var etSfxText: EditText
    private lateinit var spinnerLetterSelect: Spinner
    private lateinit var btnAddVectorPoints: Button
    private lateinit var btnReduceVectorPoints: Button
    private lateinit var btnSelectFont: Button

    private var isUpdatingSpinner = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sfx_studio)

        SfxPresetManager.init(this)

        // Bind Views
        sfxCanvasView = findViewById(R.id.sfxCanvasView)
        tvModeIndicator = findViewById(R.id.tvModeIndicator)
        rgMode = findViewById(R.id.rgMode)
        rbVectorMode = findViewById(R.id.rbVectorMode)
        rbMoveRotateMode = findViewById(R.id.rbMoveRotateMode)
        btnResetAllPoints = findViewById(R.id.btnResetAllPoints)
        etSfxText = findViewById(R.id.etSfxText)
        spinnerLetterSelect = findViewById(R.id.spinnerLetterSelect)
        btnAddVectorPoints = findViewById(R.id.btnAddVectorPoints)
        btnReduceVectorPoints = findViewById(R.id.btnReduceVectorPoints)
        btnSelectFont = findViewById(R.id.btnSelectFont)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.btnPresets).setOnClickListener {
            showSavedPresetsDialog()
        }

        findViewById<View>(R.id.btnSavePreset).setOnClickListener {
            showSavePresetDialog()
        }

        // Mode Radio Group
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbVectorMode -> {
                    sfxCanvasView.currentMode = SfxCanvasView.Mode.VECTOR_EDIT
                    tvModeIndicator.text = "Mode: Edit Vector Points"
                }
                R.id.rbMoveRotateMode -> {
                    sfxCanvasView.currentMode = SfxCanvasView.Mode.MOVE_ROTATE
                    tvModeIndicator.text = "Mode: Move & Rotate Letter"
                }
            }
        }

        btnResetAllPoints.setOnClickListener {
            sfxCanvasView.resetAllMeshes()
            Toast.makeText(this, "Reset all vector points", Toast.LENGTH_SHORT).show()
        }

        // Text Watcher
        etSfxText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val newText = s?.toString() ?: ""
                sfxCanvasView.sfxLayer.text = SpannableStringBuilder(newText)
                sfxCanvasView.initAllCharMeshes()
                updateLetterSpinner()
                sfxCanvasView.invalidate()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        updateLetterSpinner()

        spinnerLetterSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isUpdatingSpinner) {
                    sfxCanvasView.selectedCharIndex = position
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnAddVectorPoints.setOnClickListener {
            sfxCanvasView.subdivideSelectedCharMesh()
            Toast.makeText(this, "Added vector control points", Toast.LENGTH_SHORT).show()
        }

        btnReduceVectorPoints.setOnClickListener {
            sfxCanvasView.reduceSelectedCharMesh()
            Toast.makeText(this, "Reduced vector control points", Toast.LENGTH_SHORT).show()
        }

        btnSelectFont.setOnClickListener {
            showFontPickerDialog()
        }
    }

    private fun updateLetterSpinner() {
        isUpdatingSpinner = true
        val textStr = sfxCanvasView.sfxLayer.text.toString()
        val letterItems = mutableListOf<String>()
        for (i in textStr.indices) {
            letterItems.add("${i + 1}: '${textStr[i]}'")
        }
        if (letterItems.isEmpty()) letterItems.add("1: ''")

        val adapter = ArrayAdapter(this, R.layout.item_spinner, letterItems)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        spinnerLetterSelect.adapter = adapter

        val selIdx = sfxCanvasView.selectedCharIndex.coerceIn(0, letterItems.size - 1)
        spinnerLetterSelect.setSelection(selIdx)
        isUpdatingSpinner = false
    }

    private fun showFontPickerDialog() {
        val allFonts = FontManager.getStandardFonts(this) + FontManager.getCustomFonts(this)
        val fontNames = allFonts.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Font")
            .setItems(fontNames) { _, which ->
                val fontItem = allFonts[which]
                sfxCanvasView.sfxLayer.typeface = fontItem.typeface
                sfxCanvasView.sfxLayer.fontPath = if (fontItem.isCustom) fontItem.path else fontItem.name
                sfxCanvasView.invalidate()
                Toast.makeText(this, "Font applied: ${fontItem.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSavedPresetsDialog() {
        SfxPresetManager.init(this)
        val presets = SfxPresetManager.getPresets()

        if (presets.isEmpty()) {
            Toast.makeText(this, "No saved presets found", Toast.LENGTH_SHORT).show()
            return
        }

        var dialog: AlertDialog? = null

        val recyclerView = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@SfxStudioActivity, 2)
            val paddingPx = (8 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            clipToPadding = false
        }

        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val cardLayout = android.widget.LinearLayout(this@SfxStudioActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    val padding = (8 * resources.displayMetrics.density).toInt()
                    setPadding(padding, padding, padding, padding)
                    layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        val margin = (4 * resources.displayMetrics.density).toInt()
                        setMargins(margin, margin, margin, margin)
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(ThemeUtils.getColorFromAttr(this@SfxStudioActivity, R.attr.appCardBgColor))
                        setStroke((1 * resources.displayMetrics.density).toInt(), ThemeUtils.getColorFromAttr(this@SfxStudioActivity, R.attr.appCardBorderColor))
                        cornerRadius = 8 * resources.displayMetrics.density
                    }
                }

                val imageView = android.widget.ImageView(this@SfxStudioActivity).apply {
                    val size = (120 * resources.displayMetrics.density).toInt()
                    layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }

                val tvName = TextView(this@SfxStudioActivity).apply {
                    textSize = 12f
                    setTextColor(ThemeUtils.getColorFromAttr(this@SfxStudioActivity, R.attr.appTextColorPrimary))
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
                }

                cardLayout.addView(imageView)
                cardLayout.addView(tvName)

                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(cardLayout) {}
            }

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val preset = presets[position]
                val cardLayout = holder.itemView as android.widget.LinearLayout
                val imageView = cardLayout.getChildAt(0) as android.widget.ImageView
                val tvName = cardLayout.getChildAt(1) as TextView

                tvName.text = preset.name
                val thumbSize = (120 * resources.displayMetrics.density).toInt()
                val thumbnail = SfxPresetManager.generateThumbnail(this@SfxStudioActivity, preset, thumbSize, thumbSize)
                imageView.setImageBitmap(thumbnail)

                cardLayout.setOnClickListener {
                    showPresetActionDialog(preset, dialog)
                }
            }

            override fun getItemCount(): Int = presets.size
        }

        recyclerView.adapter = adapter

        dialog = AlertDialog.Builder(this)
            .setTitle("Saved SFX Presets")
            .setView(recyclerView)
            .setNegativeButton("Close", null)
            .create()

        dialog.show()
    }

    private fun showPresetActionDialog(preset: com.astral.typer.utils.SfxPreset, parentDialog: AlertDialog?) {
        val options = mutableListOf<String>()
        options.add("Edit / Load to Canvas")
        if (preset.isCustom) {
            options.add("Rename Preset")
            options.add("Delete Preset")
        }

        AlertDialog.Builder(this)
            .setTitle(preset.name)
            .setItems(options.toTypedArray()) { _, which ->
                val selectedOption = options[which]
                when {
                    selectedOption.startsWith("Edit") -> {
                        loadPresetToCanvas(preset)
                        parentDialog?.dismiss()
                    }
                    selectedOption.startsWith("Rename") -> {
                        showRenamePresetDialog(preset, parentDialog)
                    }
                    selectedOption.startsWith("Delete") -> {
                        showDeletePresetConfirmation(preset, parentDialog)
                    }
                }
            }
            .show()
    }

    private fun loadPresetToCanvas(preset: com.astral.typer.utils.SfxPreset) {
        SfxPresetManager.applyPresetToLayer(preset, sfxCanvasView.sfxLayer, this)
        etSfxText.setText(preset.text)
        updateLetterSpinner()
        sfxCanvasView.invalidate()
        Toast.makeText(this, "Preset '${preset.name}' loaded!", Toast.LENGTH_SHORT).show()
    }

    private fun showRenamePresetDialog(preset: com.astral.typer.utils.SfxPreset, parentDialog: AlertDialog?) {
        val etName = EditText(this).apply {
            setText(preset.name)
            setSelectAllOnFocus(true)
        }

        AlertDialog.Builder(this)
            .setTitle("Rename Preset")
            .setView(etName)
            .setPositiveButton("Save") { _, _ ->
                val newName = etName.text.toString().trim()
                if (newName.isNotEmpty()) {
                    SfxPresetManager.renamePreset(this, preset.id, newName)
                    Toast.makeText(this, "Preset renamed!", Toast.LENGTH_SHORT).show()
                    parentDialog?.dismiss()
                    showSavedPresetsDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeletePresetConfirmation(preset: com.astral.typer.utils.SfxPreset, parentDialog: AlertDialog?) {
        AlertDialog.Builder(this)
            .setTitle("Delete Preset")
            .setMessage("Are you sure you want to delete preset '${preset.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                SfxPresetManager.deletePreset(this, preset.id)
                Toast.makeText(this, "Preset deleted!", Toast.LENGTH_SHORT).show()
                parentDialog?.dismiss()
                showSavedPresetsDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSavePresetDialog() {
        val etName = EditText(this).apply {
            setText("SFX " + sfxCanvasView.sfxLayer.text.toString())
            setSelectAllOnFocus(true)
            hint = "Preset Name"
        }

        AlertDialog.Builder(this)
            .setTitle("Save SFX Preset")
            .setMessage("Enter a name for this SFX preset:")
            .setView(etName)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isNotEmpty()) {
                    val preset = SfxPresetManager.createPresetFromLayer(sfxCanvasView.sfxLayer, name)
                    SfxPresetManager.addCustomPreset(this, preset)
                    Toast.makeText(this, "SFX Preset '$name' saved!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
