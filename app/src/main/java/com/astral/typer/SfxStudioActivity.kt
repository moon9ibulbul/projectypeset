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
    private lateinit var rbPanZoomMode: RadioButton
    private lateinit var btnResetAllPoints: Button
    private lateinit var etSfxText: EditText
    private lateinit var spinnerLetterSelect: Spinner
    private lateinit var btnAddVectorPoints: Button
    private lateinit var btnSelectFont: Button
    private lateinit var btnChangeColor: Button
    private lateinit var btnChangeStroke: Button
    private lateinit var btnChangeSpike: Button

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
        rbPanZoomMode = findViewById(R.id.rbPanZoomMode)
        btnResetAllPoints = findViewById(R.id.btnResetAllPoints)
        etSfxText = findViewById(R.id.etSfxText)
        spinnerLetterSelect = findViewById(R.id.spinnerLetterSelect)
        btnAddVectorPoints = findViewById(R.id.btnAddVectorPoints)
        btnSelectFont = findViewById(R.id.btnSelectFont)
        btnChangeColor = findViewById(R.id.btnChangeColor)
        btnChangeStroke = findViewById(R.id.btnChangeStroke)
        btnChangeSpike = findViewById(R.id.btnChangeSpike)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btnSavePreset).setOnClickListener {
            showSavePresetDialog()
        }

        // Mode Radio Group
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbVectorMode) {
                sfxCanvasView.currentMode = SfxCanvasView.Mode.VECTOR_EDIT
                tvModeIndicator.text = "Mode: Edit Vector Points"
            } else {
                sfxCanvasView.currentMode = SfxCanvasView.Mode.PAN_ZOOM
                tvModeIndicator.text = "Mode: Pan & Zoom"
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

        btnSelectFont.setOnClickListener {
            showFontPickerDialog()
        }

        btnChangeColor.setOnClickListener {
            showColorPickerDialog()
        }

        btnChangeStroke.setOnClickListener {
            showStrokeDialog()
        }

        btnChangeSpike.setOnClickListener {
            showGlitchEffectDialog()
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
                sfxCanvasView.invalidate()
                Toast.makeText(this, "Font applied: ${fontItem.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showColorPickerDialog() {
        ColorPickerHelper.showColorPickerDialog(this, sfxCanvasView.sfxLayer.color) { newColor ->
            sfxCanvasView.sfxLayer.color = newColor
            sfxCanvasView.invalidate()
        }
    }

    private fun showStrokeDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_stroke_options, null, false)
        val sbStrokeWidth = dialogView.findViewById<SeekBar>(R.id.sbStrokeWidth)
        val sbDoubleWidth = dialogView.findViewById<SeekBar>(R.id.sbDoubleWidth)
        val btnStrokeColor = dialogView.findViewById<Button>(R.id.btnStrokeColor)
        val btnDoubleColor = dialogView.findViewById<Button>(R.id.btnDoubleColor)

        sbStrokeWidth?.progress = sfxCanvasView.sfxLayer.strokeWidth.toInt()
        sbDoubleWidth?.progress = sfxCanvasView.sfxLayer.doubleStrokeWidth.toInt()

        btnStrokeColor?.setOnClickListener {
            ColorPickerHelper.showColorPickerDialog(this, sfxCanvasView.sfxLayer.strokeColor) { c ->
                sfxCanvasView.sfxLayer.strokeColor = c
                sfxCanvasView.invalidate()
            }
        }

        btnDoubleColor?.setOnClickListener {
            ColorPickerHelper.showColorPickerDialog(this, sfxCanvasView.sfxLayer.doubleStrokeColor) { c ->
                sfxCanvasView.sfxLayer.doubleStrokeColor = c
                sfxCanvasView.invalidate()
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Stroke Options")
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                if (sbStrokeWidth != null) sfxCanvasView.sfxLayer.strokeWidth = sbStrokeWidth.progress.toFloat()
                if (sbDoubleWidth != null) sfxCanvasView.sfxLayer.doubleStrokeWidth = sbDoubleWidth.progress.toFloat()
                sfxCanvasView.invalidate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGlitchEffectDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }

        val tvLabel = TextView(this).apply {
            text = "Glitch Intensity: ${sfxCanvasView.sfxLayer.glitchAmount.toInt()}"
            setTextColor(ThemeUtils.getColorFromAttr(this@SfxStudioActivity, R.attr.appTextColorPrimary))
        }

        val sbGlitch = SeekBar(this).apply {
            max = 50
            progress = sfxCanvasView.sfxLayer.glitchAmount.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    tvLabel.text = "Glitch Intensity: $progress"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        layout.addView(tvLabel)
        layout.addView(sbGlitch)

        AlertDialog.Builder(this)
            .setTitle("Glitch Effect")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                val amount = sbGlitch.progress.toFloat()
                if (amount > 0f) {
                    sfxCanvasView.sfxLayer.currentEffect = TextEffectType.GLITCH
                    sfxCanvasView.sfxLayer.glitchAmount = amount
                } else {
                    sfxCanvasView.sfxLayer.currentEffect = TextEffectType.NONE
                    sfxCanvasView.sfxLayer.glitchAmount = 0f
                }
                sfxCanvasView.invalidate()
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
