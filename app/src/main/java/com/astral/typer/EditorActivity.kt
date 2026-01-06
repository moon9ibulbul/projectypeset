package com.astral.typer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    private val MENU_HEIGHT_DP = 180
    private var currentMenuType: String? = null

    private val importFontLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            if (FontManager.importFont(this, it)) {
                Toast.makeText(this, "Font Imported!", Toast.LENGTH_SHORT).show()
                if (isFontPickerVisible) showFontPicker()
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
                    // Open Quick Edit menu and focus input
                    showQuickEditMenu()
                    currentMenuType = "QUICK_EDIT"
                    // Delay focus slightly to ensure view is attached
                    binding.root.postDelayed({
                        activeEditText?.requestFocus()
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(activeEditText, InputMethodManager.SHOW_IMPLICIT)
                    }, 300)
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
        binding.btnPropQuickEdit.setOnClickListener { toggleMenu("QUICK_EDIT") { showQuickEditMenu() } }
        binding.btnPropFont.setOnClickListener { toggleMenu("FONT") { showFontPicker() } }
        binding.btnPropColor.setOnClickListener { toggleMenu("COLOR") { showColorPicker() } }
        binding.btnPropFormat.setOnClickListener { toggleMenu("FORMAT") { showFormatMenu() } }
        binding.btnPropStroke.setOnClickListener { toggleMenu("STROKE") { showStrokeMenu() } }
        binding.btnPropDoubleStroke.setOnClickListener { toggleMenu("DOUBLE_STROKE") { showDoubleStrokeMenu() } }
        binding.btnPropShadow.setOnClickListener { toggleMenu("SHADOW") { showShadowControls() } }
        binding.btnPropGradation.setOnClickListener { toggleMenu("GRADATION") { showGradationControls() } }

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

        // Enforce Uniform Reduced Height
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
        isFontPickerVisible = false
        currentMenuType = null
    }

    private fun toggleMenu(type: String, showAction: () -> Unit) {
        if (currentMenuType == type && binding.propertyDetailContainer.visibility == View.VISIBLE) {
            hidePropertyDetail()
        } else {
            showAction()
            currentMenuType = type
        }
    }

    // --- Shared Input Field ---
    private fun createInputView(layer: TextLayer, isEditable: Boolean): View {
        val inputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#333333"))
                cornerRadius = dpToPx(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 8, 16, 8)
            }
        }

        val editText = EditText(this).apply {
            setTextColor(Color.WHITE)
            setText(layer.text) // Copies spans
            textSize = 14f
            setHint("Type here...")
            setHintTextColor(Color.GRAY)
            background = null // Remove default underline
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            if (!isEditable) {
                inputType = 0
                setTextIsSelectable(true)
                keyListener = null
            }

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
            val layer = canvasView.getSelectedLayer() as? TextLayer
            if (layer != null) {
                layer.text = SpannableStringBuilder(et.editableText)
                canvasView.invalidate()
            }
        } else {
             // If no selection, apply to entire text (Logic moved here or handled in listener)
             // But wait, createFormattingTab uses this.
             // If I change it here, it works for all calls.
             val layer = canvasView.getSelectedLayer() as? TextLayer
             if (layer != null) {
                  // If no selection in EditText, but we want to apply style to layer?
                  // The EditText has the content.
                  if (start == end) {
                       et.editableText.setSpan(span, 0, et.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                       layer.text = SpannableStringBuilder(et.editableText)
                       canvasView.invalidate()
                  }
             }
        }
    }

    // --- QUICK EDIT MENU ---
    private fun showQuickEditMenu() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() as? TextLayer ?: return

        container.addView(createInputView(layer, true))

        val alignRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            weightSum = 4f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 16, 0, 0)
        }

        fun addAlignBtn(iconRes: Int, align: Layout.Alignment) {
            val btn = android.widget.ImageView(this).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
                setPadding(0, 16, 0, 16)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )

                setOnClickListener {
                    layer.textAlign = align
                    layer.isJustified = false
                    canvasView.invalidate()
                }
            }
            alignRow.addView(btn)
        }

        addAlignBtn(R.drawable.ic_format_align_left, Layout.Alignment.ALIGN_NORMAL)
        addAlignBtn(R.drawable.ic_format_align_center, Layout.Alignment.ALIGN_CENTER)
        addAlignBtn(R.drawable.ic_format_align_right, Layout.Alignment.ALIGN_OPPOSITE)

        val btnJustify = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_format_align_justify)
            setColorFilter(Color.WHITE)
            setPadding(0, 16, 0, 16)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )

            setOnClickListener {
                layer.textAlign = Layout.Alignment.ALIGN_NORMAL
                layer.isJustified = true
                canvasView.invalidate()
            }
        }
        alignRow.addView(btnJustify)

        container.addView(alignRow)
    }

    // --- FONT MENU ---
    private fun showFontPicker() {
        isFontPickerVisible = true
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() as? TextLayer ?: return

        container.addView(createInputView(layer, false))

        // Tabs
        val tabsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
        }

        val contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Tab Logic
        fun loadTab(type: String) {
            contentContainer.removeAllViews()

            // Horizontal List Container
            val scroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val list = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 0, 16, 0)
                gravity = Gravity.CENTER_VERTICAL
            }

            if (type == "My Font") {
                val btnImport = TextView(this).apply {
                    text = "+ Import"
                    setTextColor(Color.WHITE)
                    setPadding(24, 16, 24, 16)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(Color.DKGRAY)
                        cornerRadius = dpToPx(8).toFloat()
                    }
                    setOnClickListener { importFontLauncher.launch("*/*") }
                }
                list.addView(btnImport)
            }

            val fonts = when(type) {
                "Standard" -> FontManager.getStandardFonts(this)
                "My Font" -> FontManager.getCustomFonts(this)
                "Favorite" -> FontManager.getFavoriteFonts(this)
                else -> emptyList()
            }

            for (font in fonts) {
                val itemLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12, 12, 12, 12)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        dpToPx(100),
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(4, 0, 4, 0) }

                    background = GradientDrawable().apply {
                        setColor(Color.DKGRAY)
                        cornerRadius = dpToPx(8).toFloat()
                    }

                    setOnClickListener {
                        val et = activeEditText
                        if (et != null && et.selectionStart != et.selectionEnd) {
                            applySpanToSelection(CustomTypefaceSpan(font.typeface))
                        } else {
                            layer.typeface = font.typeface
                            canvasView.invalidate()
                        }
                    }
                }

                val tvName = TextView(this).apply {
                    text = font.name
                    typeface = font.typeface
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                val btnStar = TextView(this).apply {
                    text = if (font.isFavorite) "★" else "☆"
                    setTextColor(Color.YELLOW)
                    textSize = 18f
                    setPadding(0, 4, 0, 0)
                    setOnClickListener {
                        FontManager.toggleFavorite(this@EditorActivity, font)
                        text = if (font.isFavorite) "★" else "☆"
                        if (type == "Favorite" && !font.isFavorite) loadTab("Favorite")
                    }
                }

                itemLayout.addView(tvName)
                itemLayout.addView(btnStar)
                list.addView(itemLayout)
            }

            scroll.addView(list)
            contentContainer.addView(scroll)
        }

        val tabNames = listOf("Standard", "My Font", "Favorite")
        for (name in tabNames) {
            val btn = TextView(this).apply {
                text = name
                gravity = Gravity.CENTER
                setTextColor(Color.LTGRAY)
                textSize = 14f
                setPadding(12, 12, 12, 12)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    loadTab(name)
                    // Highlight selected tab visually (simplified)
                    alpha = 1.0f
                }
            }
            tabsLayout.addView(btn)
        }

        container.addView(tabsLayout)
        container.addView(contentContainer)

        loadTab("Standard")
    }

    // --- COLOR MENU ---
    private fun showColorPicker() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() as? TextLayer ?: return

        container.addView(createInputView(layer, false))

        val scroll = HorizontalScrollView(this).apply {
             isHorizontalScrollBarEnabled = false
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnEyedropper = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_menu_eyedropper)
            setColorFilter(Color.WHITE)
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                setColor(Color.DKGRAY)
                cornerRadius = dpToPx(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 16, 0)
            }
            setOnClickListener {
                 canvasView.setEyedropperMode(true)
                 canvasView.onColorPickedListener = { color ->
                      val et = activeEditText
                      if (et != null && et.selectionStart != et.selectionEnd) {
                            applySpanToSelection(ForegroundColorSpan(color))
                      } else {
                            layer.color = color
                            canvasView.invalidate()
                      }
                      Toast.makeText(context, "Color Picked", Toast.LENGTH_SHORT).show()
                 }
                 Toast.makeText(context, "Tap canvas to pick", Toast.LENGTH_SHORT).show()
            }
        }
        list.addView(btnEyedropper)

        val btnPalette = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_menu_palette)
            setColorFilter(Color.WHITE)
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                setColor(Color.DKGRAY)
                cornerRadius = dpToPx(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 16, 0)
            }
            setOnClickListener { showColorWheelDialog(layer) }
        }
        list.addView(btnPalette)

        val colors = listOf(
            Color.BLACK, Color.WHITE, Color.RED, Color.GREEN, Color.BLUE,
            Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.GRAY, Color.DKGRAY
        )

        for (color in colors) {
            val item = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40)).apply {
                    setMargins(8, 0, 8, 0)
                }
                background = GradientDrawable().apply {
                    setColor(color)
                    shape = GradientDrawable.OVAL
                    setStroke(2, Color.LTGRAY)
                }
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

        container.addView(createInputView(layer, false))

        // Tabs
        val tabsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
        }

        val contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

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
            textSize = 14f
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { selectTab(true) }
        }

        val btnSize = TextView(this).apply {
            text = "Size"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(16, 16, 16, 16)
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
            setPadding(16, 8, 16, 8)
        }

        // Styles Row
        val stylesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            weightSum = 4f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, 16)
        }

        fun addStyleBtn(label: String, spanProvider: () -> Any) {
            val btn = TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 16)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )

                setOnClickListener { applySpanToSelection(spanProvider()) }
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
            gravity = Gravity.CENTER
            weightSum = 4f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun addAlignBtn(iconRes: Int, align: Layout.Alignment) {
            val btn = android.widget.ImageView(this).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
                setPadding(0, 16, 0, 16)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )

                setOnClickListener {
                    layer.textAlign = align
                    layer.isJustified = false // Reset justification
                    canvasView.invalidate()
                }
            }
            alignRow.addView(btn)
        }

        addAlignBtn(R.drawable.ic_format_align_left, Layout.Alignment.ALIGN_NORMAL)
        addAlignBtn(R.drawable.ic_format_align_center, Layout.Alignment.ALIGN_CENTER)
        addAlignBtn(R.drawable.ic_format_align_right, Layout.Alignment.ALIGN_OPPOSITE)

        // Justify Button
        val btnJustify = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_format_align_justify)
            setColorFilter(Color.WHITE)
            setPadding(0, 16, 0, 16)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )

            setOnClickListener {
                layer.textAlign = Layout.Alignment.ALIGN_NORMAL
                layer.isJustified = true
                canvasView.invalidate()
            }
        }
        alignRow.addView(btnJustify)

        layout.addView(alignRow)
        return layout
    }

    private fun createSizeTab(layer: TextLayer): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }

        fun createControl(label: String, valueStr: String, onMinus: () -> Unit, onPlus: () -> Unit): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val tvLabel = TextView(this).apply {
                text = label
                setTextColor(Color.LTGRAY)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnMinus = TextView(this).apply {
                text = "-"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.DKGRAY)
                    cornerRadius = dpToPx(4).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(30))
                setOnClickListener { onMinus() }
            }

            val tvValue = TextView(this).apply {
                text = valueStr
                setTextColor(Color.CYAN)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dpToPx(80), ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val btnPlus = TextView(this).apply {
                text = "+"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.DKGRAY)
                    cornerRadius = dpToPx(4).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(30))
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
                layer.fontSize = (layer.fontSize - 1).coerceAtLeast(10f)
                canvasView.invalidate()
                (layout.getChildAt(0) as LinearLayout).getChildAt(2).let { (it as TextView).text = "${layer.fontSize.toInt()} pt" }
            },
            onPlus = {
                layer.fontSize += 1
                canvasView.invalidate()
                (layout.getChildAt(0) as LinearLayout).getChildAt(2).let { (it as TextView).text = "${layer.fontSize.toInt()} pt" }
            }
        )
        layout.addView(textSizeRow)

        // Box Scale
        val scaleRow = createControl("Box Scale", "${(layer.scale * 100).toInt()}%",
            onMinus = {
                val s = (layer.scale - 0.01f).coerceAtLeast(0.01f)
                layer.scaleX = s
                layer.scaleY = s
                canvasView.invalidate()
                (layout.getChildAt(1) as LinearLayout).getChildAt(2).let { (it as TextView).text = "${(layer.scale * 100).toInt()}%" }
            },
            onPlus = {
                val s = layer.scale + 0.01f
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
                val w = (layer.boxWidth ?: layer.getWidth()) - 1f
                layer.boxWidth = w.coerceAtLeast(50f)
                canvasView.invalidate()
                (layout.getChildAt(2) as LinearLayout).getChildAt(2).let { (it as TextView).text = "${layer.boxWidth!!.toInt()} pt" }
            },
            onPlus = {
                 val w = (layer.boxWidth ?: layer.getWidth()) + 1f
                layer.boxWidth = w
                canvasView.invalidate()
                (layout.getChildAt(2) as LinearLayout).getChildAt(2).let { (it as TextView).text = "${layer.boxWidth!!.toInt()} pt" }
            }
        )
        layout.addView(widthRow)

        return layout
    }

    private fun createSlider(label: String, initial: Int, max: Int, onChange: (Int) -> Unit): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0,8,0,8) }
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

    private fun createColorList(
        currentColor: Int,
        onColorPicked: (Int) -> Unit,
        onPaletteClick: () -> Unit
    ): View {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 8)
        }

        val btnPalette = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_menu_palette)
            setColorFilter(Color.WHITE)
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply { setColor(Color.DKGRAY); cornerRadius = dpToPx(8).toFloat() }
            setOnClickListener { onPaletteClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 16, 0)
            }
        }
        list.addView(btnPalette)

        val colors = listOf(Color.BLACK, Color.WHITE, Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.GRAY, Color.CYAN)
        for (color in colors) {
            val item = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40)).apply { setMargins(8, 0, 8, 0) }
                background = GradientDrawable().apply {
                    setColor(color)
                    shape = GradientDrawable.OVAL
                    setStroke(2, Color.LTGRAY)
                }
                setOnClickListener { onColorPicked(color) }
            }
            list.addView(item)
        }
        scroll.addView(list)
        return scroll
    }

    private fun showShadowControls() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() as? TextLayer ?: return

        // Tabs
        val tabsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
        }

        val contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Tab Views
        // 1. Drop Shadow
        val dropShadowView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            val layout = LinearLayout(this@EditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16,8,16,8)
            }
            // Color
            layout.addView(createColorList(layer.shadowColor,
                { c -> layer.shadowColor = c; canvasView.invalidate() },
                { showColorWheelDialogForProperty(layer) { c -> layer.shadowColor = c; canvasView.invalidate() } }
            ))

            layout.addView(createSlider("Blur Radius", layer.shadowRadius.toInt(), 50) {
                layer.shadowRadius = it.toFloat(); canvasView.invalidate()
            })
            // Optional: User might want DX/DY controls for normal shadow too.
            // But per request "Drop Shadow... principle same as Shadow now".
            // The previous menu had DX/DY. I should probably keep them.
            layout.addView(createSlider("DX", (layer.shadowDx + 50).toInt(), 100) {
                layer.shadowDx = (it - 50).toFloat(); canvasView.invalidate()
            })
            layout.addView(createSlider("DY", (layer.shadowDy + 50).toInt(), 100) {
                layer.shadowDy = (it - 50).toFloat(); canvasView.invalidate()
            })
            addView(layout)
        }

        // 2. Motion Shadow
        val motionShadowView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            val layout = LinearLayout(this@EditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16,8,16,8)
            }

             // Color
            layout.addView(createColorList(layer.shadowColor,
                { c -> layer.shadowColor = c; canvasView.invalidate() },
                { showColorWheelDialogForProperty(layer) { c -> layer.shadowColor = c; canvasView.invalidate() } }
            ))

            // Angle (0-360)
            val currentAngle = Math.toDegrees(atan2(layer.shadowDy.toDouble(), layer.shadowDx.toDouble())).toInt().let {
                if (it < 0) it + 360 else it
            }
            layout.addView(createSlider("Blur Direction (Angle): $currentAngle°", currentAngle, 360) { angle ->
                 // Recalculate dx, dy based on angle and current distance
                 val dist = kotlin.math.sqrt(layer.shadowDx*layer.shadowDx + layer.shadowDy*layer.shadowDy)
                 val rad = Math.toRadians(angle.toDouble())
                 layer.shadowDx = (dist * Math.cos(rad)).toFloat()
                 layer.shadowDy = (dist * Math.sin(rad)).toFloat()
                 canvasView.invalidate()
                 // Update label? Hard without reference.
            })

            // Strength (Distance)
            val currentDist = kotlin.math.sqrt(layer.shadowDx*layer.shadowDx + layer.shadowDy*layer.shadowDy).toInt()
            layout.addView(createSlider("Blur Strength (Distance)", currentDist, 200) { dist ->
                 val angleRad = atan2(layer.shadowDy.toDouble(), layer.shadowDx.toDouble())
                 layer.shadowDx = (dist * Math.cos(angleRad)).toFloat()
                 layer.shadowDy = (dist * Math.sin(angleRad)).toFloat()
                 canvasView.invalidate()
            })

            // Also Blur Radius?
             layout.addView(createSlider("Blur Softness", layer.shadowRadius.toInt(), 50) {
                layer.shadowRadius = it.toFloat(); canvasView.invalidate()
            })

            addView(layout)
        }

        fun selectTab(isDrop: Boolean) {
            contentContainer.removeAllViews()
            contentContainer.addView(if (isDrop) dropShadowView else motionShadowView)
        }

        val btnDrop = TextView(this).apply {
            text = "Drop Shadow"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { selectTab(true) }
        }

        val btnMotion = TextView(this).apply {
            text = "Motion Shadow"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { selectTab(false) }
        }

        tabsLayout.addView(btnDrop)
        tabsLayout.addView(btnMotion)
        container.addView(tabsLayout)
        container.addView(contentContainer)
        selectTab(true)
    }

    private fun showGradationControls() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() as? TextLayer ?: return
        layer.isGradient = true
        canvasView.invalidate()

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // Start Color
        mainLayout.addView(TextView(this).apply { text = "Start Color"; setTextColor(Color.LTGRAY) })
        mainLayout.addView(createColorList(layer.gradientStartColor,
             { c -> layer.gradientStartColor = c; canvasView.invalidate() },
             { showColorWheelDialogForProperty(layer) { c -> layer.gradientStartColor = c; canvasView.invalidate() } }
        ))

        // End Color
        mainLayout.addView(TextView(this).apply { text = "End Color"; setTextColor(Color.LTGRAY); setPadding(0,16,0,0) })
        mainLayout.addView(createColorList(layer.gradientEndColor,
             { c -> layer.gradientEndColor = c; canvasView.invalidate() },
             { showColorWheelDialogForProperty(layer) { c -> layer.gradientEndColor = c; canvasView.invalidate() } }
        ))

        // Angle
        mainLayout.addView(createSlider("Gradient Angle: ${layer.gradientAngle}°", layer.gradientAngle, 360) {
             layer.gradientAngle = it
             canvasView.invalidate()
        })

        scroll.addView(mainLayout)
        container.addView(scroll)
    }

    private fun showStrokeMenu() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() as? TextLayer ?: return

        val mainLayout = LinearLayout(this).apply {
             orientation = LinearLayout.VERTICAL
             layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Color List (Moved to Top)
        val colorList = createColorList(layer.strokeColor,
             { c -> layer.strokeColor = c; canvasView.invalidate() },
             { showColorWheelDialogForProperty(layer) { c -> layer.strokeColor = c; canvasView.invalidate() } }
        )
        mainLayout.addView(colorList)

        // Width Control (Style like Size tab)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
        }

        val tvLabel = TextView(this).apply {
            text = "Stroke Width"
            setTextColor(Color.LTGRAY)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnMinus = TextView(this).apply {
            text = "-"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.DKGRAY)
                cornerRadius = dpToPx(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(30))
            setOnClickListener {
                layer.strokeWidth = (layer.strokeWidth - 1).coerceAtLeast(0f)
                canvasView.invalidate()
                (row.getChildAt(2) as TextView).text = "${layer.strokeWidth.toInt()} pt"
            }
        }

        val tvValue = TextView(this).apply {
            text = "${layer.strokeWidth.toInt()} pt"
            setTextColor(Color.CYAN)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(80), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val btnPlus = TextView(this).apply {
            text = "+"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.DKGRAY)
                cornerRadius = dpToPx(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(30))
            setOnClickListener {
                layer.strokeWidth += 1
                canvasView.invalidate()
                (row.getChildAt(2) as TextView).text = "${layer.strokeWidth.toInt()} pt"
            }
        }

        row.addView(tvLabel)
        row.addView(btnMinus)
        row.addView(tvValue)
        row.addView(btnPlus)

        mainLayout.addView(row)
        container.addView(mainLayout)
    }

    private fun showDoubleStrokeMenu() {
        val layer = canvasView.getSelectedLayer() as? TextLayer ?: return
        if (layer.strokeWidth <= 0f) {
             Toast.makeText(this, "Enable Stroke first!", Toast.LENGTH_SHORT).show()
        }

        val container = prepareContainer()
        val mainLayout = LinearLayout(this).apply {
             orientation = LinearLayout.VERTICAL
             layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Color List (Moved to Top)
        val colorList = createColorList(layer.doubleStrokeColor,
             { c -> layer.doubleStrokeColor = c; canvasView.invalidate() },
             { showColorWheelDialogForProperty(layer) { c -> layer.doubleStrokeColor = c; canvasView.invalidate() } }
        )
        mainLayout.addView(colorList)

        // Width Control (Style like Size tab)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
        }

        val tvLabel = TextView(this).apply {
            text = "2nd Stroke Width"
            setTextColor(Color.LTGRAY)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnMinus = TextView(this).apply {
            text = "-"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.DKGRAY)
                cornerRadius = dpToPx(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(30))
            setOnClickListener {
                layer.doubleStrokeWidth = (layer.doubleStrokeWidth - 1).coerceAtLeast(0f)
                canvasView.invalidate()
                (row.getChildAt(2) as TextView).text = "${layer.doubleStrokeWidth.toInt()} pt"
            }
        }

        val tvValue = TextView(this).apply {
            text = "${layer.doubleStrokeWidth.toInt()} pt"
            setTextColor(Color.CYAN)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(80), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val btnPlus = TextView(this).apply {
            text = "+"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.DKGRAY)
                cornerRadius = dpToPx(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(30))
            setOnClickListener {
                layer.doubleStrokeWidth += 1
                canvasView.invalidate()
                (row.getChildAt(2) as TextView).text = "${layer.doubleStrokeWidth.toInt()} pt"
            }
        }

        row.addView(tvLabel)
        row.addView(btnMinus)
        row.addView(tvValue)
        row.addView(btnPlus)

        mainLayout.addView(row)
        container.addView(mainLayout)
    }

    private fun showColorWheelDialog(layer: TextLayer) {
        showColorWheelDialogForProperty(layer) { color ->
             val et = activeEditText
             if (et != null && et.selectionStart != et.selectionEnd) {
                applySpanToSelection(ForegroundColorSpan(color))
             } else {
                layer.color = color
                canvasView.invalidate()
             }
        }
    }

    private fun showColorWheelDialogForProperty(layer: TextLayer, applyColor: (Int) -> Unit) {
        val dialog = android.app.Dialog(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#333333"))
            }
            gravity = Gravity.CENTER
        }

        val wheel = com.astral.typer.views.RectangularColorPickerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(250), dpToPx(200))
        }

        val hexInput = EditText(this).apply {
            hint = "#RRGGBB"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(16)
            }
        }

        wheel.onColorChangedListener = { color ->
             val hex = String.format("#%06X", (0xFFFFFF and color))
             hexInput.setText(hex)
             applyColor(color)
        }

        root.addView(wheel)
        root.addView(hexInput)

        dialog.setContentView(root)
        dialog.show()
    }
}
