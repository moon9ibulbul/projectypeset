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
import com.astral.typer.utils.ColorPickerHelper
import com.astral.typer.utils.CustomTypefaceSpan
import com.astral.typer.utils.FontManager
import com.astral.typer.views.AstralCanvasView
import android.widget.GridLayout
import com.astral.typer.utils.StyleManager
import com.astral.typer.utils.InpaintManager
import com.astral.typer.utils.ProjectManager
import com.astral.typer.models.ImageLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var canvasView: AstralCanvasView

    private var activeEditText: EditText? = null
    private val MENU_HEIGHT_DP = 180
    private var currentMenuType: String? = null

    private var isInpaintMode = false
    private var btnApplyInpaint: android.widget.Button? = null
    private var toggleInpaintEngine: android.widget.ToggleButton? = null
    private var useTfliteEngine = false
    private lateinit var inpaintManager: InpaintManager

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

    private val addImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val inputStream = contentResolver.openInputStream(it)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                     // We need a path for saving later.
                     // For now pass null, ProjectManager will save the bitmap to temp when saving.
                     canvasView.addImageLayer(bitmap, null)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var isFontPickerVisible = false
    private lateinit var sidebarBinding: com.astral.typer.databinding.LayoutSidebarSaveBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Bind included sidebar
        // Use findViewById to ensure we get the View, avoiding Binding vs View ambiguity
        sidebarBinding = com.astral.typer.databinding.LayoutSidebarSaveBinding.bind(binding.root.findViewById(R.id.saveSidebar))

        // Setup Canvas
        // Check if loading a project
        val projectPath = intent.getStringExtra("PROJECT_PATH")

        canvasView = AstralCanvasView(this)
        binding.canvasContainer.addView(canvasView)

        if (projectPath != null) {
             val file = java.io.File(projectPath)
             // Load Async
             lifecycleScope.launch(Dispatchers.IO) {
                 val data = ProjectManager.loadProject(this@EditorActivity, file)
                 withContext(Dispatchers.Main) {
                     if (data != null) {
                         val (proj, images) = data
                         canvasView.initCanvas(proj.canvasWidth, proj.canvasHeight, proj.canvasColor)

                         // Restore background
                         if (images.containsKey("background")) {
                             canvasView.setBackgroundImage(images["background"]!!)
                         }

                         // Restore layers
                         val restoredLayers = mutableListOf<Layer>()
                         for (model in proj.layers) {
                             if (model.type == "TEXT") {
                                 val l = TextLayer(model.text ?: "").apply {
                                     x = model.x; y = model.y; rotation = model.rotation
                                     scaleX = model.scaleX; scaleY = model.scaleY
                                     color = model.color ?: Color.BLACK
                                     fontSize = model.fontSize ?: 24f
                                     boxWidth = model.boxWidth
                                     shadowColor = model.shadowColor ?: 0
                                     shadowRadius = model.shadowRadius ?: 0f
                                     shadowDx = model.shadowDx ?: 0f
                                     shadowDy = model.shadowDy ?: 0f
                                 }
                                 restoredLayers.add(l)
                             } else if (model.type == "IMAGE") {
                                 val bmp = images[model.imagePath]
                                 if (bmp != null) {
                                     val l = ImageLayer(bmp, model.imagePath).apply {
                                         x = model.x; y = model.y; rotation = model.rotation
                                         scaleX = model.scaleX; scaleY = model.scaleY
                                     }
                                     restoredLayers.add(l)
                                 }
                             }
                         }
                         canvasView.setLayers(restoredLayers)
                     } else {
                         Toast.makeText(this@EditorActivity, "Failed to load project", Toast.LENGTH_SHORT).show()
                         finish()
                     }
                 }
             }
        } else {
            val width = intent.getIntExtra("CANVAS_WIDTH", 1080)
            val height = intent.getIntExtra("CANVAS_HEIGHT", 1080)
            val color = intent.getIntExtra("CANVAS_COLOR", -1)
            val imageUriString = intent.getStringExtra("IMAGE_URI")

            canvasView.initCanvas(width, height, if (color == -1) Color.WHITE else color)

            if (imageUriString != null) {
                try {
                    val uri = android.net.Uri.parse(imageUriString)
                    val inputStream = contentResolver.openInputStream(uri)
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }
                    val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
                    if (bitmap != null) {
                        canvasView.setBackgroundImage(bitmap)
                    } else {
                        Toast.makeText(this, "Failed to decode image", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: OutOfMemoryError) {
                    Toast.makeText(this, "Image too large for memory!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Initialize InpaintManager
        inpaintManager = InpaintManager(this)

        // Listeners
        setupCanvasListeners()
        setupBottomMenu()
    }

    override fun onPause() {
        super.onPause()
        // Auto Save
        // Capture data on Main Thread
        val layers = canvasView.getLayers().toList() // Shallow copy list
        val bgBitmap = canvasView.getBackgroundImage()
        val bmp = canvasView.renderToBitmap()
        val w = bmp.width
        val h = bmp.height

        lifecycleScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            ProjectManager.saveProject(
                this@EditorActivity,
                layers,
                w,
                h,
                Color.WHITE,
                bgBitmap,
                "autosave"
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inpaintManager.close()
        com.astral.typer.utils.UndoManager.clearMemory()
    }

    private fun setupCanvasListeners() {
        canvasView.onLayerSelectedListener = object : AstralCanvasView.OnLayerSelectedListener {
            override fun onLayerSelected(layer: Layer?) {
                if (layer != null) {
                    if (currentMenuType == "QUICK_EDIT") {
                        hidePropertyDetail()
                    }
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

    private fun performInpaint(maskBitmap: android.graphics.Bitmap, onSuccess: () -> Unit) {
        val originalBitmap = canvasView.getBackgroundImage()
        if (originalBitmap == null) {
            Toast.makeText(this, "No image to inpaint", Toast.LENGTH_SHORT).show()
            return
        }

        // Save current state for Undo (Bitmap History)
        com.astral.typer.utils.UndoManager.saveBitmapState(originalBitmap)

        Toast.makeText(this, "Inpainting...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.Default) {
            // Run heavy OpenCV inpaint on background thread
            val result = inpaintManager.inpaint(originalBitmap, maskBitmap, useTfliteEngine)
            withContext(Dispatchers.Main) {
                if (result != null) {
                    canvasView.setBackgroundImage(result)
                    Toast.makeText(this@EditorActivity, "Done", Toast.LENGTH_SHORT).show()
                    onSuccess()
                } else {
                    Toast.makeText(this@EditorActivity, "Inpaint Failed: Check Logs", Toast.LENGTH_SHORT).show()
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
            addImageLauncher.launch("image/*")
        }

        // Top Bar Add Button
        binding.btnAdd.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            popup.menu.add("Text")
            popup.menu.add("Image")
            popup.setOnMenuItemClickListener {
                 if (it.title == "Text") canvasView.addTextLayer("Double Tap to Edit")
                 else if (it.title == "Image") addImageLauncher.launch("image/*")
                 true
            }
            popup.show()
        }

        // Property Actions
        binding.btnPropQuickEdit.setOnClickListener { toggleMenu("QUICK_EDIT") { showQuickEditMenu() } }
        binding.btnPropFont.setOnClickListener { toggleMenu("FONT") { showFontPicker() } }
        binding.btnPropColor.setOnClickListener { toggleMenu("COLOR") { showColorPicker() } }
        binding.btnPropFormat.setOnClickListener { toggleMenu("FORMAT") { showFormatMenu() } }

        // New Spacing Menu
        binding.btnPropSpacing.setOnClickListener { toggleMenu("SPACING") { showSpacingMenu() } }

        binding.btnPropStroke.setOnClickListener { toggleMenu("STROKE") { showStrokeMenu() } }
        binding.btnPropDoubleStroke.setOnClickListener { toggleMenu("DOUBLE_STROKE") { showDoubleStrokeMenu() } }
        binding.btnPropShadow.setOnClickListener { toggleMenu("SHADOW") { showShadowControls() } }
        binding.btnPropGradation.setOnClickListener { toggleMenu("GRADATION") { showGradationControls() } }
        binding.btnPropPerspective.setOnClickListener {
            // Toggle Perspective Mode directly or open menu?
            // "saat menu ini ditekan... semua ikon control hilang... sudut jadi titik"
            // "To cancel... click again or click another menu"
            if (currentMenuType == "PERSPECTIVE") {
                // Toggle Off
                togglePerspectiveMode(false)
                hidePropertyDetail() // Close container if open (though perspective has no container usually? prompt implies it's a mode)
                // "Jangan lupa untuk memunculkan kembali semua ikon"
            } else {
                toggleMenu("PERSPECTIVE") {
                    togglePerspectiveMode(true)
                    // Show a message or dummy container?
                    val container = prepareContainer()
                    val tv = TextView(this).apply {
                        text = "Drag corners to warp text.\nClick menu again to exit."
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        setPadding(32,32,32,32)
                    }
                    container.addView(tv)
                }
            }
        }

        // Top Bar
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { showSaveSidebar() }

        binding.btnEraser.setOnClickListener {
            toggleInpaintMode()
        }

        // Undo/Redo/Layers
        binding.btnUndo.setOnClickListener {
            if (isInpaintMode) {
                 // Mask Undo
                 canvasView.undoInpaintMask()
            } else {
                val restored = com.astral.typer.utils.UndoManager.undo(canvasView.getLayers())
                if (restored != null) {
                    canvasView.setLayers(restored)
                } else {
                    Toast.makeText(this, "Nothing to Undo", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnRedo.setOnClickListener {
            if (isInpaintMode) {
                // Mask Redo
                canvasView.redoInpaintMask()
            } else {
                val restored = com.astral.typer.utils.UndoManager.redo(canvasView.getLayers())
                if (restored != null) {
                    canvasView.setLayers(restored)
                } else {
                    Toast.makeText(this, "Nothing to Redo", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnLayers.setOnClickListener { showLayerMenu() }

        // Property Actions
        binding.btnPropStyle.setOnClickListener { toggleMenu("STYLE") { showStyleMenu() } }
    }

    // --- SIDEBAR LOGIC ---
    private fun showSaveSidebar() {
        binding.saveSidebar.root.visibility = View.VISIBLE
        resetSidebarUI()

        sidebarBinding.viewOverlay.setOnClickListener {
            binding.saveSidebar.root.visibility = View.GONE
        }

        sidebarBinding.btnSaveProjectOption.setOnClickListener {
            sidebarBinding.layoutSaveOptions.visibility = View.GONE
            sidebarBinding.layoutSaveProjectForm.visibility = View.VISIBLE
        }

        sidebarBinding.btnSaveFileOption.setOnClickListener {
            sidebarBinding.layoutSaveOptions.visibility = View.GONE
            sidebarBinding.layoutSaveFileForm.visibility = View.VISIBLE

            // Setup Spinner
            val formats = arrayOf("PNG", "JPG", "WEBP")
            val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, formats)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            sidebarBinding.spinnerFormat.adapter = adapter

            sidebarBinding.spinnerFormat.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    val fmt = formats[pos]
                    if (fmt == "PNG") sidebarBinding.qualityContainer.visibility = View.GONE
                    else sidebarBinding.qualityContainer.visibility = View.VISIBLE
                }
                override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
            }
        }

        sidebarBinding.seekBarQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                sidebarBinding.tvQualityLabel.text = "Quality: $p%"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // Back Buttons
        val backAction = {
             sidebarBinding.layoutSaveProjectForm.visibility = View.GONE
             sidebarBinding.layoutSaveFileForm.visibility = View.GONE
             sidebarBinding.layoutSaveOptions.visibility = View.VISIBLE
        }
        sidebarBinding.btnCancelSaveProject.setOnClickListener { backAction() }
        sidebarBinding.btnCancelSaveFile.setOnClickListener { backAction() }

        // CONFIRM ACTIONS
        sidebarBinding.btnConfirmSaveProject.setOnClickListener {
            val name = sidebarBinding.etProjectName.text.toString()
            if (name.isBlank()) {
                Toast.makeText(this, "Enter project name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Capture Data on Main Thread
            val layers = canvasView.getLayers().toList()
            val bgBitmap = canvasView.getBackgroundImage()
            val bmp = canvasView.renderToBitmap()
            val w = bmp.width
            val h = bmp.height

            // Save logic
             lifecycleScope.launch(Dispatchers.IO) {
                val success = ProjectManager.saveProject(
                    this@EditorActivity,
                    layers,
                    w,
                    h,
                    Color.WHITE,
                    bgBitmap,
                    name
                )
                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(this@EditorActivity, "Project Saved", Toast.LENGTH_SHORT).show()
                        binding.saveSidebar.root.visibility = View.GONE
                    } else {
                        Toast.makeText(this@EditorActivity, "Save Failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        sidebarBinding.btnConfirmSaveFile.setOnClickListener {
            val name = sidebarBinding.etFileName.text.toString()
             if (name.isBlank()) {
                Toast.makeText(this, "Enter file name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val formatStr = sidebarBinding.spinnerFormat.selectedItem.toString()
            val quality = sidebarBinding.seekBarQuality.progress

            // Export
            val bitmap = canvasView.renderToBitmap() // Full resolution
            val compressFormat = when(formatStr) {
                "JPG" -> android.graphics.Bitmap.CompressFormat.JPEG
                "WEBP" -> if (android.os.Build.VERSION.SDK_INT >= 30) android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS else android.graphics.Bitmap.CompressFormat.WEBP
                else -> android.graphics.Bitmap.CompressFormat.PNG
            }
            val ext = formatStr.lowercase()

            val filename = "$name.$ext"
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/$ext")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
            }

            val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                try {
                    contentResolver.openOutputStream(uri).use { stream ->
                        if (stream != null) {
                            bitmap.compress(compressFormat, quality, stream)
                        }
                    }
                    Toast.makeText(this, "File Exported!", Toast.LENGTH_SHORT).show()
                    binding.saveSidebar.root.visibility = View.GONE
                } catch (e: Exception) {
                     Toast.makeText(this, "Export Failed", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Failed to create file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resetSidebarUI() {
        sidebarBinding.layoutSaveOptions.visibility = View.VISIBLE
        sidebarBinding.layoutSaveProjectForm.visibility = View.GONE
        sidebarBinding.layoutSaveFileForm.visibility = View.GONE
    }

    private var inpaintToolbar: android.widget.LinearLayout? = null

    private fun toggleInpaintMode() {
        isInpaintMode = !isInpaintMode

        if (isInpaintMode) {
            binding.btnEraser.setImageResource(R.drawable.ic_pencil)
            canvasView.setInpaintMode(true)
            Toast.makeText(this, "Inpaint Mode: Draw over object to erase", Toast.LENGTH_SHORT).show()

            // Hide bottom menu in inpaint mode?
            binding.bottomMenuContainer.visibility = View.GONE
            hidePropertyDetail()

            // Deselect any layer
            canvasView.selectLayer(null)

            // Add Apply Button
            val btn = android.widget.Button(this).apply {
                text = "APPLY"
                setTextColor(Color.WHITE)
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#444444")) // Dark gray
                    cornerRadius = dpToPx(20).toFloat()
                    setStroke(dpToPx(2), Color.WHITE)
                }
                layoutParams = FrameLayout.LayoutParams(
                    dpToPx(120),
                    dpToPx(48)
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    setMargins(0, 0, 0, dpToPx(32))
                }
                setOnClickListener {
                    val mask = canvasView.getInpaintMask()
                    performInpaint(mask) {
                        canvasView.clearInpaintMask()
                    }
                }
            }
            binding.canvasContainer.addView(btn)
            btnApplyInpaint = btn

            // Add Engine Toggle
            val toggle = android.widget.ToggleButton(this).apply {
                textOn = "TFLite"
                textOff = "OpenCV"
                isChecked = useTfliteEngine
                text = if(isChecked) textOn else textOff
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#666666"))
                    cornerRadius = dpToPx(8).toFloat()
                }
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    setMargins(dpToPx(16), dpToPx(16), 0, 0)
                }
                setOnCheckedChangeListener { _, isChecked ->
                    useTfliteEngine = isChecked
                    text = if(isChecked) textOn else textOff
                    Toast.makeText(this@EditorActivity, "Engine: ${if(isChecked) "TFLite" else "OpenCV"}", Toast.LENGTH_SHORT).show()
                }
            }
            binding.canvasContainer.addView(toggle)
            toggleInpaintEngine = toggle

            // Add Inpaint Toolbar (Brush, Eraser, Lasso, Touch)
            showInpaintToolbar()

        } else {
            binding.btnEraser.setImageResource(R.drawable.ic_eraser)
            canvasView.setInpaintMode(false)
            binding.bottomMenuContainer.visibility = View.VISIBLE
            showInsertMenu()

            // Remove Apply Button
            btnApplyInpaint?.let {
                binding.canvasContainer.removeView(it)
                btnApplyInpaint = null
            }
            // Remove Toggle
            toggleInpaintEngine?.let {
                binding.canvasContainer.removeView(it)
                toggleInpaintEngine = null
            }
            // Remove Toolbar
            removeInpaintToolbar()
            canvasView.clearInpaintMask()
        }
    }

    private fun showInpaintToolbar() {
        if (inpaintToolbar != null) return

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(16, 16, 16, 16)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, dpToPx(90)) // Above Apply button
            }
        }

        fun addButton(iconRes: Int, text: String, tool: AstralCanvasView.InpaintTool) {
            val btn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(16, 8, 16, 8)
                setOnClickListener {
                    canvasView.currentInpaintTool = tool
                    Toast.makeText(this@EditorActivity, "$text Selected", Toast.LENGTH_SHORT).show()
                }
            }
            val iv = android.widget.ImageView(this).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24))
            }
            val tv = TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 10f
            }
            btn.addView(iv)
            btn.addView(tv)
            toolbar.addView(btn)
        }

        // Icons
        addButton(R.drawable.ic_pencil, "Brush", AstralCanvasView.InpaintTool.BRUSH)
        addButton(R.drawable.ic_eraser, "Eraser", AstralCanvasView.InpaintTool.ERASER)
        // Re-use pencil for Lasso if no other icons available
        addButton(R.drawable.ic_pencil, "Lasso", AstralCanvasView.InpaintTool.LASSO)
        // "Touch" is just Brush in this context
        addButton(R.drawable.ic_menu_palette, "Touch", AstralCanvasView.InpaintTool.BRUSH)

        binding.canvasContainer.addView(toolbar)
        inpaintToolbar = toolbar
    }

    private fun removeInpaintToolbar() {
        inpaintToolbar?.let {
            binding.canvasContainer.removeView(it)
            inpaintToolbar = null
        }
    }

    private fun togglePerspectiveMode(enabled: Boolean) {
        val layer = canvasView.getSelectedLayer() as? TextLayer
        if (layer != null) {
            // We set a flag on the layer or the canvas?
            // "Menu Perspective ... ditekan"
            // The prompt implies it's a tool mode.
            // Let's implement it in AstralCanvasView by setting a mode on the layer.
            layer.isPerspective = enabled
            // If enabled, initialize points if null
            if (enabled && layer.perspectivePoints == null) {
                // Initialize to current corners relative to center
                val w = layer.getWidth()
                val h = layer.getHeight()
                layer.perspectivePoints = floatArrayOf(
                    -w/2f, -h/2f, // TL
                    w/2f, -h/2f,  // TR
                    w/2f, h/2f,   // BR
                    -w/2f, h/2f   // BL
                )
            }

            // Notify Canvas
            canvasView.setPerspectiveMode(enabled) // We need to add this method to CanvasView
            canvasView.invalidate()
        }
    }

    // --- LAYERS MENU ---
    private fun showLayerMenu() {
        val popupView = layoutInflater.inflate(R.layout.popup_layers, null)
        val popupWindow = android.widget.PopupWindow(popupView, dpToPx(300), dpToPx(400), true)
        popupWindow.elevation = 20f
        popupWindow.showAsDropDown(binding.btnLayers, -dpToPx(200), 0)

        val recyclerView = popupView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerLayers)
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        val adapter = LayerAdapter(canvasView.getLayers()) {
            // On Item Click? Maybe select
            canvasView.selectLayer(it)
            canvasView.invalidate()
        }
        recyclerView.adapter = adapter

        // Drag and Drop
        val callback = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: androidx.recyclerview.widget.RecyclerView, vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, target: androidx.recyclerview.widget.RecyclerView.ViewHolder): Boolean {
                val from = vh.adapterPosition
                val to = target.adapterPosition
                java.util.Collections.swap(canvasView.getLayers(), from, to)
                adapter.notifyItemMoved(from, to)
                canvasView.invalidate()
                return true
            }
            override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int) {}
        }
        val itemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    // --- STYLE MENU ---
    private fun showStyleMenu() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() as? TextLayer

        val scroll = ScrollView(this)
        // 3 Column Grid
        val grid = GridLayout(this).apply {
            columnCount = 3
            setPadding(16,16,16,16)
        }

        // Saved Styles
        val saved = com.astral.typer.utils.StyleManager.getSavedStyles()

        if (saved.isEmpty()) {
            grid.addView(TextView(this).apply { text = "No Saved Styles"; setTextColor(Color.GRAY) })
        }

        for ((index, style) in saved.withIndex()) {
            // Container for item
            val itemContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dpToPx(100)
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    setMargins(8, 8, 8, 8)
                }
                background = GradientDrawable().apply {
                    setColor(Color.DKGRAY)
                    cornerRadius = dpToPx(8).toFloat()
                    setStroke(2, Color.LTGRAY)
                }
                setOnClickListener {
                    if (layer != null) {
                        // Apply style properties
                        com.astral.typer.utils.UndoManager.saveState(canvasView.getLayers())

                        layer.color = style.color
                        layer.fontSize = style.fontSize
                        layer.typeface = style.typeface
                        layer.opacity = style.opacity
                        layer.shadowColor = style.shadowColor
                        layer.shadowRadius = style.shadowRadius
                        layer.shadowDx = style.shadowDx
                        layer.shadowDy = style.shadowDy
                        layer.isMotionShadow = style.isMotionShadow
                        layer.motionShadowAngle = style.motionShadowAngle
                        layer.motionShadowDistance = style.motionShadowDistance
                        layer.motionBlurStrength = style.motionBlurStrength
                        layer.isGradient = style.isGradient
                        layer.gradientStartColor = style.gradientStartColor
                        layer.gradientEndColor = style.gradientEndColor
                        layer.gradientAngle = style.gradientAngle
                        layer.isGradientText = style.isGradientText
                        layer.isGradientStroke = style.isGradientStroke
                        layer.isGradientShadow = style.isGradientShadow
                        layer.strokeColor = style.strokeColor
                        layer.strokeWidth = style.strokeWidth
                        layer.doubleStrokeColor = style.doubleStrokeColor
                        layer.doubleStrokeWidth = style.doubleStrokeWidth
                        // Spacing
                        layer.letterSpacing = style.letterSpacing
                        layer.lineSpacing = style.lineSpacing

                        canvasView.invalidate()
                        Toast.makeText(context, "Style Applied", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // Preview Image
            val bmp = StyleManager.getPreview(style)
            val img = android.widget.ImageView(this).apply {
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams(dpToPx(80), dpToPx(80)).apply {
                    setMargins(0, 10, 0, 0)
                }
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            }
            itemContainer.addView(img)

            // Label
            val tv = TextView(this).apply {
                text = "Style ${index+1}"
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, 4, 0, 8)
            }
            itemContainer.addView(tv)

            grid.addView(itemContainer)
        }

        scroll.addView(grid)
        container.addView(scroll)
    }

    private fun showInsertMenu() {
        binding.bottomMenuContainer.visibility = View.VISIBLE
        binding.menuInsert.visibility = View.VISIBLE
        binding.menuProperties.visibility = View.GONE
    }

    private fun showPropertiesMenu() {
        binding.bottomMenuContainer.visibility = View.VISIBLE
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

        // If exiting perspective menu, ensure mode is off (if user didn't explicitly toggle off, assume exit cancels/applies? Prompt said "klik menu lain" cancels)
        // Check if we were in perspective
        if (currentMenuType == "PERSPECTIVE") {
            togglePerspectiveMode(false)
        }

        currentMenuType = null
    }

    private fun toggleMenu(type: String, showAction: () -> Unit) {
        if (currentMenuType == type && binding.propertyDetailContainer.visibility == View.VISIBLE) {
            hidePropertyDetail()
        } else {
            // Switching menus
            if (currentMenuType == "PERSPECTIVE" && type != "PERSPECTIVE") {
                togglePerspectiveMode(false)
            }
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

        val actualStart = if (start != -1 && end != -1 && start != end) start else 0
        val actualEnd = if (start != -1 && end != -1 && start != end) end else et.length()

        // Toggle Logic for StyleSpan, UnderlineSpan, StrikethroughSpan
        if (span is StyleSpan) {
            val existing = et.editableText.getSpans(actualStart, actualEnd, StyleSpan::class.java)
            var found = false
            for (s in existing) {
                if (s.style == span.style) {
                    et.editableText.removeSpan(s)
                    found = true
                }
            }
            if (!found) {
                et.editableText.setSpan(span, actualStart, actualEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else if (span is UnderlineSpan) {
            val existing = et.editableText.getSpans(actualStart, actualEnd, UnderlineSpan::class.java)
            if (existing.isNotEmpty()) {
                for (s in existing) et.editableText.removeSpan(s)
            } else {
                et.editableText.setSpan(span, actualStart, actualEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else if (span is StrikethroughSpan) {
            val existing = et.editableText.getSpans(actualStart, actualEnd, StrikethroughSpan::class.java)
            if (existing.isNotEmpty()) {
                for (s in existing) et.editableText.removeSpan(s)
            } else {
                et.editableText.setSpan(span, actualStart, actualEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            // For other spans (Color, Typeface), just apply (replace)
            et.editableText.setSpan(span, actualStart, actualEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val layer = canvasView.getSelectedLayer() as? TextLayer
        if (layer != null) {
            layer.text = SpannableStringBuilder(et.editableText)
            canvasView.invalidate()
        }
    }

    // --- QUICK EDIT MENU ---
    private fun showQuickEditMenu() {
        val container = prepareContainer()

        // Custom setup for Quick Edit: Hide standard bottom menu and adjust container
        binding.bottomMenuContainer.visibility = View.GONE
        binding.propertyDetailContainer.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        binding.propertyDetailContainer.requestLayout()

        val layer = canvasView.getSelectedLayer() as? TextLayer ?: return
        val originalText = SpannableStringBuilder(layer.text)

        container.addView(createInputView(layer, true))

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            weightSum = 6f // X, Left, Center, Right, Overflow, Check
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 8, 0, 0)
        }

        // Helper for icons
        fun addIcon(iconRes: Int, onClick: (View) -> Unit) {
            val btn = android.widget.ImageView(this).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
                setPadding(0, 16, 0, 16)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener(onClick)
            }
            toolbar.addView(btn)
        }

        // 1. Cancel (X)
        addIcon(R.drawable.ic_close) {
            // Restore text
            layer.text = originalText
            canvasView.invalidate()
            hidePropertyDetail()
            showPropertiesMenu()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        }

        // 2. Align Left
        addIcon(R.drawable.ic_format_align_left) {
            layer.textAlign = Layout.Alignment.ALIGN_NORMAL
            layer.isJustified = false
            canvasView.invalidate()
        }

        // 3. Align Center
        addIcon(R.drawable.ic_format_align_center) {
            layer.textAlign = Layout.Alignment.ALIGN_CENTER
            layer.isJustified = false
            canvasView.invalidate()
        }

        // 4. Align Right
        addIcon(R.drawable.ic_format_align_right) {
            layer.textAlign = Layout.Alignment.ALIGN_OPPOSITE
            layer.isJustified = false
            canvasView.invalidate()
        }

        // 5. Overflow (Dots)
        addIcon(R.drawable.ic_more_vert) { view ->
            val popup = android.widget.PopupMenu(this, view)
            popup.menu.add("UPPERCASE")
            popup.menu.add("lowercase")
            popup.menu.add("Capitalize Each Word")
            popup.setOnMenuItemClickListener { item ->
                val currentText = activeEditText?.text?.toString() ?: ""
                val newText = when (item.title) {
                    "UPPERCASE" -> currentText.uppercase()
                    "lowercase" -> currentText.lowercase()
                    "Capitalize Each Word" -> {
                         currentText.split(" ").joinToString(" ") {
                             it.replaceFirstChar { char -> char.uppercase() }
                         }
                    }
                    else -> currentText
                }
                activeEditText?.setText(newText)
                true
            }
            popup.show()
        }

        // 6. Done (Check)
        addIcon(R.drawable.ic_check) {
            hidePropertyDetail()
            showPropertiesMenu()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        }

        container.addView(toolbar)
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

            val outerLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                     ViewGroup.LayoutParams.MATCH_PARENT,
                     ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            // Search Bar
            val searchInput = EditText(this).apply {
                hint = "Search fonts..."
                setHintTextColor(Color.GRAY)
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(16, 8, 16, 8)
                background = GradientDrawable().apply {
                     setColor(Color.parseColor("#444444"))
                     cornerRadius = dpToPx(8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                     ViewGroup.LayoutParams.MATCH_PARENT,
                     ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(16, 0, 16, 8) }
                maxLines = 1
                inputType = android.text.InputType.TYPE_CLASS_TEXT
            }
            outerLayout.addView(searchInput)

            // Vertical List Container
            val scroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 0, 16, 0)
            }
            scroll.addView(list)
            outerLayout.addView(scroll)
            contentContainer.addView(outerLayout)

            if (type == "My Font") {
                val btnImport = TextView(this).apply {
                    text = "+ Import"
                    setTextColor(Color.WHITE)
                    setPadding(24, 16, 24, 16)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 16) }
                    background = GradientDrawable().apply {
                        setColor(Color.DKGRAY)
                        cornerRadius = dpToPx(8).toFloat()
                    }
                    setOnClickListener { importFontLauncher.launch("*/*") }
                }
                list.addView(btnImport)
            }

            val progressBar = android.widget.ProgressBar(this).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
            }
            list.addView(progressBar)

            // Async Load
            kotlin.concurrent.thread {
                val fonts = when(type) {
                    "Standard" -> FontManager.getStandardFonts(this)
                    "My Font" -> FontManager.getCustomFonts(this)
                    "Favorite" -> FontManager.getFavoriteFonts(this)
                    else -> emptyList()
                }

                runOnUiThread {
                    if (list.childCount > 0 && list.getChildAt(list.childCount - 1) == progressBar) {
                        list.removeView(progressBar)
                    }

                    val renderFonts = { query: String ->
                        // Clear existing font items (Keep Import button if My Font)
                        val startIndex = if (type == "My Font") 1 else 0
                        while (list.childCount > startIndex) {
                            list.removeViewAt(startIndex)
                        }

                        val filtered = if (query.isEmpty()) fonts else fonts.filter { it.name.contains(query, ignoreCase = true) }

                        val limit = 50
                        var count = 0

                        for (font in filtered) {
                            if (count >= limit && query.isEmpty()) break
                            count++

                            val itemLayout = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                setPadding(16, 16, 16, 16)
                                gravity = Gravity.CENTER_VERTICAL
                                layoutParams = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply { setMargins(0, 4, 0, 4) }

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
                                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                                maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            }

                            val btnStar = TextView(this).apply {
                                text = if (font.isFavorite) "★" else "☆"
                                setTextColor(Color.YELLOW)
                                textSize = 24f
                                setPadding(16, 0, 0, 0)
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

                        if (filtered.size > limit && query.isEmpty()) {
                             val moreBtn = TextView(this).apply {
                                 text = "Load more..."
                                 setTextColor(Color.LTGRAY)
                                 setPadding(16,16,16,16)
                                 gravity = Gravity.CENTER
                                 setOnClickListener {
                                     for (i in limit until filtered.size) {
                                         val font = filtered[i]
                                         val itemLayout = LinearLayout(this@EditorActivity).apply {
                                             orientation = LinearLayout.HORIZONTAL
                                             setPadding(16, 16, 16, 16)
                                             gravity = Gravity.CENTER_VERTICAL
                                             layoutParams = LinearLayout.LayoutParams(
                                                 ViewGroup.LayoutParams.MATCH_PARENT,
                                                 ViewGroup.LayoutParams.WRAP_CONTENT
                                             ).apply { setMargins(0, 4, 0, 4) }

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

                                         val tvName = TextView(this@EditorActivity).apply {
                                             text = font.name
                                             typeface = font.typeface
                                             textSize = 16f
                                             setTextColor(Color.WHITE)
                                             gravity = Gravity.CENTER_VERTICAL or Gravity.START
                                             maxLines = 1
                                             ellipsize = android.text.TextUtils.TruncateAt.END
                                             layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                         }

                                         val btnStar = TextView(this@EditorActivity).apply {
                                             text = if (font.isFavorite) "★" else "☆"
                                             setTextColor(Color.YELLOW)
                                             textSize = 24f
                                             setPadding(16, 0, 0, 0)
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
                                     (parent as ViewGroup).removeView(this)
                                 }
                             }
                             list.addView(moreBtn)
                        }
                    }

                    renderFonts("")

                    searchInput.addTextChangedListener(object: TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                             renderFonts(s?.toString() ?: "")
                        }
                        override fun afterTextChanged(s: Editable?) {}
                    })
                }
            }
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
                            // Apply Color and Disable Gradient
                            layer.color = color
                            layer.isGradient = false
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
            setOnClickListener {
                ColorPickerHelper.showColorPickerDialog(this@EditorActivity, layer.color) { color ->
                    val et = activeEditText
                    if (et != null && et.selectionStart != et.selectionEnd) {
                        applySpanToSelection(ForegroundColorSpan(color))
                    } else {
                        // Apply Color and Disable Gradient
                        layer.color = color
                        layer.isGradient = false
                        canvasView.invalidate()
                    }
                }
            }
        }
        list.addView(btnPalette)

        // Add saved colors view using helper
        // We use the helper directly, passing the current color for selection highlight
        val paletteView = ColorPickerHelper.createPaletteView(
            this,
            { color ->
                val et = activeEditText
                if (et != null && et.selectionStart != et.selectionEnd) {
                    applySpanToSelection(ForegroundColorSpan(color))
                } else {
                    // Toggle Off Logic: "Jika warna yang sedang aktif ditekan kembali, maka akan membatalkan efek text terkait"
                    // For Main Text Color, it doesn't make sense to "cancel" it (invisible text?).
                    // But if Gradient is active, maybe revert to solid?
                    // Let's assume for Main Color, it just sets it.
                    // But we MUST Disable Gradient if solid color is picked.

                    // Actually, prompt says: "jika dia menambahkan text color ... akan membatalkan gradation"

                    if (layer.color == color && !layer.isGradient) {
                        // Already active and solid. Do nothing or toggle?
                        // For main text, invisible is bad. So do nothing.
                    } else {
                        layer.color = color
                        layer.isGradient = false
                        canvasView.invalidate()
                    }
                }
            },
            null, // No add button here? Or keep it? The helper has logic for saved colors internally.
            layer.color // Selected color
        )
        // Extract the list from ScrollView returned by helper because we are already inside a scroll/linear layout structure?
        // No, current structure adds buttons then list.
        // Helper returns a ScrollView. We can add that ScrollView to our container, but we are inside a horizontal linear layout 'list'.
        // Better to just add the items.
        // But Helper encapsulates creation.
        // Let's just use the Helper's view instead of iterating manually.

        list.addView(paletteView)

        // Remove the old loop for saved colors

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

    // --- SPACING MENU ---
    private fun showSpacingMenu() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() as? TextLayer ?: return

        container.addView(createInputView(layer, false))

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
            row.addView(tvLabel); row.addView(btnMinus); row.addView(tvValue); row.addView(btnPlus)
            return row
        }

        // Letter Spacing
        val letterSpacingRow = createControl("Letter Spacing", String.format("%.2f", layer.letterSpacing),
            onMinus = {
                layer.letterSpacing -= 0.05f
                canvasView.invalidate()
                (layout.getChildAt(0) as LinearLayout).getChildAt(2).let { (it as TextView).text = String.format("%.2f", layer.letterSpacing) }
            },
            onPlus = {
                layer.letterSpacing += 0.05f
                canvasView.invalidate()
                (layout.getChildAt(0) as LinearLayout).getChildAt(2).let { (it as TextView).text = String.format("%.2f", layer.letterSpacing) }
            }
        )
        layout.addView(letterSpacingRow)

        // Line Spacing
        val lineSpacingRow = createControl("Line Spacing", "${layer.lineSpacing.toInt()}",
            onMinus = {
                layer.lineSpacing -= 5f
                canvasView.invalidate()
                (layout.getChildAt(1) as LinearLayout).getChildAt(2).let { (it as TextView).text = "${layer.lineSpacing.toInt()}" }
            },
            onPlus = {
                layer.lineSpacing += 5f
                canvasView.invalidate()
                (layout.getChildAt(1) as LinearLayout).getChildAt(2).let { (it as TextView).text = "${layer.lineSpacing.toInt()}" }
            }
        )
        layout.addView(lineSpacingRow)

        container.addView(layout)
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
        // Use ColorPickerHelper.createPaletteView logic
        return ColorPickerHelper.createPaletteView(
            this,
            { color ->
                // Toggle off logic for list items in submenu?
                // "Jika warna yang sedang aktif itu di tekan kembali, maka akan membatalkan efek text terkait"
                // Checking previous value:
                if (currentColor == color) {
                     // Trigger "Disable" logic. How?
                     // We need to pass a "disabled" state or -1?
                     // Or handled by caller.
                     // Since onColorPicked expects Int, let's just callback.
                     // The caller (e.g. showShadowControls) needs to handle toggle.
                     onColorPicked(color)
                } else {
                     onColorPicked(color)
                }
            },
            { onPaletteClick(); 0 }, // Fake add button to trigger palette dialog? No, design calls for Palette Button separately.
            currentColor
        )
    }

    // Adjusted Color List creation to match new Helper
    private fun createColorScroll(
        currentColor: Int,
        onColorPicked: (Int) -> Unit,
        onPaletteClick: () -> Unit
    ): View {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 8)
        }

        // Palette Button
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

        // Use Helper for the rest
        val palette = ColorPickerHelper.createPaletteView(this@EditorActivity, onColorPicked, null, currentColor)
        list.addView(palette)

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
            layout.addView(createColorScroll(layer.shadowColor,
                { c ->
                    if (layer.shadowColor == c && layer.shadowRadius > 0) {
                        // Toggle Off Shadow?
                        layer.shadowRadius = 0f
                    } else {
                        layer.shadowColor = c
                        if (layer.shadowRadius == 0f) layer.shadowRadius = 10f // Enable if was disabled
                    }
                    canvasView.invalidate()
                },
                { showColorWheelDialogForProperty(layer) { c -> layer.shadowColor = c; if(layer.shadowRadius==0f) layer.shadowRadius=10f; canvasView.invalidate() } }
            ))

            layout.addView(createSlider("Blur Radius", layer.shadowRadius.toInt(), 50) {
                layer.shadowRadius = it.toFloat(); canvasView.invalidate()
            })
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
            layout.addView(createColorScroll(layer.shadowColor,
                { c ->
                    if (layer.shadowColor == c && layer.motionShadowDistance > 0) {
                        layer.motionShadowDistance = 0f // Toggle off
                    } else {
                        layer.shadowColor = c
                        if (layer.motionShadowDistance == 0f) layer.motionShadowDistance = 20f
                    }
                    canvasView.invalidate()
                },
                { showColorWheelDialogForProperty(layer) { c -> layer.shadowColor = c; if(layer.motionShadowDistance==0f) layer.motionShadowDistance=20f; canvasView.invalidate() } }
            ))

            // Angle
            val angleLabel = TextView(this@EditorActivity).apply {
                text = "Blur Angle: ${layer.motionShadowAngle}°"
                setTextColor(Color.WHITE)
            }
            layout.addView(angleLabel)

            val sbAngle = SeekBar(this@EditorActivity).apply {
                max = 360
                progress = layer.motionShadowAngle
                setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        layer.motionShadowAngle = p
                        angleLabel.text = "Blur Angle: $p°"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            layout.addView(sbAngle)

            // Distance
            val distLabel = TextView(this@EditorActivity).apply {
                text = "Blur Distance: ${layer.motionShadowDistance.toInt()}"
                setTextColor(Color.WHITE)
                setPadding(0,16,0,0)
            }
            layout.addView(distLabel)

            val sbDist = SeekBar(this@EditorActivity).apply {
                max = 200
                progress = layer.motionShadowDistance.toInt()
                setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        layer.motionShadowDistance = p.toFloat()
                        distLabel.text = "Blur Distance: $p"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            layout.addView(sbDist)

            // Blur Strength (New)
            val blurLabel = TextView(this@EditorActivity).apply {
                text = "Blur Strength: ${layer.motionBlurStrength.toInt()}"
                setTextColor(Color.WHITE)
                setPadding(0,16,0,0)
            }
            layout.addView(blurLabel)

            val sbBlur = SeekBar(this@EditorActivity).apply {
                max = 50
                progress = layer.motionBlurStrength.toInt()
                setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        layer.motionBlurStrength = p.toFloat()
                        blurLabel.text = "Blur Strength: $p"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            layout.addView(sbBlur)

            addView(layout)
        }

        fun selectTab(isDrop: Boolean) {
            contentContainer.removeAllViews()
            contentContainer.addView(if (isDrop) dropShadowView else motionShadowView)

            layer.isMotionShadow = !isDrop
            canvasView.invalidate()
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

        // Toggles for Text, Stroke, Shadow
        val togglesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        fun createToggle(text: String, isChecked: Boolean, onChecked: (Boolean) -> Unit): android.widget.CheckBox {
            return android.widget.CheckBox(this).apply {
                setText(text)
                this.isChecked = isChecked
                setTextColor(Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.CYAN)
                setOnCheckedChangeListener { _, b -> onChecked(b) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        }

        togglesLayout.addView(createToggle("Text", layer.isGradientText) { b -> layer.isGradientText = b; canvasView.invalidate() })
        togglesLayout.addView(createToggle("Stroke", layer.isGradientStroke) { b -> layer.isGradientStroke = b; canvasView.invalidate() })
        togglesLayout.addView(createToggle("Shadow", layer.isGradientShadow) { b -> layer.isGradientShadow = b; canvasView.invalidate() })

        mainLayout.addView(togglesLayout)

        // Start Color
        mainLayout.addView(TextView(this).apply { text = "Start Color"; setTextColor(Color.LTGRAY) })
        mainLayout.addView(createColorScroll(layer.gradientStartColor,
             { c ->
                 if (layer.gradientStartColor == c) {
                     // Toggle off gradient?
                     layer.isGradient = false
                 } else {
                     layer.gradientStartColor = c
                 }
                 canvasView.invalidate()
             },
             { showColorWheelDialogForProperty(layer) { c -> layer.gradientStartColor = c; canvasView.invalidate() } }
        ))

        // End Color
        mainLayout.addView(TextView(this).apply { text = "End Color"; setTextColor(Color.LTGRAY); setPadding(0,16,0,0) })
        mainLayout.addView(createColorScroll(layer.gradientEndColor,
             { c ->
                 layer.gradientEndColor = c; canvasView.invalidate()
             },
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
        val colorList = createColorScroll(layer.strokeColor,
             { c ->
                 if (layer.strokeColor == c && layer.strokeWidth > 0) {
                     layer.strokeWidth = 0f
                 } else {
                     layer.strokeColor = c
                     if (layer.strokeWidth == 0f) layer.strokeWidth = 5f
                 }
                 canvasView.invalidate()
             },
             { showColorWheelDialogForProperty(layer) { c -> layer.strokeColor = c; if(layer.strokeWidth==0f) layer.strokeWidth=5f; canvasView.invalidate() } }
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
        val colorList = createColorScroll(layer.doubleStrokeColor,
             { c ->
                 if (layer.doubleStrokeColor == c && layer.doubleStrokeWidth > 0) {
                     layer.doubleStrokeWidth = 0f
                 } else {
                     layer.doubleStrokeColor = c
                     if (layer.doubleStrokeWidth == 0f) layer.doubleStrokeWidth = 5f
                 }
                 canvasView.invalidate()
             },
             { showColorWheelDialogForProperty(layer) { c -> layer.doubleStrokeColor = c; if(layer.doubleStrokeWidth==0f) layer.doubleStrokeWidth=5f; canvasView.invalidate() } }
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
                layer.isGradient = false
                canvasView.invalidate()
             }
        }
    }

    private fun showColorWheelDialogForProperty(layer: TextLayer, applyColor: (Int) -> Unit) {
        ColorPickerHelper.showColorPickerDialog(
            this,
            Color.WHITE,
            applyColor
        )
    }
}
