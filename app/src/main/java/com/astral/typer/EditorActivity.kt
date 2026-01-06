package com.astral.typer

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.astral.typer.databinding.ActivityEditorBinding
import com.astral.typer.models.Layer
import com.astral.typer.models.TextLayer
import com.astral.typer.views.AstralCanvasView
import com.astral.typer.utils.FontManager
import androidx.activity.result.contract.ActivityResultContracts

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var canvasView: AstralCanvasView

    private val importFontLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            if (FontManager.importFont(this, it)) {
                Toast.makeText(this, "Font Imported!", Toast.LENGTH_SHORT).show()
                // Refresh if font picker is open?
                if (isFontPickerVisible) showFontPicker() // Reload
            } else {
                Toast.makeText(this, "Import Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var isFontPickerVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup Canvas
        val width = intent.getIntExtra("CANVAS_WIDTH", 1080)
        val height = intent.getIntExtra("CANVAS_HEIGHT", 1080)
        val color = intent.getIntExtra("CANVAS_COLOR", -1)
        val imageUriString = intent.getStringExtra("IMAGE_URI")

        canvasView = AstralCanvasView(this)
        binding.canvasContainer.addView(canvasView)
        canvasView.initCanvas(width, height, if (color == -1) Color.WHITE else color)

        if (imageUriString != null) {
            try {
                val uri = android.net.Uri.parse(imageUriString)
                val inputStream = contentResolver.openInputStream(uri)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                canvasView.setBackgroundImage(bitmap)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }

        // Listeners
        setupCanvasListeners()
        setupBottomMenu()
    }

    private fun setupCanvasListeners() {
        canvasView.onLayerSelectedListener = object : AstralCanvasView.OnLayerSelectedListener {
            override fun onLayerSelected(layer: Layer?) {
                if (layer != null) {
                    showPropertiesMenu()
                } else {
                    showInsertMenu()
                    hidePropertyDetail()
                }
            }
        }

        canvasView.onLayerEditListener = object : AstralCanvasView.OnLayerEditListener {
            override fun onLayerDoubleTap(layer: Layer) {
                if (layer is TextLayer) {
                    showEditTextDialog(layer)
                }
            }
        }
    }

    private fun showEditTextDialog(layer: TextLayer) {
        val editText = EditText(this)
        editText.setText(layer.text)

        AlertDialog.Builder(this)
            .setTitle("Edit Text")
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                layer.text = editText.text.toString()
                canvasView.invalidate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupBottomMenu() {
        // Insert Actions
        binding.btnInsertText.setOnClickListener {
            canvasView.addTextLayer("New Text")
        }

        binding.btnInsertImage.setOnClickListener {
            Toast.makeText(this, "Image Import Not Implemented", Toast.LENGTH_SHORT).show()
        }

        // Property Actions
        binding.btnPropFont.setOnClickListener {
             showFontPicker()
        }
        binding.btnPropColor.setOnClickListener {
             showColorPicker()
        }
        binding.btnPropFormat.setOnClickListener {
             showPropertyDetail("Format")
        }
        binding.btnPropShadow.setOnClickListener {
             showShadowControls()
        }
        binding.btnPropGradation.setOnClickListener {
             showGradationControls()
        }

        // Top Bar
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { saveImage() }
    }

    private fun saveImage() {
        val bitmap = canvasView.renderToBitmap()

        // Save to MediaStore (Basic implementation)
        val filename = "AstralTyper_${System.currentTimeMillis()}.png"
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
        }

        val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            contentResolver.openOutputStream(uri).use { stream ->
                if (stream != null) {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                }
            }
            Toast.makeText(this, "Image Saved!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Failed to save", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showInsertMenu() {
        binding.menuInsert.visibility = View.VISIBLE
        binding.menuProperties.visibility = View.GONE
    }

    private fun showPropertiesMenu() {
        binding.menuInsert.visibility = View.GONE
        binding.menuProperties.visibility = View.VISIBLE
    }

    private fun showPropertyDetail(type: String) {
        binding.propertyDetailContainer.visibility = View.VISIBLE
        binding.propertyDetailContainer.removeAllViews()

        val textView = android.widget.TextView(this).apply {
             text = "Adjust $type (Not Implemented)"
             setTextColor(Color.WHITE)
             gravity = android.view.Gravity.CENTER
             layoutParams = android.widget.FrameLayout.LayoutParams(
                 android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                 300 // Height
             )
        }
        binding.propertyDetailContainer.addView(textView)
    }

    private fun hidePropertyDetail() {
        binding.propertyDetailContainer.visibility = View.GONE
    }

    private fun showFontPicker() {
        isFontPickerVisible = true
        binding.propertyDetailContainer.visibility = View.VISIBLE
        binding.propertyDetailContainer.removeAllViews()

        // Tab Layout Container
        val mainContainer = android.widget.LinearLayout(this).apply {
             orientation = android.widget.LinearLayout.VERTICAL
             layoutParams = android.widget.FrameLayout.LayoutParams(
                 android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                 android.view.ViewGroup.LayoutParams.MATCH_PARENT
             )
        }

        // Tabs
        val tabsLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            weightSum = 3f
        }

        var currentTab = "Standard"
        val contentContainer = android.widget.FrameLayout(this)

        fun loadFonts(type: String) {
            currentTab = type
            contentContainer.removeAllViews()

            val listLayout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            val scrollView = android.widget.ScrollView(this)

            // Add Import Button for My Font
            if (type == "My Font") {
                val btnImport = android.widget.Button(this).apply {
                    text = "+ Import Font (Zip/TTF/OTF)"
                    setOnClickListener { importFontLauncher.launch("*/*") }
                }
                listLayout.addView(btnImport)
            }

            val fonts = when(type) {
                "Standard" -> FontManager.getStandardFonts(this)
                "My Font" -> FontManager.getCustomFonts(this)
                "Favorite" -> FontManager.getFavoriteFonts(this)
                else -> emptyList()
            }

            if (fonts.isEmpty() && type != "Standard") {
                 val empty = android.widget.TextView(this).apply {
                     text = "No fonts here."
                     setTextColor(Color.WHITE)
                     setPadding(20, 20, 20, 20)
                 }
                 listLayout.addView(empty)
            }

            for (font in fonts) {
                val itemLayout = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(16, 16, 16, 16)
                    setBackgroundColor(Color.DKGRAY) // Separator
                    val params = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    params.setMargins(0, 2, 0, 2)
                    layoutParams = params
                }

                // Font Preview
                val tv = android.widget.TextView(this).apply {
                    text = font.name
                    typeface = font.typeface
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                    setOnClickListener {
                        val layer = canvasView.getSelectedLayer()
                        if (layer is TextLayer) {
                            layer.typeface = font.typeface
                            canvasView.invalidate()
                        }
                    }
                }

                // Star
                val btnStar = android.widget.Button(this).apply {
                    text = if (font.isFavorite) "★" else "☆"
                    setTextColor(Color.YELLOW)
                    textSize = 20f
                    setBackgroundColor(Color.TRANSPARENT)
                    setOnClickListener {
                        FontManager.toggleFavorite(this@EditorActivity, font)
                        text = if (font.isFavorite) "★" else "☆"
                        if (currentTab == "Favorite" && !font.isFavorite) {
                            loadFonts("Favorite") // Refresh
                        }
                    }
                }

                itemLayout.addView(tv)
                itemLayout.addView(btnStar)
                listLayout.addView(itemLayout)
            }

            scrollView.addView(listLayout)
            contentContainer.addView(scrollView)
        }

        val tabNames = listOf("Standard", "My Font", "Favorite")
        for (name in tabNames) {
            val btn = android.widget.Button(this).apply {
                text = name
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { loadFonts(name) }
            }
            tabsLayout.addView(btn)
        }

        mainContainer.addView(tabsLayout)
        mainContainer.addView(contentContainer)

        binding.propertyDetailContainer.addView(mainContainer)

        // Initial load
        loadFonts("Standard")
    }

    private fun showColorPicker() {
        binding.propertyDetailContainer.visibility = View.VISIBLE
        binding.propertyDetailContainer.removeAllViews()

        val scrollView = android.widget.HorizontalScrollView(this)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }

        val colors = listOf(
            Color.BLACK, Color.WHITE, Color.RED, Color.GREEN, Color.BLUE,
            Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.GRAY, Color.DKGRAY
        )

        for (color in colors) {
            val btn = android.view.View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(80, 80).apply {
                    setMargins(8, 0, 8, 0)
                }
                setBackgroundColor(color)
                setOnClickListener {
                    val layer = canvasView.getSelectedLayer()
                    if (layer is TextLayer) {
                        layer.color = color
                        canvasView.invalidate()
                    }
                }
            }
            layout.addView(btn)
        }

        scrollView.addView(layout)
        binding.propertyDetailContainer.addView(scrollView)
    }

    private fun showGradationControls() {
        binding.propertyDetailContainer.visibility = View.VISIBLE
        binding.propertyDetailContainer.removeAllViews()

        val layer = canvasView.getSelectedLayer() as? TextLayer
        if (layer == null) return

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // Toggle Switch
        val switch = android.widget.Switch(this).apply {
            text = "Enable Gradient"
            setTextColor(Color.WHITE)
            isChecked = layer.isGradient
            setOnCheckedChangeListener { _, isChecked ->
                layer.isGradient = isChecked
                canvasView.invalidate()
            }
        }
        layout.addView(switch)

        // Helper to add color list
        fun addColorList(label: String, onColorSelect: (Int) -> Unit) {
            val tv = android.widget.TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setPadding(0, 16, 0, 8)
            }
            layout.addView(tv)

            val scrollView = android.widget.HorizontalScrollView(this)
            val colorsLayout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }

            val colors = listOf(Color.BLACK, Color.WHITE, Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA)
            for (color in colors) {
                val btn = android.view.View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(60, 60).apply {
                        setMargins(8, 0, 8, 0)
                    }
                    setBackgroundColor(color)
                    setOnClickListener {
                        onColorSelect(color)
                        layer.isGradient = true
                        switch.isChecked = true
                        canvasView.invalidate()
                    }
                }
                colorsLayout.addView(btn)
            }
            scrollView.addView(colorsLayout)
            layout.addView(scrollView)
        }

        addColorList("Start Color") { color -> layer.gradientStartColor = color }
        addColorList("End Color") { color -> layer.gradientEndColor = color }

        binding.propertyDetailContainer.addView(layout)
    }

    private fun showShadowControls() {
        binding.propertyDetailContainer.visibility = View.VISIBLE
        binding.propertyDetailContainer.removeAllViews()

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val layer = canvasView.getSelectedLayer() as? TextLayer
        if (layer == null) return

        // Radius Slider
        layout.addView(createSlider("Radius", layer.shadowRadius.toInt(), 50) { value ->
            layer.shadowRadius = value.toFloat()
            canvasView.invalidate()
        })

        // DX Slider (offset by 50 to handle negative)
        layout.addView(createSlider("DX", (layer.shadowDx + 50).toInt(), 100) { value ->
            layer.shadowDx = (value - 50).toFloat()
            canvasView.invalidate()
        })

        // DY Slider
        layout.addView(createSlider("DY", (layer.shadowDy + 50).toInt(), 100) { value ->
            layer.shadowDy = (value - 50).toFloat()
            canvasView.invalidate()
        })

        // Shadow Color Picker (Mini)
        val colorsLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }
        val colors = listOf(Color.BLACK, Color.GRAY, Color.WHITE, Color.RED)
        for (color in colors) {
             val btn = android.view.View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(60, 60).apply {
                    setMargins(8, 0, 8, 0)
                }
                setBackgroundColor(color)
                setOnClickListener {
                    layer.shadowColor = color
                    canvasView.invalidate()
                }
            }
            colorsLayout.addView(btn)
        }
        layout.addView(colorsLayout)

        binding.propertyDetailContainer.addView(layout)
    }

    private fun createSlider(label: String, initial: Int, max: Int, onChange: (Int) -> Unit): View {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val tv = android.widget.TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
        }
        container.addView(tv)

        val seekBar = android.widget.SeekBar(this).apply {
            this.max = max
            progress = initial
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    onChange(progress)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        container.addView(seekBar)

        return container
    }
}
