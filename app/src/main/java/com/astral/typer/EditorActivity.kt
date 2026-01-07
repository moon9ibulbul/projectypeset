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

    private val importTextureLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val inputStream = contentResolver.openInputStream(it)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                     val layer = canvasView.getSelectedLayer() as? TextLayer
                     if (layer != null) {
                         layer.textureBitmap = bitmap
                         layer.isGradient = false // Texture overrides gradient usually
                         canvasView.invalidate()
                         Toast.makeText(this, "Texture Imported", Toast.LENGTH_SHORT).show()
                     }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to load texture", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var isFontPickerVisible = false
    private lateinit var sidebarBinding: com.astral.typer.databinding.LayoutSidebarSaveBinding

    // Sync listener references
    private var sizeMenuListeners: UpdateListeners? = null

    // Helper class to store references to update UI
    data class UpdateListeners(
        val updateWidth: () -> Unit,
        val updateSize: () -> Unit,
        val updateScale: () -> Unit,
        val updateRotate: () -> Unit
    )

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

        canvasView.onLayerUpdateListener = object : AstralCanvasView.OnLayerUpdateListener {
            override fun onLayerUpdate(layer: Layer) {
                if (currentMenuType == "SIZE" || currentMenuType == "FORMAT") {
                    // We only need to update if the relevant tab is active, but we can try updating active listeners
                    sizeMenuListeners?.let {
                        it.updateWidth()
                        it.updateSize()
                        it.updateScale()
                        it.updateRotate()
                    }
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

        binding.btnPropWarp.setOnClickListener {
            if (currentMenuType == "WARP") {
                toggleWarpMode(false)
                hidePropertyDetail()
            } else {
                toggleMenu("WARP") {
                    showWarpMenu()
                }
            }
        }

        binding.btnPropOpacity.setOnClickListener { toggleMenu("OPACITY") { showOpacityMenu() } }

        binding.btnPropPerspective.setOnClickListener {
            if (currentMenuType == "PERSPECTIVE") {
                // Toggle Off (Exit Mode)
                togglePerspectiveMode(false)
                hidePropertyDetail()
            } else {
                toggleMenu("PERSPECTIVE") {
                    togglePerspectiveMode(true)
                    val container = prepareContainer()

                    val layout = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }

                    val btnReset = android.widget.Button(this).apply {
                        text = "Reset"
                        setTextColor(Color.WHITE)
                        background = GradientDrawable().apply {
                            setColor(Color.DKGRAY)
                            cornerRadius = dpToPx(8).toFloat()
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setOnClickListener {
                            val layer = canvasView.getSelectedLayer() as? TextLayer
                            if (layer != null) {
                                layer.perspectivePoints = null
                                // Re-init points to reset visual
                                val w = layer.getWidth()
                                val h = layer.getHeight()
                                layer.perspectivePoints = floatArrayOf(
                                    -w/2f, -h/2f, // TL
                                    w/2f, -h/2f,  // TR
                                    w/2f, h/2f,   // BR
                                    -w/2f, h/2f   // BL
                                )
                                canvasView.invalidate()
                                Toast.makeText(this@EditorActivity, "Perspective Reset", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    layout.addView(btnReset)
                    container.addView(layout)
                }
            }
        }

        // --- NEW MENUS ---
        // Add buttons for Texture and Hapus (Erase)
        // Since we are running out of space in the horizontal scroll view 'menuProperties', I'll append them.
        // We need to add buttons dynamically or modify XML.
        // Since I only modified XML for `activity_editor.xml` earlier (but I actually just read it),
        // I should have added buttons to XML.
        // However, I can add them programmatically here to the LinearLayout inside HorizontalScrollView.

        val propContainer = (binding.menuProperties.getChildAt(0) as LinearLayout)

        // Texture Button
        val btnTexture = android.widget.Button(this).apply {
             text = "Texture"
             style(this)
             setOnClickListener { toggleMenu("TEXTURE") { showTextureMenu() } }
        }
        propContainer.addView(btnTexture)

        // Hapus Button
        val btnHapus = android.widget.Button(this).apply {
             text = "Hapus"
             style(this)
             setOnClickListener { toggleMenu("HAPUS") { showHapusMenu() } }
        }
        propContainer.addView(btnHapus)


        // Top Bar
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { showSaveSidebar() }

        binding.btnEraser.setOnClickListener {
            toggleInpaintMode()
        }

        // Undo/Redo/Layers
        binding.btnUndo.setOnClickListener {
            if (isInpaintMode) {
                 // Mask Undo First
                 if (!canvasView.undoInpaintMask()) {
                     // If no mask to undo, undo Inpaint Result (Bitmap)
                     val currentBg = canvasView.getBackgroundImage()
                     val restoredBg = com.astral.typer.utils.UndoManager.undoBitmap(currentBg)
                     if (restoredBg != null) {
                         canvasView.setBackgroundImage(restoredBg)
                         Toast.makeText(this, "Undid Inpaint", Toast.LENGTH_SHORT).show()
                     } else {
                         Toast.makeText(this, "Nothing to Undo", Toast.LENGTH_SHORT).show()
                     }
                 }
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
                // Mask Redo First
                if (!canvasView.redoInpaintMask()) {
                    // Try to redo Bitmap
                    val currentBg = canvasView.getBackgroundImage()
                    val restoredBg = com.astral.typer.utils.UndoManager.redoBitmap(currentBg)
                    if (restoredBg != null) {
                         canvasView.setBackgroundImage(restoredBg)
                         Toast.makeText(this, "Redid Inpaint", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Nothing to Redo", Toast.LENGTH_SHORT).show()
                    }
                }
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

    private fun style(btn: android.widget.Button) {
        // Apply same style as XML buttons
        // @style/Widget.MaterialComponents.Button.TextButton + White Color
        // Since we can't easily apply style res programmatically without ContextThemeWrapper or inflation,
        // we'll just set properties manually to match "TextButton" look in dark theme.
        btn.setTextColor(Color.WHITE)
        btn.background = null // Transparent background
        btn.isAllCaps = true
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
            orientation = LinearLayout.VERTICAL // Changed to Vertical to stack slider and buttons
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(16, 16, 16, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC000000"))
                cornerRadius = dpToPx(16).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, dpToPx(90)) // Above Apply button
            }
        }

        // 1. Brush Size Slider
        val sizeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(200), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0,0,0,16)
            }
        }
        val tvSize = TextView(this).apply {
            text = "Size"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0,0,8,0)
        }
        val sbSize = SeekBar(this).apply {
            max = 100
            progress = canvasView.brushSize.toInt()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                    val size = p.coerceAtLeast(1).toFloat()
                    canvasView.brushSize = size
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        sizeLayout.addView(tvSize)
        sizeLayout.addView(sbSize)
        toolbar.addView(sizeLayout)

        // 2. Button Container
        val btnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // Helper to update button visual
        fun updateButtonVisual(btnLayout: LinearLayout, iconRes: Int, text: String) {
             val iv = btnLayout.getChildAt(0) as android.widget.ImageView
             val tv = btnLayout.getChildAt(1) as TextView
             iv.setImageResource(iconRes)
             tv.text = text
        }

        // --- Pair 1: Brush <-> Eraser ---
        val btnBrushEraser = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 8)
        }
        val ivBE = android.widget.ImageView(this).apply { setColorFilter(Color.WHITE); layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24)) }
        val tvBE = TextView(this).apply { setTextColor(Color.WHITE); textSize = 10f }
        btnBrushEraser.addView(ivBE); btnBrushEraser.addView(tvBE)

        fun updateBrushEraserState() {
            if (canvasView.currentInpaintTool == AstralCanvasView.InpaintTool.BRUSH) {
                // Current is Brush -> Show Eraser
                updateButtonVisual(btnBrushEraser, R.drawable.ic_eraser, "Eraser")
            } else {
                // Current is Eraser (or others, but we treat this pair) -> Show Brush
                updateButtonVisual(btnBrushEraser, R.drawable.ic_pencil, "Brush")
            }
        }

        btnBrushEraser.setOnClickListener {
             if (canvasView.currentInpaintTool == AstralCanvasView.InpaintTool.BRUSH) {
                 canvasView.currentInpaintTool = AstralCanvasView.InpaintTool.ERASER
                 Toast.makeText(this, "Eraser Selected", Toast.LENGTH_SHORT).show()
             } else {
                 canvasView.currentInpaintTool = AstralCanvasView.InpaintTool.BRUSH
                 Toast.makeText(this, "Brush Selected", Toast.LENGTH_SHORT).show()
             }
             updateBrushEraserState()
        }
        updateBrushEraserState() // Init

        // --- Pair 2: Lasso <-> Touch ---
        val btnLassoTouch = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 8)
        }
        val ivLT = android.widget.ImageView(this).apply { setColorFilter(Color.WHITE); layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24)) }
        val tvLT = TextView(this).apply { setTextColor(Color.WHITE); textSize = 10f }
        btnLassoTouch.addView(ivLT); btnLassoTouch.addView(tvLT)

        var isLassoActive = false

        fun updateLassoTouchState() {
             if (isLassoActive) {
                 // Active is Lasso -> Show Touch icon
                 updateButtonVisual(btnLassoTouch, R.drawable.ic_menu_palette, "Touch")
                 canvasView.currentInpaintTool = AstralCanvasView.InpaintTool.LASSO
             } else {
                 // Active is Touch (Standard Brush/Eraser mode) -> Show Lasso icon
                 updateButtonVisual(btnLassoTouch, R.drawable.ic_pencil, "Lasso")
                 if (btnBrushEraser.tag == "ERASER") {
                      canvasView.currentInpaintTool = AstralCanvasView.InpaintTool.BRUSH
                      updateBrushEraserState()
                 } else {
                      canvasView.currentInpaintTool = AstralCanvasView.InpaintTool.BRUSH
                      updateBrushEraserState()
                 }
             }
        }

        btnLassoTouch.setOnClickListener {
             isLassoActive = !isLassoActive
             updateLassoTouchState()
             if (isLassoActive) {
                 Toast.makeText(this, "Lasso Selected", Toast.LENGTH_SHORT).show()
             } else {
                 Toast.makeText(this, "Touch Mode (Brush/Eraser)", Toast.LENGTH_SHORT).show()
             }
        }
        updateLassoTouchState() // Init

        btnContainer.addView(btnBrushEraser)
        btnContainer.addView(btnLassoTouch)

        toolbar.addView(btnContainer)

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
            // "Menu Perspective ... ditekan"
            // The prompt implies it's a tool mode.

            // Only set layer.isPerspective = true when entering.
            // DO NOT set it to false on exit, to persist the effect.
            if (enabled) {
                layer.isPerspective = true
                // If enabled, initialize points if null
                if (layer.perspectivePoints == null) {
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
            }

            // Notify Canvas about the Interaction Mode
            canvasView.setPerspectiveMode(enabled)
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
        if (currentMenuType == "WARP") {
            toggleWarpMode(false)
        }
        if (currentMenuType == "HAPUS") {
            canvasView.setEraseLayerMode(false)
        }

        currentMenuType = null
        sizeMenuListeners = null
    }

    private fun toggleMenu(type: String, showAction: () -> Unit) {
        if (currentMenuType == type && binding.propertyDetailContainer.visibility == View.VISIBLE) {
            hidePropertyDetail()
        } else {
            // Switching menus
            if (currentMenuType == "PERSPECTIVE" && type != "PERSPECTIVE") {
                togglePerspectiveMode(false)
            }
            if (currentMenuType == "WARP" && type != "WARP") {
                toggleWarpMode(false)
            }
            if (currentMenuType == "HAPUS" && type != "HAPUS") {
                canvasView.setEraseLayerMode(false)
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

        val inputView = createInputView(layer, true)
        container.addView(inputView)

        // Set selection to end of text
        activeEditText?.let {
            it.post {
                it.setSelection(it.text.length)
            }
        }

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

    private fun createFormattingTab(layer: TextLayer): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }

        // Styles Row
        val stylesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            weightSum = 6f // Added 2 for mirrors
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

        // Mirror Buttons
        // Icon for Mirror H: |<|>| (using generic icon for now, e.g. sort)
        val btnMirrorH = android.widget.ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_sort_by_size) // Placeholder
            rotation = 90f // Make it look horizontal
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                layer.scaleX *= -1
                canvasView.invalidate()
            }
        }
        stylesRow.addView(btnMirrorH)

        val btnMirrorV = android.widget.ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_sort_by_size) // Placeholder
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                layer.scaleY *= -1
                canvasView.invalidate()
            }
        }
        stylesRow.addView(btnMirrorV)

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
                // Update will be handled by listener or manual invalidate
                //Manual update for click
                // (layout.getChildAt(0) as LinearLayout).getChildAt(2).let { (it as TextView).text = "${layer.fontSize.toInt()} pt" }
            },
            onPlus = {
                layer.fontSize += 1
                canvasView.invalidate()
            }
        )
        layout.addView(textSizeRow)

        // Box Scale
        val scaleRow = createControl("Box Scale", "${(layer.scaleX * 100).toInt()}%",
            onMinus = {
                val s = (layer.scaleX - 0.01f) // ScaleX might differ from ScaleY if mirrored
                // Simple scalar scaling assuming uniform or handling separate?
                // Using abs scale to increase/decrease magnitude
                val signX = Math.signum(layer.scaleX)
                val signY = Math.signum(layer.scaleY)
                val absS = kotlin.math.abs(layer.scaleX)
                val newS = (absS - 0.01f).coerceAtLeast(0.01f)
                layer.scaleX = newS * signX
                layer.scaleY = newS * signY // Maintain aspect
                canvasView.invalidate()
            },
            onPlus = {
                val signX = Math.signum(layer.scaleX)
                val signY = Math.signum(layer.scaleY)
                val absS = kotlin.math.abs(layer.scaleX)
                val newS = absS + 0.01f
                layer.scaleX = newS * signX
                layer.scaleY = newS * signY
                canvasView.invalidate()
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
            },
            onPlus = {
                 val w = (layer.boxWidth ?: layer.getWidth()) + 1f
                layer.boxWidth = w
                canvasView.invalidate()
            }
        )
        layout.addView(widthRow)

        // Rotate
        val rotateRow = createControl("Rotate", "${layer.rotation.toInt()}°",
            onMinus = {
                layer.rotation -= 1
                canvasView.invalidate()
            },
            onPlus = {
                layer.rotation += 1
                canvasView.invalidate()
            }
        )
        layout.addView(rotateRow)

        // Register listeners for Realtime Sync
        sizeMenuListeners = UpdateListeners(
            updateWidth = {
                val w = layer.boxWidth ?: 0f
                val s = if (w <= 0) "Auto" else "${w.toInt()} pt"
                (widthRow.findViewById<TextView>(widthRow.childCount-2) as? TextView)?.text = s // Hacky index find?
                // Better: find by index in layout. The structure is Label, Minus, Value, Plus. Value is index 2.
                (widthRow.getChildAt(2) as TextView).text = s
            },
            updateSize = {
                (textSizeRow.getChildAt(2) as TextView).text = "${layer.fontSize.toInt()} pt"
            },
            updateScale = {
                (scaleRow.getChildAt(2) as TextView).text = "${(kotlin.math.abs(layer.scaleX) * 100).toInt()}%"
            },
            updateRotate = {
                (rotateRow.getChildAt(2) as TextView).text = "${layer.rotation.toInt()}°"
            }
        )

        // Initial set (already done by createControl but good to ensure sync)
        sizeMenuListeners?.updateWidth?.invoke()
        sizeMenuListeners?.updateSize?.invoke()
        sizeMenuListeners?.updateScale?.invoke()
        sizeMenuListeners?.updateRotate?.invoke()

        return layout
    }

    private fun showTextureMenu() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() as? TextLayer ?: return

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // Import Button
        val btnImport = android.widget.Button(this).apply {
            text = "Import Texture"
            style(this)
            background = GradientDrawable().apply {
                setColor(Color.DKGRAY)
                cornerRadius = dpToPx(8).toFloat()
            }
            setOnClickListener {
                importTextureLauncher.launch("image/*")
            }
        }
        layout.addView(btnImport)

        // Arrows Control
        val arrowsLayout = GridLayout(this).apply {
            columnCount = 3
            rowCount = 3
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                setMargins(0, 32, 0, 0)
            }
        }

        fun createArrow(text: String, row: Int, col: Int, onClick: () -> Unit) {
            val btn = android.widget.Button(this).apply {
                this.text = text
                textSize = 20f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#444444"))
                    cornerRadius = dpToPx(8).toFloat()
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    rowSpec = GridLayout.spec(row)
                    columnSpec = GridLayout.spec(col)
                    width = dpToPx(60)
                    height = dpToPx(60)
                    setMargins(8,8,8,8)
                }
                setOnClickListener { onClick() }
            }
            arrowsLayout.addView(btn)
        }

        // Up (0, 1)
        createArrow("↑", 0, 1) {
            layer.textureOffsetY -= 5f
            canvasView.invalidate()
        }
        // Left (1, 0)
        createArrow("←", 1, 0) {
            layer.textureOffsetX -= 5f
            canvasView.invalidate()
        }
        // Right (1, 2)
        createArrow("→", 1, 2) {
            layer.textureOffsetX += 5f
            canvasView.invalidate()
        }
        // Down (2, 1)
        createArrow("↓", 2, 1) {
            layer.textureOffsetY += 5f
            canvasView.invalidate()
        }

        layout.addView(arrowsLayout)
        container.addView(layout)
    }

    private fun showHapusMenu() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() as? TextLayer ?: return

        // Enter Erase Mode
        canvasView.setEraseLayerMode(true)

        val layout = LinearLayout(this).apply {
             orientation = LinearLayout.VERTICAL
             setPadding(16, 16, 16, 16)
        }

        layout.addView(TextView(this).apply {
            text = "Erase Layer Mode"
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setPadding(0,0,0,16)
        })

        // Brush Size
        layout.addView(createSlider("Size: ${canvasView.layerEraseBrushSize.toInt()}", canvasView.layerEraseBrushSize.toInt(), 200) {
            canvasView.layerEraseBrushSize = it.toFloat().coerceAtLeast(1f)
            (layout.getChildAt(1) as LinearLayout).getChildAt(0).let { tv -> (tv as TextView).text = "Size: $it" }
        })

        // Opacity
        layout.addView(createSlider("Opacity: ${canvasView.layerEraseOpacity}", canvasView.layerEraseOpacity, 255) {
            canvasView.layerEraseOpacity = it
             (layout.getChildAt(2) as LinearLayout).getChildAt(0).let { tv -> (tv as TextView).text = "Opacity: $it" }
        })

        // Hardness
        layout.addView(createSlider("Hardness: ${canvasView.layerEraseHardness.toInt()}%", canvasView.layerEraseHardness.toInt(), 100) {
            canvasView.layerEraseHardness = it.toFloat()
             (layout.getChildAt(3) as LinearLayout).getChildAt(0).let { tv -> (tv as TextView).text = "Hardness: $it%" }
        })

        container.addView(layout)
    }
}
