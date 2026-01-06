package com.astral.typer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.astral.typer.databinding.ActivityEditorBinding
import com.astral.typer.models.Layer
import com.astral.typer.models.TextLayer
import com.astral.typer.utils.CustomTypefaceSpan
import com.astral.typer.utils.FontManager
import com.astral.typer.views.AstralCanvasView

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var canvasView: AstralCanvasView

    private var activeEditText: EditText? = null
    private val MENU_HEIGHT_DP = 380

    private val importFontLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            if (FontManager.importFont(this, it)) {
                Toast.makeText(this, "Font Imported!", Toast.LENGTH_SHORT).show()
                // Refresh logic if needed
            } else {
                Toast.makeText(this, "Import Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
                    // Open Format menu and focus input
                    showFormatMenu()
                    activeEditText?.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(activeEditText, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }

    private fun setupBottomMenu() {
        // Insert Actions
        binding.btnInsertText.setOnClickListener {
            canvasView.addTextLayer("Double Tap to Edit")
        }

        binding.btnInsertImage.setOnClickListener {
            Toast.makeText(this, "Image Import Not Implemented", Toast.LENGTH_SHORT).show()
        }

        // Property Actions
        binding.btnPropFont.setOnClickListener { showFontPicker() }
        binding.btnPropColor.setOnClickListener { showColorPicker() }
        binding.btnPropFormat.setOnClickListener { showFormatMenu() }
        binding.btnPropShadow.setOnClickListener { showShadowControls() }
        binding.btnPropGradation.setOnClickListener { showGradationControls() }

        // Top Bar
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { saveImage() }
    }

    private fun saveImage() {
        val bitmap = canvasView.renderToBitmap()
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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun prepareContainer(): LinearLayout {
        binding.propertyDetailContainer.visibility = View.VISIBLE
        binding.propertyDetailContainer.removeAllViews()

        // Enforce Uniform Height
        val params = binding.propertyDetailContainer.layoutParams
        params.height = dpToPx(MENU_HEIGHT_DP)
        binding.propertyDetailContainer.layoutParams = params

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        binding.propertyDetailContainer.addView(container)
        return container
    }

    private fun hidePropertyDetail() {
        binding.propertyDetailContainer.visibility = View.GONE
        activeEditText = null
    }

    // --- Shared Input Field ---
    private fun createInputView(layer: TextLayer): View {
        val inputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.DKGRAY)
        }

        val editText = EditText(this).apply {
            setTextColor(Color.WHITE)
            setText(layer.text) // Copies spans
            setHint("Edit Text...")
            setHintTextColor(Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (s != null) {
                        layer.text = SpannableStringBuilder(s)
                        canvasView.invalidate()
                    }
                }
            })
        }
        activeEditText = editText
        inputContainer.addView(editText)
        return inputContainer
    }

    private fun applySpanToSelection(span: Any) {
        val et = activeEditText ?: return
        val start = et.selectionStart
        val end = et.selectionEnd

        if (start != -1 && end != -1 && start != end) {
            et.editableText.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            // Trigger update in TextLayer via TextWatcher
            // But TextWatcher fires after change. setSpan does not always trigger onTextChanged unless text changes?
            // setSpan DOES trigger span change events, but TextWatcher monitors text content characters usually.
            // Let's force update
            val layer = canvasView.getSelectedLayer() as? TextLayer
            if (layer != null) {
                layer.text = SpannableStringBuilder(et.editableText)
                canvasView.invalidate()
            }
        } else {
             // If no selection, maybe apply global? Or toggle for next typing?
             // For now, only selection.
        }
    }

    // --- FONT MENU ---
    private fun showFontPicker() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() as? TextLayer ?: return

        container.addView(createInputView(layer))

        val scroll = HorizontalScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }

        // Horizontal Compact List
        val allFonts = FontManager.getStandardFonts(this) + FontManager.getCustomFonts(this)

        for (font in allFonts) {
            val item = TextView(this).apply {
                text = font.name
                textSize = 16f
                setTextColor(Color.WHITE)
                typeface = font.typeface
                setPadding(20, 20, 20, 20)
                setBackgroundResource(android.R.drawable.btn_default_small) // Or custom bg

                setOnClickListener {
                    // Check selection
                    val et = activeEditText
                    if (et != null && et.selectionStart != et.selectionEnd) {
                        applySpanToSelection(CustomTypefaceSpan(font.typeface))
                    } else {
                        // Global Apply
                        layer.typeface = font.typeface
                        canvasView.invalidate()
                    }
                }
            }
            list.addView(item)

            // Spacer
            val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) }
            list.addView(spacer)
        }

        scroll.addView(list)
        container.addView(scroll)
    }

    // --- COLOR MENU ---
    private fun showColorPicker() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() as? TextLayer ?: return

        container.addView(createInputView(layer))

        val scroll = HorizontalScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }

        val colors = listOf(
            Color.BLACK, Color.WHITE, Color.RED, Color.GREEN, Color.BLUE,
            Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.GRAY, Color.DKGRAY
        )

        for (color in colors) {
            val item = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(80, 80).apply {
                    setMargins(8, 0, 8, 0)
                }
                setBackgroundColor(color)
                setOnClickListener {
                    val et = activeEditText
                    if (et != null && et.selectionStart != et.selectionEnd) {
                        applySpanToSelection(ForegroundColorSpan(color))
                    } else {
                        layer.color = color
                        canvasView.invalidate()
                    }
                }
            }
            list.addView(item)
        }

        scroll.addView(list)
        container.addView(scroll)
    }

    // --- FORMAT MENU ---
    private fun showFormatMenu() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() as? TextLayer ?: return

        container.addView(createInputView(layer))

        // Tabs
        val tabsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Content Views
        val formattingView = createFormattingTab(layer)
        val sizeView = createSizeTab(layer)

        fun selectTab(isFormatting: Boolean) {
            contentContainer.removeAllViews()
            contentContainer.addView(if (isFormatting) formattingView else sizeView)
        }

        val btnFormat = TextView(this).apply {
            text = "Formatting"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(16, 24, 16, 24)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { selectTab(true) }
        }

        val btnSize = TextView(this).apply {
            text = "Size"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(16, 24, 16, 24)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { selectTab(false) }
        }

        tabsLayout.addView(btnFormat)
        tabsLayout.addView(btnSize)
        container.addView(tabsLayout)
        container.addView(contentContainer)

        selectTab(true) // Default
    }

    private fun createFormattingTab(layer: TextLayer): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // Styles Row
        val stylesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 16)
        }

        fun addStyleBtn(label: String, spanProvider: () -> Any) {
            val btn = TextView(this).apply {
                text = label
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setBackgroundResource(android.R.drawable.btn_default_small)
                setPadding(24, 16, 24, 16)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(8, 0, 8, 0)
                }
                setOnClickListener {
                    applySpanToSelection(spanProvider())
                }
            }
            stylesRow.addView(btn)
        }

        addStyleBtn("B") { StyleSpan(Typeface.BOLD) }
        addStyleBtn("I") { StyleSpan(Typeface.ITALIC) }
        addStyleBtn("U") { UnderlineSpan() }
        addStyleBtn("S") { StrikethroughSpan() }

        layout.addView(stylesRow)

        // Alignment Row
        val alignRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        fun addAlignBtn(label: String, align: Layout.Alignment) {
            val btn = TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setBackgroundResource(android.R.drawable.btn_default_small)
                setPadding(24, 16, 24, 16)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(8, 0, 8, 0)
                }
                setOnClickListener {
                    // Alignment is usually paragraph level, but can be span (AlignmentSpan)
                    // Or layer level. Prompt says "rata kiri..." in format tab.
                    // Let's do Layer Level for simplicity as TextLayer supports it now
                    layer.textAlign = align
                    canvasView.invalidate()
                }
            }
            alignRow.addView(btn)
        }

        addAlignBtn("Left", Layout.Alignment.ALIGN_NORMAL)
        addAlignBtn("Center", Layout.Alignment.ALIGN_CENTER)
        addAlignBtn("Right", Layout.Alignment.ALIGN_OPPOSITE)
        // Justify isn't standard in Layout.Alignment for StaticLayout until O/P, simple fallback
        // AlignmentSpan.Standard only supports normal, center, opposite.

        layout.addView(alignRow)

        return layout
    }

    private fun createSizeTab(layer: TextLayer): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        fun createControl(label: String, valueStr: String, onMinus: () -> Unit, onPlus: () -> Unit): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val tvLabel = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnMinus = TextView(this).apply {
                text = "-"
                textSize = 20f
                setTextColor(Color.WHITE)
                setPadding(20, 10, 20, 10)
                setOnClickListener { onMinus() }
            }

            val tvValue = TextView(this).apply {
                text = valueStr
                setTextColor(Color.CYAN)
                setPadding(20, 0, 20, 0)
            }

            val btnPlus = TextView(this).apply {
                text = "+"
                textSize = 20f
                setTextColor(Color.WHITE)
                setPadding(20, 10, 20, 10)
                setOnClickListener { onPlus() }
            }

            row.addView(tvLabel)
            row.addView(btnMinus)
            row.addView(tvValue)
            row.addView(btnPlus)
            return row
        }

        // Text Size
        val textSizeRow = createControl("Text Size", "${layer.fontSize.toInt()} pt",
            onMinus = {
                layer.fontSize = (layer.fontSize - 2).coerceAtLeast(10f)
                canvasView.invalidate()
                // Refresh View? A bit hacky, but we can recreate
                (layout.getChildAt(0) as LinearLayout).getChildAt(2).let { (it as TextView).text = "${layer.fontSize.toInt()} pt" }
            },
            onPlus = {
                layer.fontSize += 2
                canvasView.invalidate()
                (layout.getChildAt(0) as LinearLayout).getChildAt(2).let { (it as TextView).text = "${layer.fontSize.toInt()} pt" }
            }
        )
        layout.addView(textSizeRow)

        // Box Area Size (Scale)
        // Average scale
        val scaleRow = createControl("Box Scale", "${(layer.scale * 100).toInt()}%",
            onMinus = {
                val s = (layer.scale - 0.1f).coerceAtLeast(0.1f)
                layer.scaleX = s
                layer.scaleY = s
                canvasView.invalidate()
                (layout.getChildAt(1) as LinearLayout).getChildAt(2).let { (it as TextView).text = "${(layer.scale * 100).toInt()}%" }
            },
            onPlus = {
                val s = layer.scale + 0.1f
                layer.scaleX = s
                layer.scaleY = s
                canvasView.invalidate()
                (layout.getChildAt(1) as LinearLayout).getChildAt(2).let { (it as TextView).text = "${(layer.scale * 100).toInt()}%" }
            }
        )
        layout.addView(scaleRow)

        // Box Width
        val widthVal = layer.boxWidth ?: 0f
        val widthStr = if (widthVal <= 0) "Auto" else "${widthVal.toInt()} pt"
        val widthRow = createControl("Box Width", widthStr,
            onMinus = {
                val w = (layer.boxWidth ?: layer.getWidth()) - 20f
                layer.boxWidth = w.coerceAtLeast(50f)
                canvasView.invalidate()
                (layout.getChildAt(2) as LinearLayout).getChildAt(2).let { (it as TextView).text = "${layer.boxWidth!!.toInt()} pt" }
            },
            onPlus = {
                 val w = (layer.boxWidth ?: layer.getWidth()) + 20f
                layer.boxWidth = w
                canvasView.invalidate()
                (layout.getChildAt(2) as LinearLayout).getChildAt(2).let { (it as TextView).text = "${layer.boxWidth!!.toInt()} pt" }
            }
        )
        layout.addView(widthRow)

        return layout
    }

    private fun showShadowControls() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() as? TextLayer ?: return

        container.addView(TextView(this).apply {
            text = "Shadow Controls"; setTextColor(Color.WHITE); setPadding(16,16,16,16)
        })

        fun createSlider(label: String, initial: Int, max: Int, onChange: (Int) -> Unit): View {
            val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val tv = TextView(this).apply { text = label; setTextColor(Color.WHITE) }
            val sb = SeekBar(this).apply {
                this.max = max
                progress = initial
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { onChange(p) }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            wrap.addView(tv)
            wrap.addView(sb)
            return wrap
        }

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,16,16,16) }

        list.addView(createSlider("Radius", layer.shadowRadius.toInt(), 50) {
            layer.shadowRadius = it.toFloat(); canvasView.invalidate()
        })
        list.addView(createSlider("DX", (layer.shadowDx + 50).toInt(), 100) {
            layer.shadowDx = (it - 50).toFloat(); canvasView.invalidate()
        })
        list.addView(createSlider("DY", (layer.shadowDy + 50).toInt(), 100) {
            layer.shadowDy = (it - 50).toFloat(); canvasView.invalidate()
        })

        scroll.addView(list)
        container.addView(scroll)
    }

    private fun showGradationControls() {
        // Keeping simple placeholder or previous implementation for now as not requested to change
         val container = prepareContainer()
         container.addView(TextView(this).apply {
             text = "Gradient Controls (Basic)"; setTextColor(Color.WHITE); setPadding(16,16,16,16)
         })
         // ... Similar logic to previous implementation if needed
    }
}
