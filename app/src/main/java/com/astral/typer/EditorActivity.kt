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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.astral.typer.databinding.ActivityEditorBinding
import com.astral.typer.models.Layer
import com.astral.typer.models.TextLayer
import com.astral.typer.models.ShapeLayer
import com.astral.typer.models.StylableLayer
import com.astral.typer.models.TextEffectType
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

    private var currentProjectName: String? = null
    private var parentFolderName: String? = null

    private var isInpaintMode = false
    private var btnApplyInpaint: android.widget.Button? = null
    private var btnApplyCut: android.widget.Button? = null
    private lateinit var inpaintManager: InpaintManager
    private lateinit var bubbleProcessor: com.astral.typer.utils.BubbleDetectorProcessor

    // Typer
    private var typerAdapter: TyperTextAdapter? = null
    private var typerTextLines: List<String> = emptyList()
    private var typerPopup: android.widget.PopupWindow? = null
    private var loadingDialog: android.app.Dialog? = null
    private var isProjectLoadedSuccessfully = true

    private val importTxtLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                contentResolver.openInputStream(it)?.use { stream ->
                    val text = stream.bufferedReader().use { reader -> reader.readText() }
                    val lines = text.lines().filter { line -> line.isNotBlank() }
                    updateTyperList(lines)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to load text", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val loadRawLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                contentResolver.openInputStream(it)?.use { inputStream ->
                    val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        canvasView.setRawPanelImage(bitmap)
                        sidebarBinding.layoutRawControls.visibility = View.VISIBLE
                        Toast.makeText(this, "RAW Panel Loaded!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to load RAW panel", Toast.LENGTH_SHORT).show()
            }
        }
    }

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

        // Clear Undo history from previous sessions if any
        com.astral.typer.utils.UndoManager.clearMemory()

        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Loading Dialog
        loadingDialog = android.app.Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(android.widget.ProgressBar(context).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            })
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setCancelable(false)
        }

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
             currentProjectName = file.nameWithoutExtension
             val parent = file.parentFile
             if (parent != null && parent.name != "Project" && parent.name != "Projects") {
                 parentFolderName = parent.name
             }
             isProjectLoadedSuccessfully = false
             // Load Async
             lifecycleScope.launch(Dispatchers.IO) {
                 val result = ProjectManager.loadProject(this@EditorActivity, file)
                 withContext(Dispatchers.Main) {
                     when (result) {
                         is ProjectManager.LoadResult.Success -> {
                             loadProjectData(result.projectData, result.images)
                             isProjectLoadedSuccessfully = true
                         }
                         is ProjectManager.LoadResult.MissingAssets -> {
                             // Show warning
                             val missingFonts = result.missingFonts.joinToString("\n")
                             android.app.AlertDialog.Builder(this@EditorActivity)
                                 .setTitle("Missing Fonts")
                                 .setMessage("The following fonts are not available on this device:\n$missingFonts\n\nDo you want to replace them with the default font?")
                                 .setPositiveButton("Replace") { _, _ ->
                                     // Proceed loading, but the layers will use default font since we can't load the custom one
                                     // In loadProjectData, we can handle logic to set default if font fails to load (which it already does essentially, but we want to be explicit)
                                     loadProjectData(result.projectData, result.images)
                                     isProjectLoadedSuccessfully = true
                                 }
                                 .setNegativeButton("Cancel") { _, _ ->
                                     finish()
                                 }
                                 .setCancelable(false)
                                 .show()
                         }
                         is ProjectManager.LoadResult.Error -> {
                             Toast.makeText(this@EditorActivity, "Failed to load project: ${result.message}", Toast.LENGTH_SHORT).show()
                             finish()
                         }
                     }
                 }
             }
        } else {
            val width = intent.getIntExtra("CANVAS_WIDTH", 1080)
            val height = intent.getIntExtra("CANVAS_HEIGHT", 1080)
            val color = intent.getIntExtra("CANVAS_COLOR", -1)
            val imageUriString = intent.getStringExtra("IMAGE_URI")

            canvasView.initCanvas(width, height, if (color == -1) Color.WHITE else color)

            val autoWatermark = getSharedPreferences("settings_prefs", MODE_PRIVATE).getBoolean("auto_watermark", false)
            if (autoWatermark && imageUriString == null) {
                addWatermarkLayer(true)
            }

            if (imageUriString != null) {
                binding.loadingOverlay.visibility = View.VISIBLE
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val uri = android.net.Uri.parse(imageUriString)
                        val inputStream = contentResolver.openInputStream(uri)
                        val options = android.graphics.BitmapFactory.Options().apply {
                            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                        }
                        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
                        withContext(Dispatchers.Main) {
                            if (bitmap != null) {
                                canvasView.setBackgroundImage(bitmap)
                                val autoWatermarkImport = getSharedPreferences("settings_prefs", MODE_PRIVATE).getBoolean("auto_watermark", false)
                                if (autoWatermarkImport) {
                                    addWatermarkLayer(true)
                                }
                            } else {
                                Toast.makeText(this@EditorActivity, "Failed to decode image", Toast.LENGTH_SHORT).show()
                            }
                            binding.loadingOverlay.visibility = View.GONE
                        }
                    } catch (e: OutOfMemoryError) {
                        withContext(Dispatchers.Main) {
                            binding.loadingOverlay.visibility = View.GONE
                            Toast.makeText(this@EditorActivity, "Image too large for memory!", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                         withContext(Dispatchers.Main) {
                            binding.loadingOverlay.visibility = View.GONE
                            Toast.makeText(this@EditorActivity, "Failed to load image", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        // Initialize InpaintManager
        inpaintManager = InpaintManager(this)

        // Init Bubble Processor
        bubbleProcessor = com.astral.typer.utils.BubbleDetectorProcessor(this)

        // Initialize StyleManager to load saved styles
        StyleManager.init(this)

        // Handle Back Press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                android.app.AlertDialog.Builder(this@EditorActivity)
                    .setTitle("Confirmation")
                    .setMessage("Do you want to go back to main menu?")
                    .setPositiveButton("Yes") { _, _ ->
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
        })

        // Listeners
        setupCanvasListeners()
        setupBottomMenu()

        // Check for Typer Model
        checkTyperAvailability()
    }

    private fun checkTyperAvailability() {
        // Always visible
        binding.btnTopTyper.visibility = View.VISIBLE
    }

    private fun loadProjectData(proj: ProjectManager.ProjectData, images: Map<String, android.graphics.Bitmap>) {
         canvasView.initCanvas(proj.canvasWidth, proj.canvasHeight, proj.canvasColor)

         // Restore background
         if (images.containsKey("images/background.png")) {
             canvasView.setBackgroundImage(images["images/background.png"]!!)
         } else if (images.containsKey("background.png")) {
             canvasView.setBackgroundImage(images["background.png"]!!)
         } else if (images.containsKey("background")) {
             canvasView.setBackgroundImage(images["background"]!!)
         }

         // Restore layers
         val restoredLayers = mutableListOf<Layer>()
         val availableFonts = FontManager.getStandardFonts(this) + FontManager.getCustomFonts(this)

         for (model in proj.layers) {
             val layer = ProjectManager.createLayerFromModel(model, images)
             if (layer != null) {
                 // Restore Font Typeface if TextLayer
                 if (layer is TextLayer && !model.fontPath.isNullOrEmpty()) {
                     val found = availableFonts.find {
                         (it.isCustom && it.path == model.fontPath) || (!it.isCustom && it.name == model.fontPath)
                     }
                     if (found != null) {
                         layer.typeface = found.typeface
                     } else {
                         layer.typeface = Typeface.DEFAULT
                     }
                 }
                 restoredLayers.add(layer)
             }
         }
         canvasView.setLayers(restoredLayers)
    }

    override fun onPause() {
        super.onPause()
        // Auto Save
        val settingsPrefs = getSharedPreferences("settings_prefs", MODE_PRIVATE)
        val enableAutosave = settingsPrefs.getBoolean("enable_autosave", false)
        if (!enableAutosave) return

        // Capture data on Main Thread
        val layersToSave = canvasView.getLayers().toMutableList()
        val bgBitmap = canvasView.getBackgroundImage()
        val bmp = canvasView.renderToBitmap()
        val w = bmp.width
        val h = bmp.height

        // Generate Thumbnail (small)
        val thumbW = 300
        val thumbH = (h * (thumbW.toFloat() / w)).toInt()
        val thumbnail = android.graphics.Bitmap.createScaledBitmap(bmp, thumbW, thumbH, true)

        if (isProjectLoadedSuccessfully) {
            ProjectManager.isSaving = true
            Toast.makeText(this, "Menyimpan autosave...", Toast.LENGTH_SHORT).show()

            lifecycleScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                try {
                    ProjectManager.saveProject(
                        this@EditorActivity,
                        layersToSave,
                        w,
                        h,
                        Color.WHITE,
                        bgBitmap,
                        "autosave",
                        thumbnail,
                        parentFolderName
                    )
                } finally {
                    ProjectManager.isSaving = false
                    // Recycle temporary bitmaps
                    bgBitmap?.recycle()
                    bmp.recycle()
                    thumbnail.recycle()
                }
            }
        } else {
            // Even if not saved, we should recycle the temporary bitmaps
            bgBitmap?.recycle()
            bmp.recycle()
            thumbnail.recycle()
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
                // Show/Hide Cut Button
                if (layer is ImageLayer) {
                    binding.btnCut.visibility = View.VISIBLE
                } else {
                    binding.btnCut.visibility = View.GONE
                }

                if (layer != null) {
                    if (currentMenuType == "QUICK_EDIT") {
                        hidePropertyDetail()
                    }
                    // Ensure properties are updated/shown even if already selected
                    showPropertiesMenu()

                    // Refresh active menu if one is open to sync with new layer
                    currentMenuType?.let { type ->
                         if (binding.propertyDetailContainer.visibility == View.VISIBLE) {
                             when (type) {
                                 "FONT" -> showFontPicker()
                                 "BRUSH" -> showBrushMenu()
                                 "COLOR" -> showColorPicker()
                                 "FORMAT" -> showFormatMenu()
                                 "EFFECT" -> showEffectMenu()
                                 "SPACING" -> showSpacingMenu()
                                 "STROKE" -> showStrokeMenu()
                                 "DOUBLE_STROKE" -> showDoubleStrokeMenu()
                                 "SHADOW" -> showShadowControls()
                                 "GRADATION" -> showGradationControls()
                                 "TEXTURE" -> showTextureMenu()
                                 "ERASE" -> showEraseMenu()
                                 "WARP" -> showWarpMenu()
                                 "PUPPET_WARP" -> showPuppetWarpMenu()
                                 "OPACITY" -> showOpacityMenu()
                                 "STYLE" -> showStyleMenu()
                                 "PERSPECTIVE" -> showPerspectiveMenu()
                             }
                         }
                    }
                } else {
                    if (!isInpaintMode) {
                        showInsertMenu()
                    }
                    hidePropertyDetail()
                }
            }
        }

        canvasView.onBubbleClickListener = { bubble ->
            // User clicked a detected bubble
            if (typerAdapter != null) {
                val text = typerAdapter?.getSelectedText() ?: "Text"
                val style = typerAdapter?.getSelectedStyle()
                val rect = bubble.rect

                // Create Text Layer
                val layer = TextLayer(text)
                layer.isOval = bubble.isOval

                // Position centered on rect
                // The rect is in Global Coords. layer.x/y is global.
                layer.x = rect.centerX()
                layer.y = rect.centerY()

                // Apply Style
                if (style != null) {
                    layer.color = style.color
                    layer.fontSize = style.fontSize
                    // Load Font if needed
                    if (style.fontPath != null) {
                         val found = FontManager.getStandardFonts(this@EditorActivity).find { it.name == style.fontPath }
                             ?: FontManager.getCustomFonts(this@EditorActivity).find { it.path == style.fontPath }

                         if (found != null) {
                             layer.typeface = found.typeface
                             layer.fontPath = if (found.isCustom) found.path else found.name
                         }
                    } else {
                        layer.typeface = Typeface.DEFAULT
                    }

                    layer.opacity = style.opacity
                    layer.shadowColor = style.shadowColor
                    layer.shadowRadius = style.shadowRadius
                    layer.shadowDx = style.shadowDx
                    layer.shadowDy = style.shadowDy
                    layer.isMotionShadow = style.isMotionShadow
                    layer.motionShadowAngle = style.motionAngle
                    layer.motionShadowDistance = style.motionDist
                    layer.isGradient = style.isGradient
                    layer.gradientStartColor = style.gradientStart
                    layer.gradientEndColor = style.gradientEnd
                    layer.gradientAngle = style.gradientAngle
                    layer.isGradientText = style.isGradientText
                    layer.isGradientStroke = style.isGradientStroke
                    layer.isGradientShadow = style.isGradientShadow
                    layer.strokeColor = style.strokeColor
                    layer.strokeWidth = style.strokeWidth
                    layer.doubleStrokeColor = style.doubleStrokeColor
                    layer.doubleStrokeWidth = style.doubleStrokeWidth
                    layer.letterSpacing = style.letterSpacing
                    layer.lineSpacing = style.lineSpacing

                    // Formatting
                    layer.textAlign = if (style.textAlign >= 0 && style.textAlign < Layout.Alignment.values().size)
                        Layout.Alignment.values()[style.textAlign] else Layout.Alignment.ALIGN_NORMAL
                    layer.isJustified = style.isJustified

                    if (style.isBold) layer.text.setSpan(StyleSpan(Typeface.BOLD), 0, layer.text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (style.isItalic) layer.text.setSpan(StyleSpan(Typeface.ITALIC), 0, layer.text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (style.isUnderline) layer.text.setSpan(UnderlineSpan(), 0, layer.text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (style.isStrike) layer.text.setSpan(StrikethroughSpan(), 0, layer.text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    layer.color = Color.BLACK
                }

                // Box Width (Constraint)
                val padding = 20f
                val targetWidth = (rect.width() - padding).coerceAtLeast(50f)
                val targetHeight = (rect.height() - padding).coerceAtLeast(50f)

                // Calculate Max Word Width to prevent splitting
                val paint = android.text.TextPaint().apply {
                    textSize = layer.fontSize
                    typeface = layer.typeface
                    letterSpacing = layer.letterSpacing
                }
                val textStr = layer.text.toString()
                // Split by spaces to find longest word
                val words = textStr.split("\\s+".toRegex())
                var maxWordWidth = 0f
                for (word in words) {
                    val w = paint.measureText(word)
                    if (w > maxWordWidth) maxWordWidth = w
                }
                // Add padding to max word width
                maxWordWidth += 40f

                // 4. Determine if content is "Dense" (heuristic: length > 20 chars)
                val isDense = layer.text.length > 20

                if (isDense) {
                    // Dense: Optimize boxWidth to match Aspect Ratio of Target

                    // Measure Height at arbitrary large width to estimate Area
                    layer.boxWidth = 1000f
                    val refH = layer.getHeight()

                    val estimatedArea = 1000f * refH
                    val targetAspect = targetWidth / targetHeight

                    // Ideal Width in Local Space
                    var idealWidth = kotlin.math.sqrt(estimatedArea * targetAspect)

                    // Constraint 1: Must be at least Max Word Width
                    if (idealWidth < maxWordWidth) idealWidth = maxWordWidth

                    // Constraint 2: Oval Adjustment
                    // Ovals have less effective width at top/bottom.
                    // Expanding width gives more room for text to flow without breaking.
                    if (layer.isOval) {
                        idealWidth *= 1.2f
                    }

                    // Apply Ideal Width
                    layer.boxWidth = idealWidth

                    // Recalculate dimensions (Local Space)
                    val newH = layer.getHeight()

                    // Calculate Scale to fit Target Box (Screen Space)
                    val scaleX = targetWidth / idealWidth
                    val scaleY = targetHeight / newH

                    var finalScale = minOf(scaleX, scaleY)

                    // If oval, boost scale slightly to fill better (resize permission)
                    if (layer.isOval) {
                         finalScale *= 1.15f
                    }

                    layer.scaleX = finalScale
                    layer.scaleY = finalScale

                } else {
                    // Sparse
                    layer.boxWidth = null
                    val naturalWidth = layer.getWidth() // Local natural width

                    var chosenWidth = naturalWidth + 40f // Padding

                    // Ensure we accommodate maxWordWidth (Local)
                    if (chosenWidth < maxWordWidth) chosenWidth = maxWordWidth

                    if (layer.isOval) {
                        chosenWidth *= 1.15f
                    }

                    layer.boxWidth = chosenWidth
                    val newH = layer.getHeight()

                    val scaleX = targetWidth / chosenWidth
                    val scaleY = targetHeight / newH

                    var finalScale = minOf(scaleX, scaleY)

                    // Cap at 1.0 to prevent blowing up small text
                    if (finalScale > 1f) finalScale = 1f

                    if (layer.isOval) {
                         // Allow slightly larger if oval to fill
                         finalScale *= 1.15f
                    }

                    layer.scaleX = finalScale
                    layer.scaleY = finalScale
                }

                // Save state before adding to allow step-by-step undo
                com.astral.typer.utils.UndoManager.saveState(canvasView.getLayers())

                canvasView.getLayers().add(layer)
                canvasView.selectLayer(layer)

                // Remove the bubble overlay
                canvasView.removeDetectedBubble(bubble)

                // Advance
                typerAdapter?.advanceSelection()
            }
        }

        canvasView.onLayerEditListener = object : AstralCanvasView.OnLayerEditListener {
            override fun onLayerDoubleTap(layer: Layer) {
                if (layer is TextLayer) {
                    if (currentMenuType == "QUICK_EDIT") return

                    // Open Quick Edit menu and focus input
                    showQuickEditMenu()
                    currentMenuType = "QUICK_EDIT"
                    // Delay focus slightly to ensure view is attached
                    binding.root.postDelayed({
                        val et = activeEditText
                        if (et != null) {
                            et.requestFocus()
                            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }, 300)
                }
            }
        }

        canvasView.onLayerUpdateListener = object : AstralCanvasView.OnLayerUpdateListener {
            override fun onLayerUpdate(layer: Layer) {
                if (binding.propertyDetailContainer.visibility == View.VISIBLE) {
                    // Update Size Tab Values if visible
                    val container = binding.propertyDetailContainer

                    // Rotate
                    container.findViewWithTag<TextView>("VAL_ROTATE")?.text = "${layer.rotation.toInt()}°"

                    // Box Scale
                    container.findViewWithTag<TextView>("VAL_BOX_SCALE")?.text = "${(layer.scale * 100).toInt()}%"

                    if (layer is TextLayer) {
                        // Text Size
                        container.findViewWithTag<TextView>("VAL_TEXT_SIZE")?.text = "${layer.fontSize.toInt()} pt"

                        // Box Width
                        val widthVal = layer.boxWidth ?: 0f
                        val widthStr = if (widthVal <= 0) "Auto" else "${widthVal.toInt()} pt"
                        container.findViewWithTag<TextView>("VAL_BOX_WIDTH")?.text = widthStr
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

        // Show Loading Overlay
        binding.loadingOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            // Run heavy inpaint on background thread (inpaint function is suspend and handles Dispatchers)
            val result = inpaintManager.inpaint(originalBitmap, maskBitmap)
            withContext(Dispatchers.Main) {
                binding.loadingOverlay.visibility = View.GONE
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

        binding.btnInsertShape.setOnClickListener {
            showShapePicker()
        }

        binding.btnInsertBrush.setOnClickListener {
            addBrushLayer()
        }

        // Top Bar Add Button
        binding.btnAdd.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            popup.menu.add("Text")
            popup.menu.add("Image")
            popup.menu.add("Shape")
            popup.setOnMenuItemClickListener {
                 if (it.title == "Text") canvasView.addTextLayer("Double Tap to Edit")
                 else if (it.title == "Image") addImageLauncher.launch("image/*")
                 else if (it.title == "Shape") showShapePicker()
                 true
            }
            popup.show()
        }

        // Property Actions
        binding.btnTopTyper.setOnClickListener { toggleTyperMode() }
        binding.btnPropQuickEdit.setOnClickListener { toggleMenu("QUICK_EDIT") { showQuickEditMenu() } }
        binding.btnPropBrush.setOnClickListener { toggleMenu("BRUSH") { showBrushMenu() } }
        binding.btnPropFont.setOnClickListener { toggleMenu("FONT") { showFontPicker() } }
        binding.btnPropColor.setOnClickListener { toggleMenu("COLOR") { showColorPicker() } }
        binding.btnPropFormat.setOnClickListener { toggleMenu("FORMAT") { showFormatMenu() } }
        binding.btnPropEffect.setOnClickListener { toggleMenu("EFFECT") { showEffectMenu() } }

        // New Spacing Menu
        binding.btnPropSpacing.setOnClickListener { toggleMenu("SPACING") { showSpacingMenu() } }

        binding.btnPropStroke.setOnClickListener { toggleMenu("STROKE") { showStrokeMenu() } }
        binding.btnPropDoubleStroke.setOnClickListener { toggleMenu("DOUBLE_STROKE") { showDoubleStrokeMenu() } }
        binding.btnPropShadow.setOnClickListener { toggleMenu("SHADOW") { showShadowControls() } }
        binding.btnPropGradation.setOnClickListener { toggleMenu("GRADATION") { showGradationControls() } }
        binding.btnPropTexture.setOnClickListener { toggleMenu("TEXTURE") { showTextureMenu() } }
        binding.btnPropErase.setOnClickListener { toggleMenu("ERASE") { showEraseMenu() } }

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

        binding.btnPropPuppetWarp.setOnClickListener {
            if (currentMenuType == "PUPPET_WARP") {
                togglePuppetWarpMode(false)
                hidePropertyDetail()
            } else {
                toggleMenu("PUPPET_WARP") {
                    showPuppetWarpMenu()
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
                    showPerspectiveMenu()
                }
            }
        }

        // Top Bar
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { showSaveSidebar() }

        binding.btnCut.setOnClickListener {
            enterCutMode()
        }

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
            } else if (currentMenuType == "ERASE") {
                // Layer Erase Undo
                canvasView.undoLayerErase()
                Toast.makeText(this, "Undid Erasure", Toast.LENGTH_SHORT).show()
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

    private fun saveCurrentStyle(layer: TextLayer) {
        val input = EditText(this)
        input.hint = "Enter Style Name"

        // Actually StyleManager doesn't support naming yet, just list.
        // And user didn't ask for it.
        // But we need to call StyleManager.saveStyle(context, layer).

        StyleManager.saveStyle(this, layer)
        Toast.makeText(this, "Style Saved", Toast.LENGTH_SHORT).show()
        // Do not hide, just refresh directly
        showStyleMenu()
    }

    private fun showEffectMenu() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() ?: return
        val stylableLayer = layer as? StylableLayer ?: return

        val getEffect = { stylableLayer.currentEffect }
        val getSecondaryEffect = { stylableLayer.secondaryEffect }
        val setEffect = { e: TextEffectType -> stylableLayer.currentEffect = e }
        val setSecondaryEffect = { e: TextEffectType -> stylableLayer.secondaryEffect = e }

        // Wrap everything in a ScrollView to ensure sliders are visible on small screens/landscape
        val mainScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainScroll.addView(mainLayout)
        container.addView(mainScroll)

        val cardsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val cardsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }

        // Helper to create Effect Cards
        fun createCard(title: String, effectType: TextEffectType, isSelected: Boolean, isSecondary: Boolean, onClick: () -> Unit, onDoubleClick: () -> Unit): View {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dpToPx(120), dpToPx(140)).apply {
                    setMargins(8, 8, 8, 8)
                }

                val borderColor = if (isSelected) Color.CYAN else if (isSecondary) Color.MAGENTA else Color.TRANSPARENT
                val bgColor = if (isSelected || isSecondary) Color.DKGRAY else Color.parseColor("#333333")

                background = GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = dpToPx(8).toFloat()
                    setStroke(dpToPx(2), borderColor)
                }

                val gestureDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                        onClick()
                        return true
                    }
                    override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                        onDoubleClick()
                        return true
                    }
                })

                setOnTouchListener { _, event ->
                    gestureDetector.onTouchEvent(event)
                    true
                }
            }

            // Generate Preview
            val previewBitmap = android.graphics.Bitmap.createBitmap(dpToPx(100), dpToPx(60), android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(previewBitmap)
            val dummyLayer = if (layer is ShapeLayer) {
                ShapeLayer(layer.shapeName).apply {
                    color = Color.WHITE; currentEffect = effectType
                    if (effectType == TextEffectType.LONG_SHADOW) shadowColor = Color.DKGRAY
                }
            } else {
                TextLayer("Abc").apply {
                    fontSize = dpToPx(30).toFloat(); color = Color.WHITE; currentEffect = effectType
                    if (effectType == TextEffectType.LONG_SHADOW) shadowColor = Color.DKGRAY
                }
            }
            // Center the dummy layer on the preview canvas
            dummyLayer.x = dpToPx(50).toFloat()
            dummyLayer.y = dpToPx(30).toFloat()
            dummyLayer.draw(canvas)

            val imgPreview = android.widget.ImageView(this).apply {
                setImageBitmap(previewBitmap)
                layoutParams = LinearLayout.LayoutParams(dpToPx(100), dpToPx(60)).apply {
                    setMargins(0, 16, 0, 0)
                }
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }
            card.addView(imgPreview)

            val tv = TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(8, 8, 8, 8)
            }
            card.addView(tv)
            return card
        }

        val handleEffectToggle = { effect: TextEffectType, isToggleOff: Boolean ->
            if (effect == TextEffectType.NONE) {
                setEffect(TextEffectType.NONE); setSecondaryEffect(TextEffectType.NONE)
            } else if (isToggleOff) {
                if (getEffect() == effect) setEffect(TextEffectType.NONE)
                if (getSecondaryEffect() == effect) setSecondaryEffect(TextEffectType.NONE)
            } else {
                if (getEffect() == TextEffectType.NONE) setEffect(effect)
                else if (getSecondaryEffect() == TextEffectType.NONE && getEffect() != effect) setSecondaryEffect(effect)
                else if (getEffect() != effect && getSecondaryEffect() != effect) {
                    setEffect(getSecondaryEffect()); setSecondaryEffect(effect)
                }
            }

            // Defaults
            if (effect == TextEffectType.CHROMATIC_ABERRATION && stylableLayer.chromaticShift == 0f) stylableLayer.chromaticShift = 5f
            if (effect == TextEffectType.GLITCH && stylableLayer.glitchIntensity == 0f) stylableLayer.glitchIntensity = 1.0f
            if (effect == TextEffectType.PIXELATION && stylableLayer.pixelBlockSize == 0f) stylableLayer.pixelBlockSize = 10f
            if (effect == TextEffectType.NEON && stylableLayer.neonRadius == 0f) stylableLayer.neonRadius = 30f
            if (effect == TextEffectType.LONG_SHADOW && stylableLayer.longShadowLength == 0f) stylableLayer.longShadowLength = 30f
            if (effect == TextEffectType.GAUSSIAN_BLUR && stylableLayer.blurRadius == 0f) stylableLayer.blurRadius = 10f
            if (effect == TextEffectType.HALFTONE && stylableLayer.halftoneDotSize == 0f) stylableLayer.halftoneDotSize = 10f
            if (effect == TextEffectType.TEXT_DECAY) {
                if (stylableLayer.decayIntensity == 0f) stylableLayer.decayIntensity = 0.5f
                if (stylableLayer.decayFadingLevel == 0f) stylableLayer.decayFadingLevel = 0.5f
            }

            canvasView.invalidate()
            showEffectMenu()
        }

        fun addEffectCard(title: String, effect: TextEffectType) {
            val isPrimary = getEffect() == effect
            val isSecondary = getSecondaryEffect() == effect
            cardsLayout.addView(createCard(title, effect, isPrimary, isSecondary,
                onClick = { handleEffectToggle(effect, false) },
                onDoubleClick = { handleEffectToggle(effect, true) }
            ))
        }

        val noEffectActive = getEffect() == TextEffectType.NONE && getSecondaryEffect() == TextEffectType.NONE
        cardsLayout.addView(createCard("None", TextEffectType.NONE, noEffectActive, false,
            onClick = { handleEffectToggle(TextEffectType.NONE, false) },
            onDoubleClick = { }
        ))

        addEffectCard("Chromatic", TextEffectType.CHROMATIC_ABERRATION)
        addEffectCard("Glitch", TextEffectType.GLITCH)
        addEffectCard("Pixelation", TextEffectType.PIXELATION)
        addEffectCard("Neon", TextEffectType.NEON)
        addEffectCard("Long Shadow", TextEffectType.LONG_SHADOW)
        addEffectCard("Fiery", TextEffectType.FIERY)
        addEffectCard("Wavy", TextEffectType.WAVY)
        addEffectCard("Gaussian Blur", TextEffectType.GAUSSIAN_BLUR)
        addEffectCard("Radial Blur", TextEffectType.RADIAL_BLUR)
        addEffectCard("Halftone", TextEffectType.HALFTONE)
        addEffectCard("Multi Gradient", TextEffectType.MULTI_GRADIENT)
        addEffectCard("Text Decay", TextEffectType.TEXT_DECAY)

        cardsScroll.addView(cardsLayout)
        mainLayout.addView(cardsScroll)

        // Settings Container for specific effects
        val settingsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Helper to check if effect is active
        val isEffectActive = { effect: TextEffectType -> getEffect() == effect || getSecondaryEffect() == effect }

        if (isEffectActive(TextEffectType.LONG_SHADOW)) {
                val currentLen = stylableLayer.longShadowLength
                val s1 = createSlider("Length: ${currentLen.toInt()}", currentLen.toInt(), 100) {
                    stylableLayer.longShadowLength = it.toFloat()
                    canvasView.invalidate()
                }
                val tv1 = s1.findViewWithTag<TextView>("SLIDER_LABEL")
                s1.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        stylableLayer.longShadowLength = p.toFloat()
                        tv1?.text = "Length: $p"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                settingsLayout.addView(s1)

                val currentAngle = stylableLayer.longShadowAngle
                val s2 = createSlider("Angle: ${currentAngle.toInt()}°", currentAngle.toInt(), 360) {
                    stylableLayer.longShadowAngle = it.toFloat()
                    canvasView.invalidate()
                }
                val tv2 = s2.findViewWithTag<TextView>("SLIDER_LABEL")
                s2.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        stylableLayer.longShadowAngle = p.toFloat()
                        tv2?.text = "Angle: $p°"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                settingsLayout.addView(s2)
                val tvColor = TextView(this).apply { text = "Shadow Color"; setTextColor(Color.LTGRAY); setPadding(0,16,0,0) }
                settingsLayout.addView(tvColor)
                settingsLayout.addView(createColorScroll(stylableLayer.longShadowColor,
                    { c -> stylableLayer.longShadowColor = c; canvasView.invalidate(); showEffectMenu() },
                    { showColorWheelDialogForProperty(stylableLayer.longShadowColor) { c -> stylableLayer.longShadowColor = c; canvasView.invalidate(); showEffectMenu() } }
                ))
        }
        if (isEffectActive(TextEffectType.FIERY)) {
                val currentFieryInt = stylableLayer.fieryIntensity
                val s1 = createSlider("Intensity: ${(currentFieryInt * 100).toInt()}%", (currentFieryInt * 100).toInt(), 100) {
                    stylableLayer.fieryIntensity = it / 100f
                    canvasView.invalidate()
                }
                val tv1 = s1.findViewWithTag<TextView>("SLIDER_LABEL")
                s1.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        stylableLayer.fieryIntensity = p / 100f
                        tv1?.text = "Intensity: $p%"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                settingsLayout.addView(s1)
                val tvColor = TextView(this).apply { text = "Fire Color"; setTextColor(Color.LTGRAY); setPadding(0,16,0,0) }
                settingsLayout.addView(tvColor)
                settingsLayout.addView(createColorScroll(stylableLayer.fieryColor,
                    { c -> stylableLayer.fieryColor = c; canvasView.invalidate(); showEffectMenu() },
                    { showColorWheelDialogForProperty(stylableLayer.fieryColor) { c -> stylableLayer.fieryColor = c; canvasView.invalidate(); showEffectMenu() } }
                ))
        }
        if (isEffectActive(TextEffectType.WAVY)) {
                val currentWInt = stylableLayer.wavyIntensity
                val s1 = createSlider("Intensity: ${(currentWInt * 100).toInt()}%", (currentWInt * 100).toInt(), 100) {
                    stylableLayer.wavyIntensity = it / 100f
                    canvasView.invalidate()
                }
                val tv1 = s1.findViewWithTag<TextView>("SLIDER_LABEL")
                s1.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        stylableLayer.wavyIntensity = p / 100f
                        tv1?.text = "Intensity: $p%"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                settingsLayout.addView(s1)

                val currentFreq = stylableLayer.wavyFrequency
                val s2 = createSlider("Frequency: ${currentFreq.toInt()}", currentFreq.toInt(), 50) {
                    stylableLayer.wavyFrequency = it.toFloat()
                    canvasView.invalidate()
                }
                val tv2 = s2.findViewWithTag<TextView>("SLIDER_LABEL")
                s2.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        stylableLayer.wavyFrequency = p.toFloat()
                        tv2?.text = "Frequency: $p"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                settingsLayout.addView(s2)
        }
        if (isEffectActive(TextEffectType.RADIAL_BLUR)) {
                val currentInner = stylableLayer.radialBlurInnerRadius
                val s1 = createSlider("Clear Area: ${currentInner.toInt()}", currentInner.toInt(), 500) {
                    stylableLayer.radialBlurInnerRadius = it.toFloat()
                    canvasView.invalidate()
                }
                val tv1 = s1.findViewWithTag<TextView>("SLIDER_LABEL")
                s1.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        stylableLayer.radialBlurInnerRadius = p.toFloat()
                        tv1?.text = "Clear Area: $p"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                settingsLayout.addView(s1)

                val currentMotion = stylableLayer.radialBlurMotionStrength
                val s2 = createSlider("Motion Strength: ${currentMotion.toInt()}", currentMotion.toInt(), 180) {
                    stylableLayer.radialBlurMotionStrength = it.toFloat()
                    canvasView.invalidate()
                }
                val tv2 = s2.findViewWithTag<TextView>("SLIDER_LABEL")
                s2.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        stylableLayer.radialBlurMotionStrength = p.toFloat()
                        tv2?.text = "Motion Strength: $p"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                settingsLayout.addView(s2)
        }
        if (isEffectActive(TextEffectType.GAUSSIAN_BLUR)) {
                val currentBlur = stylableLayer.blurRadius
                val s1 = createSlider("Blur Strength: ${currentBlur.toInt()}", currentBlur.toInt(), 50) {
                    stylableLayer.blurRadius = it.toFloat()
                    canvasView.invalidate()
                }
                val tv1 = s1.findViewWithTag<TextView>("SLIDER_LABEL")
                s1.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        stylableLayer.blurRadius = p.toFloat()
                        tv1?.text = "Blur Strength: $p"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                settingsLayout.addView(s1)
        }
        if (isEffectActive(TextEffectType.HALFTONE)) {
                val currentDotSize = stylableLayer.halftoneDotSize
                val s1 = createSlider("Dot Size: ${currentDotSize.toInt()}", currentDotSize.toInt().coerceIn(1, 50), 50) {
                    stylableLayer.halftoneDotSize = it.coerceAtLeast(1).toFloat()
                    canvasView.invalidate()
                }
                val tv1 = s1.findViewWithTag<TextView>("SLIDER_LABEL")
                s1.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        stylableLayer.halftoneDotSize = p.coerceAtLeast(1).toFloat()
                        tv1?.text = "Dot Size: $p"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                settingsLayout.addView(s1)
                val tvColor = TextView(this).apply { text = "Dot Color"; setTextColor(Color.LTGRAY); setPadding(0,16,0,0) }
                settingsLayout.addView(tvColor)
                settingsLayout.addView(createColorScroll(stylableLayer.halftoneDotColor,
                    { c -> stylableLayer.halftoneDotColor = c; canvasView.invalidate(); showEffectMenu() },
                    { showColorWheelDialogForProperty(stylableLayer.halftoneDotColor) { c -> stylableLayer.halftoneDotColor = c; canvasView.invalidate(); showEffectMenu() } }
                ))
        }
        if (isEffectActive(TextEffectType.MULTI_GRADIENT)) {
                // Multi Gradient Control
                val scrollPalette = HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                }

                val paletteContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                scrollPalette.addView(paletteContainer)

                data class PaletteItem(val name: String, val colors: IntArray)
                val palettes = listOf(
                    PaletteItem("Rainbow", intArrayOf(0xFFFF0000.toInt(), 0xFFFF7F00.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF4B0082.toInt(), 0xFF9400D3.toInt())),
                    PaletteItem("Sunset", intArrayOf(0xFF2D3486.toInt(), 0xFFC53888.toInt(), 0xFFFA7D60.toInt(), 0xFFFFD363.toInt())),
                    PaletteItem("Cyberpunk", intArrayOf(0xFF00F0FF.toInt(), 0xFFFF0099.toInt(), 0xFFCCFF00.toInt())),
                    PaletteItem("Gold", intArrayOf(0xFFBF953F.toInt(), 0xFFFCF6BA.toInt(), 0xFFFFFFFF.toInt(), 0xFFFBF5B7.toInt(), 0xFFAA771C.toInt())),
                    PaletteItem("Cotton Candy", intArrayOf(0xFFA1C4FD.toInt(), 0xFFC2E9FB.toInt(), 0xFFFBC2EB.toInt(), 0xFFA6C1EE.toInt()))
                )

                for (p in palettes) {
                    val btn = android.widget.Button(this).apply {
                        text = p.name
                        setTextColor(Color.WHITE)
                        textSize = 10f
                        background = GradientDrawable().apply {
                            orientation = GradientDrawable.Orientation.LEFT_RIGHT
                            colors = p.colors
                            cornerRadius = dpToPx(16).toFloat()
                            setStroke(dpToPx(1), Color.WHITE)
                        }
                        layoutParams = LinearLayout.LayoutParams(dpToPx(80), dpToPx(40)).apply {
                            setMargins(4,0,4,0)
                        }
                        setOnClickListener {
                            stylableLayer.multiGradientColors = p.colors
                            canvasView.invalidate()
                        }
                    }
                    paletteContainer.addView(btn)
                }

                settingsLayout.addView(TextView(this).apply { text = "Select Palette"; setTextColor(Color.LTGRAY) })
                settingsLayout.addView(scrollPalette)

                val currentMGAngle = stylableLayer.multiGradientAngle
                val s1 = createSlider("Angle: ${currentMGAngle.toInt()}°", currentMGAngle.toInt(), 360) {
                    stylableLayer.multiGradientAngle = it.toFloat()
                    canvasView.invalidate()
                }
                val tv1 = s1.findViewWithTag<TextView>("SLIDER_LABEL")
                s1.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        stylableLayer.multiGradientAngle = p.toFloat()
                        tv1?.text = "Angle: $p°"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                settingsLayout.addView(s1)
        }
        if (isEffectActive(TextEffectType.NEON)) {
                val currentNeonRadius = stylableLayer.neonRadius
                val s1 = createSlider("Glow Radius: ${currentNeonRadius.toInt()}", currentNeonRadius.toInt(), 100) {
                    stylableLayer.neonRadius = it.coerceAtLeast(1).toFloat()
                    canvasView.invalidate()
                }
                val tv1 = s1.findViewWithTag<TextView>("SLIDER_LABEL")
                s1.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        stylableLayer.neonRadius = p.coerceAtLeast(1).toFloat()
                        tv1?.text = "Glow Radius: $p"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                settingsLayout.addView(s1)
                val tvColor = TextView(this).apply { text = "Glow Color (Optional)"; setTextColor(Color.LTGRAY); setPadding(0,16,0,0) }
                settingsLayout.addView(tvColor)
                settingsLayout.addView(createColorScroll(stylableLayer.neonColor,
                    { c -> stylableLayer.neonColor = c; canvasView.invalidate(); showEffectMenu() },
                    { showColorWheelDialogForProperty(stylableLayer.neonColor) { c -> stylableLayer.neonColor = c; canvasView.invalidate(); showEffectMenu() } }
                ))
        }
        if (isEffectActive(TextEffectType.GLITCH)) {
                val currentGlitchInt = stylableLayer.glitchIntensity
                val s1 = createSlider("Intensity: ${(currentGlitchInt * 100).toInt()}%", (currentGlitchInt * 100).toInt(), 200) {
                    stylableLayer.glitchIntensity = it / 100f
                    canvasView.invalidate()
                }
                val tv1 = s1.findViewWithTag<TextView>("SLIDER_LABEL")
                s1.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        stylableLayer.glitchIntensity = p / 100f
                        tv1?.text = "Intensity: $p%"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                settingsLayout.addView(s1)
                val btnSeed = android.widget.Button(this).apply {
                    text = "Randomize Seed"
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.DKGRAY); cornerRadius = dpToPx(8).toFloat() }
                    setOnClickListener {
                        stylableLayer.effectSeed = java.util.Random().nextInt(10000).toLong()
                        canvasView.invalidate()
                    }
                }
                settingsLayout.addView(btnSeed)
        }
        if (isEffectActive(TextEffectType.PIXELATION)) {
                val currentPixelSize = stylableLayer.pixelBlockSize
                val s1 = createSlider("Block Size: ${currentPixelSize.toInt()}", currentPixelSize.toInt().coerceIn(1, 50), 50) {
                    stylableLayer.pixelBlockSize = it.coerceAtLeast(1).toFloat()
                    canvasView.invalidate()
                }
                val tv1 = s1.findViewWithTag<TextView>("SLIDER_LABEL")
                s1.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        stylableLayer.pixelBlockSize = p.coerceAtLeast(1).toFloat()
                        tv1?.text = "Block Size: $p"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                settingsLayout.addView(s1)
        }
        if (isEffectActive(TextEffectType.CHROMATIC_ABERRATION)) {
                // Chromatic Palette Control
                val scrollPalette = HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                }

                val paletteContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                scrollPalette.addView(paletteContainer)

                data class ChromaticPaletteItem(val name: String, val colors: IntArray)
                val palettes = listOf(
                    ChromaticPaletteItem("Standar", intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF00FF00.toInt())),
                    ChromaticPaletteItem("Standar 2", intArrayOf(0xFF00FFFF.toInt(), 0xFFFFFF00.toInt(), 0xFFFF00FF.toInt())),
                    ChromaticPaletteItem("Melancholy", intArrayOf(0xFF4A6984.toInt(), 0xFF7BA4B6.toInt(), 0xFFB3A1C6.toInt())),
                    ChromaticPaletteItem("Thriller", intArrayOf(0xFF8B0000.toInt(), 0xFF556B2F.toInt(), 0xFF4B0082.toInt())),
                    ChromaticPaletteItem("Romantic", intArrayOf(0xFFFF69B4.toInt(), 0xFFFFB6C1.toInt(), 0xFF87CEFA.toInt())),
                    ChromaticPaletteItem("Action", intArrayOf(0xFFFF2400.toInt(), 0xFFFFA500.toInt(), 0xFF00FFFF.toInt())),
                    ChromaticPaletteItem("Nostalgia", intArrayOf(0xFF8B5A2B.toInt(), 0xFFDAA520.toInt(), 0xFF8F9779.toInt()))
                )

                for (p in palettes) {
                    val btn = android.widget.Button(this).apply {
                        text = p.name
                        setTextColor(Color.WHITE)
                        textSize = 10f
                        background = GradientDrawable().apply {
                            orientation = GradientDrawable.Orientation.LEFT_RIGHT
                            colors = p.colors
                            cornerRadius = dpToPx(16).toFloat()
                            setStroke(dpToPx(1), Color.WHITE)
                        }
                        layoutParams = LinearLayout.LayoutParams(dpToPx(80), dpToPx(40)).apply {
                            setMargins(4,0,4,0)
                        }
                        setOnClickListener {
                            stylableLayer.chromaticColors = p.colors
                            canvasView.invalidate()
                        }
                    }
                    paletteContainer.addView(btn)
                }

                val customContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(4, 0, 4, 0)
                }

                val customLabel = TextView(this).apply {
                    text = "Custom: "
                    setTextColor(Color.WHITE)
                    textSize = 10f
                    setPadding(8, 0, 8, 0)
                }
                customContainer.addView(customLabel)

                for (i in 0..2) {
                    val colorBtn = android.widget.ImageView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dpToPx(30), dpToPx(30)).apply {
                            setMargins(4, 0, 4, 0)
                        }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(stylableLayer.chromaticColors[i])
                            setStroke(dpToPx(1), Color.WHITE)
                        }
                        setOnClickListener { btn ->
                            showColorWheelDialogForProperty(stylableLayer.chromaticColors[i]) { pickedColor ->
                                stylableLayer.chromaticColors[i] = pickedColor
                                (btn.background as GradientDrawable).setColor(pickedColor)
                                canvasView.invalidate()
                                showEffectMenu()
                            }
                        }
                    }
                    customContainer.addView(colorBtn)
                }

                paletteContainer.addView(customContainer)

                settingsLayout.addView(TextView(this).apply { text = "Select Palette"; setTextColor(Color.LTGRAY) })
                settingsLayout.addView(scrollPalette)

                val currentShift = stylableLayer.chromaticShift
                val s1 = createSlider("Shift: ${currentShift.toInt()}", currentShift.toInt(), 50) {
                    stylableLayer.chromaticShift = it.toFloat()
                    canvasView.invalidate()
                }
                val tv1 = s1.findViewWithTag<TextView>("SLIDER_LABEL")
                s1.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        stylableLayer.chromaticShift = p.toFloat()
                        tv1?.text = "Shift: $p"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                settingsLayout.addView(s1)
        }

        if (isEffectActive(TextEffectType.TEXT_DECAY)) {
                val currentIntensity = stylableLayer.decayIntensity
                val s1 = createSlider("Intensity: ${(currentIntensity * 100).toInt()}%", (currentIntensity * 100).toInt(), 100) {
                    stylableLayer.decayIntensity = it / 100f
                    canvasView.invalidate()
                }
                val tv1 = s1.findViewWithTag<TextView>("SLIDER_LABEL")
                s1.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        stylableLayer.decayIntensity = p / 100f
                        tv1?.text = "Intensity: $p%"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                settingsLayout.addView(s1)

                val currentFading = stylableLayer.decayFadingLevel
                val s2 = createSlider("Fading Level: ${(currentFading * 100).toInt()}%", (currentFading * 100).toInt(), 100) {
                    stylableLayer.decayFadingLevel = it / 100f
                    canvasView.invalidate()
                }
                val tv2 = s2.findViewWithTag<TextView>("SLIDER_LABEL")
                s2.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        stylableLayer.decayFadingLevel = p / 100f
                        tv2?.text = "Fading Level: $p%"
                        canvasView.invalidate()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                settingsLayout.addView(s2)

                val btnSeed = android.widget.Button(this).apply {
                    text = "Randomize Seed"
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.DKGRAY); cornerRadius = dpToPx(8).toFloat() }
                    setOnClickListener {
                        stylableLayer.effectSeed = java.util.Random().nextInt(10000).toLong()
                        canvasView.invalidate()
                    }
                }
                settingsLayout.addView(btnSeed)
        }

        mainLayout.addView(settingsLayout)
    }

    private fun showTextureMenu() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() ?: return
        val stylableLayer = layer as? StylableLayer ?: return

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(16, 8, 16, 8)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Import Button
        val btnImport = android.widget.Button(this).apply {
            text = "Import"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.DKGRAY)
                cornerRadius = dpToPx(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 8, 0)
            }
            setOnClickListener {
                importTextureLauncher.launch("image/*")
            }
        }
        btnRow.addView(btnImport)

        // Browse Button
        val btnBrowse = android.widget.Button(this).apply {
            text = "Browse"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.DKGRAY)
                cornerRadius = dpToPx(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(8, 0, 0, 0)
            }
            setOnClickListener {
                showPatternBrowser()
            }
        }
        btnRow.addView(btnBrowse)
        layout.addView(btnRow)

        if (stylableLayer.textureBitmap != null || stylableLayer.patternName != null) {
            val settingsScroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val settingsLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 0)
            }
            settingsScroll.addView(settingsLayout)

            if (stylableLayer.patternName != null) {
                val currentPatternScale = stylableLayer.patternScale
                val currentPatternAlpha = stylableLayer.patternAlpha
                val currentPatternRotation = stylableLayer.patternRotation

                // Color
                settingsLayout.addView(TextView(this).apply { text = "Pattern Color"; setTextColor(Color.LTGRAY) })
                settingsLayout.addView(createColorScroll(stylableLayer.patternColor,
                    { c -> stylableLayer.patternColor = c; canvasView.invalidate(); showTextureMenu() },
                    { showColorWheelDialogForProperty(stylableLayer.patternColor) { c -> stylableLayer.patternColor = c; canvasView.invalidate(); showTextureMenu() } }
                ))

                // Intensity
                val initialIntensity = ((5.0f - currentPatternScale) / 4.9f * 100).toInt().coerceIn(0, 100)
                settingsLayout.addView(createSlider("Intensity: $initialIntensity%", initialIntensity, 100) {
                    val scale = 5.0f - (it / 100f * 4.9f)
                    stylableLayer.patternScale = scale
                    canvasView.invalidate()
                })

                // Opacity
                settingsLayout.addView(createSlider("Opacity: ${(currentPatternAlpha / 2.55f).toInt()}%", currentPatternAlpha, 255) {
                    stylableLayer.patternAlpha = it
                    canvasView.invalidate()
                })

                // Rotation
                settingsLayout.addView(createSlider("Rotation: ${currentPatternRotation.toInt()}°", currentPatternRotation.toInt(), 360) {
                    stylableLayer.patternRotation = it.toFloat()
                    canvasView.invalidate()
                })

                val btnClear = android.widget.Button(this).apply {
                    text = "Clear Pattern"
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.parseColor("#880000")); cornerRadius = dpToPx(8).toFloat() }
                    setOnClickListener {
                        stylableLayer.patternName = null
                        canvasView.invalidate()
                        showTextureMenu()
                    }
                }
                settingsLayout.addView(btnClear)
            }

            // Offset Controls (Arrows)
            val controlsLabel = TextView(this).apply {
                text = if (stylableLayer.patternName != null) "Shift Pattern" else "Shift Texture"
                setTextColor(Color.LTGRAY)
                setPadding(0, 16, 0, 8)
            }
            settingsLayout.addView(controlsLabel)

            val controls = GridLayout(this).apply {
                columnCount = 3
                rowCount = 3
                alignmentMode = GridLayout.ALIGN_BOUNDS
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }

            fun createArrow(text: String, dx: Float, dy: Float): View {
                return android.widget.Button(this).apply {
                    this.text = text
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                         setColor(Color.parseColor("#444444"))
                         cornerRadius = dpToPx(8).toFloat()
                    }
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = dpToPx(40)
                        height = dpToPx(40)
                        setMargins(2,2,2,2)
                    }
                    setOnClickListener {
                        stylableLayer.textureOffsetX += dx
                        stylableLayer.textureOffsetY += dy
                        canvasView.invalidate()
                    }
                }
            }

            // Grid Layout for Arrows
            controls.addView(View(this), GridLayout.LayoutParams().apply { width = dpToPx(30); height = dpToPx(30) })
            controls.addView(createArrow("▲", 0f, -10f))
            controls.addView(View(this), GridLayout.LayoutParams().apply { width = dpToPx(30); height = dpToPx(30) })

            controls.addView(createArrow("◄", -10f, 0f))
            val btnReset = android.widget.Button(this).apply {
                text = "R"
                background = GradientDrawable().apply { setColor(Color.DKGRAY); cornerRadius = dpToPx(8).toFloat() }
                setTextColor(Color.WHITE)
                layoutParams = GridLayout.LayoutParams().apply { width = dpToPx(40); height = dpToPx(40); setMargins(2,2,2,2) }
                setOnClickListener {
                    stylableLayer.textureOffsetX = 0f
                    stylableLayer.textureOffsetY = 0f
                    canvasView.invalidate()
                }
            }
            controls.addView(btnReset)
            controls.addView(createArrow("►", 10f, 0f))

            controls.addView(View(this), GridLayout.LayoutParams().apply { width = dpToPx(30); height = dpToPx(30) })
            controls.addView(createArrow("▼", 0f, 10f))
            controls.addView(View(this), GridLayout.LayoutParams().apply { width = dpToPx(30); height = dpToPx(30) })

            settingsLayout.addView(controls)
            layout.addView(settingsScroll)
        }

        container.addView(layout)
    }

    private fun showPatternBrowser() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() ?: return
        val stylableLayer = layer as? StylableLayer ?: return

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
        }
        val btnBack = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            setOnClickListener { showTextureMenu() }
        }
        val tvTitle = TextView(this).apply {
            text = "Browse Patterns"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(16, 0, 0, 0)
        }
        titleBar.addView(btnBack)
        titleBar.addView(tvTitle)
        layout.addView(titleBar)

        val recyclerView = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@EditorActivity, 4)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val patterns = com.astral.typer.utils.PatternManager.listPatterns(this)
        val currentPatternName = stylableLayer.patternName
        recyclerView.adapter = PatternAdapter(lifecycleScope, patterns, currentPatternName) { selected ->
            stylableLayer.patternName = selected
            if (stylableLayer.patternAlpha == 0) stylableLayer.patternAlpha = 255
            if (stylableLayer.patternScale == 0f) stylableLayer.patternScale = 1.0f
            canvasView.invalidate()
            showTextureMenu()
        }
        layout.addView(recyclerView)

        container.addView(layout)
    }

    private fun enterCutMode() {
        if (btnApplyCut != null) return
        canvasView.enterCutMode()

        // Hide standard menus
        binding.bottomMenuContainer.visibility = View.GONE
        hidePropertyDetail()

        // Show Apply/Cancel
        val btn = android.widget.Button(this).apply {
            text = "APPLY CUT"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#444444"))
                cornerRadius = dpToPx(20).toFloat()
                setStroke(dpToPx(2), Color.WHITE)
            }
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(140),
                dpToPx(48)
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, dpToPx(32))
            }
            setOnClickListener {
                canvasView.applyCut()
                exitCutModeUI()
            }
        }
        binding.canvasContainer.addView(btn)
        btnApplyCut = btn

        // Add Cancel button top-right? Or reuse back button logic?
        // Let's add a simple X button on screen or rely on top bar?
        // Top bar is still visible. But we might want to prevent other actions.
        // Let's assume user presses Apply or Back.
        // We should handle Back press to cancel cut mode if active.
    }

    private fun exitCutModeUI() {
        btnApplyCut?.let {
            binding.canvasContainer.removeView(it)
            btnApplyCut = null
        }
        canvasView.exitCutMode()
        binding.bottomMenuContainer.visibility = View.VISIBLE
        showPropertiesMenu()
    }

    private val importTextureLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val inputStream = contentResolver.openInputStream(it)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                val layer = canvasView.getSelectedLayer()
                val stylableLayer = layer as? StylableLayer
                if (bitmap != null && stylableLayer != null) {
                     stylableLayer.textureBitmap = bitmap
                     stylableLayer.textureOffsetX = 0f
                     stylableLayer.textureOffsetY = 0f
                     stylableLayer.isGradient = false
                     canvasView.invalidate()
                     showTextureMenu() // Refresh to show controls
                     Toast.makeText(this, "Texture Applied", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to load texture", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showEraseMenu() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer()
        if (layer !is StylableLayer) return

        canvasView.setEraseLayerMode(true)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(16, 16, 16, 16)
        }

        val s1 = createSlider("Size: ${canvasView.layerEraseSize.toInt()}", canvasView.layerEraseSize.toInt(), 200) {
            canvasView.layerEraseSize = it.toFloat().coerceAtLeast(1f)
        }
        val tv1 = s1.findViewWithTag<TextView>("SLIDER_LABEL")
        val sb1 = s1.findViewWithTag<SeekBar>("SLIDER_BAR")
        sb1?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                val value = p.coerceAtLeast(1)
                canvasView.layerEraseSize = value.toFloat()
                tv1?.text = "Size: $value"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        layout.addView(s1)

        val s2 = createSlider("Opacity: ${canvasView.layerEraseOpacity}", canvasView.layerEraseOpacity, 255) {
            canvasView.layerEraseOpacity = it
        }
        val tv2 = s2.findViewWithTag<TextView>("SLIDER_LABEL")
        val sb2 = s2.findViewWithTag<SeekBar>("SLIDER_BAR")
        sb2?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                canvasView.layerEraseOpacity = p
                tv2?.text = "Opacity: $p"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        layout.addView(s2)

        val s3 = createSlider("Hardness: ${canvasView.layerEraseHardness.toInt()}%", canvasView.layerEraseHardness.toInt(), 100) {
            canvasView.layerEraseHardness = it.toFloat()
        }
        val tv3 = s3.findViewWithTag<TextView>("SLIDER_LABEL")
        val sb3 = s3.findViewWithTag<SeekBar>("SLIDER_BAR")
        sb3?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                canvasView.layerEraseHardness = p.toFloat()
                tv3?.text = "Hardness: $p%"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        layout.addView(s3)

        container.addView(layout)
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

            if (!currentProjectName.isNullOrEmpty()) {
                sidebarBinding.etProjectName.setText(currentProjectName)
            }
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

        // RAW Panel Controls
        sidebarBinding.btnLoadRaw.setOnClickListener {
            loadRawLauncher.launch("image/*")
        }

        val rawModes = arrayOf("Load on top Canvas", "Load Beside Canvas")
        val rawAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, rawModes)
        rawAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sidebarBinding.spinnerRawMode.adapter = rawAdapter

        sidebarBinding.spinnerRawMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                canvasView.rawPanelMode = if (pos == 0) AstralCanvasView.RawPanelMode.ON_TOP else AstralCanvasView.RawPanelMode.BESIDE
                canvasView.invalidate()
            }
            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
        }

        sidebarBinding.seekBarRawOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                canvasView.rawPanelOpacity = p
                sidebarBinding.tvRawOpacityLabel.text = "RAW Opacity: ${(p / 2.55f).toInt()}%"
                canvasView.invalidate()
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

        val settingsPrefs = getSharedPreferences("settings_prefs", MODE_PRIVATE)
        val enableWatermark = settingsPrefs.getBoolean("enable_watermark", false)
        sidebarBinding.btnInsertWatermark.visibility = if (enableWatermark) View.VISIBLE else View.GONE
        sidebarBinding.btnInsertWatermark.setOnClickListener {
            addWatermarkLayer(false)
        }

        // CONFIRM ACTIONS
        sidebarBinding.btnConfirmSaveProject.setOnClickListener {
            val name = sidebarBinding.etProjectName.text.toString()
            if (name.isBlank()) {
                Toast.makeText(this, "Enter project name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Check permissions for better chance at Public storage
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
                    return@setOnClickListener
                }
            }

            if (ProjectManager.projectExists(this, name)) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Peringatan")
                    .setMessage("Sudah ada project dengan nama yang sama, apakah ingin tetap menyimpan?")
                    .setPositiveButton("Ya") { _, _ ->
                        performProjectSave(name)
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            } else {
                performProjectSave(name)
            }
        }

        sidebarBinding.btnConfirmSaveFile.setOnClickListener {
            val name = sidebarBinding.etFileName.text.toString()
             if (name.isBlank()) {
                Toast.makeText(this, "Enter file name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Check permissions for older Androids
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
                    return@setOnClickListener
                }
            }

            val formatStr = sidebarBinding.spinnerFormat.selectedItem.toString()
            val quality = sidebarBinding.seekBarQuality.progress

            // Show Loading
            binding.loadingOverlay.visibility = View.VISIBLE

            // Export
            val bitmap = canvasView.renderToBitmap() // Full resolution - Main Thread

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
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
                }
            }

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri).use { stream ->
                            if (stream != null) {
                                bitmap.compress(compressFormat, quality, stream)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@EditorActivity, "File Exported!", Toast.LENGTH_SHORT).show()
                            binding.saveSidebar.root.visibility = View.GONE
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@EditorActivity, "Failed to create file", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                     withContext(Dispatchers.Main) {
                         Toast.makeText(this@EditorActivity, "Export Failed", Toast.LENGTH_SHORT).show()
                     }
                } finally {
                    withContext(Dispatchers.Main) {
                        binding.loadingOverlay.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun performProjectSave(name: String) {
        // Show Loading
        binding.loadingOverlay.visibility = View.VISIBLE

        // Capture Data on Main Thread
        val layersToSave = canvasView.getLayers().toMutableList()
        val bgBitmap = canvasView.getBackgroundImage()
        val bmp = canvasView.renderToBitmap()
        val w = bmp.width
        val h = bmp.height

        // Generate Thumbnail
        val thumbW = 300
        val thumbH = (h * (thumbW.toFloat() / w)).toInt()
        val thumbnail = android.graphics.Bitmap.createScaledBitmap(bmp, thumbW, thumbH, true)

        // Save logic
        lifecycleScope.launch(Dispatchers.IO) {
            var success = false
            try {
                success = ProjectManager.saveProject(
                    this@EditorActivity,
                    layersToSave,
                    w,
                    h,
                    Color.WHITE,
                    bgBitmap,
                    name,
                    thumbnail,
                    parentFolderName
                )
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // Recycle temporary bitmaps
                bgBitmap?.recycle()
                bmp.recycle()
                thumbnail.recycle()
            }

            withContext(Dispatchers.Main) {
                binding.loadingOverlay.visibility = View.GONE
                if (success) {
                    Toast.makeText(this@EditorActivity, "Project Saved", Toast.LENGTH_SHORT).show()
                    binding.saveSidebar.root.visibility = View.GONE
                } else {
                    Toast.makeText(this@EditorActivity, "Save Failed", Toast.LENGTH_SHORT).show()
                }
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
            binding.btnEraser.setColorFilter(Color.CYAN) // Active Indicator
            canvasView.setInpaintMode(true)
            // Toast.makeText(this, "Inpaint Mode: Draw over object to erase", Toast.LENGTH_SHORT).show()

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

            // Add Inpaint Toolbar (Brush, Eraser, Lasso, Touch)
            showInpaintToolbar()

        } else {
            binding.btnEraser.setImageResource(R.drawable.ic_eraser)
            binding.btnEraser.setColorFilter(Color.WHITE) // Inactive Indicator
            canvasView.setInpaintMode(false)
            binding.bottomMenuContainer.visibility = View.VISIBLE
            showInsertMenu()

            // Remove Apply Button
            btnApplyInpaint?.let {
                binding.canvasContainer.removeView(it)
                btnApplyInpaint = null
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
            // Add background as requested
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#80000000")) // 50% opacity black
                cornerRadius = dpToPx(16).toFloat()
            }
            setPadding(16, 16, 16, 16)

            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, dpToPx(90)) // Above Apply button
            }
        }

        // --- Engine Selector (If LaMa Available) ---
        // Check LaMa availability
        val lamaProcessor = com.astral.typer.utils.LaMaProcessor(this)
        if (lamaProcessor.isModelAvailable()) {
            val engineLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0,0,0,16)
                }
            }

            val modes = arrayOf("OpenCV (Telea)", "LaMa (AI)")
            val spinner = android.widget.Spinner(this)
            val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter

            // Set initial selection
            spinner.setSelection(0)
            inpaintManager.setEngine(InpaintManager.Engine.OPENCV)

            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    if (pos == 0) {
                        inpaintManager.setEngine(InpaintManager.Engine.OPENCV)
                    } else {
                        inpaintManager.setEngine(InpaintManager.Engine.LAMA)
                    }
                }
                override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
            }

            // Customizing Spinner Text Color is tricky programmatically with default layout.
            // Wrapping it or just let it be standard Android style.
            // Better: Radio Group? Or just a Toggle Button?
            // Spinner is fine, but text might be dark. Let's force background to white for spinner popup or use a custom view.
            // Simple: just add it. The text color depends on theme. Activity is AppCompat.

            val tvLabel = TextView(this).apply {
                text = "Engine: "
                setTextColor(Color.WHITE)
            }
            engineLayout.addView(tvLabel)
            engineLayout.addView(spinner)
            toolbar.addView(engineLayout)
        } else {
            // Just force OpenCV
            inpaintManager.setEngine(InpaintManager.Engine.OPENCV)
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

        // Detect Text Button (if model available)
        if (bubbleProcessor.isModelAvailable()) {
            val btnDetectText = android.widget.LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(16, 8, 16, 8)
                layoutParams = LinearLayout.LayoutParams(dpToPx(60), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val ivDetect = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_typer) // Reusing Typer icon
                setColorFilter(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24))
            }
            val tvDetect = TextView(this).apply {
                text = "Text"
                setTextColor(Color.WHITE)
                textSize = 10f
                gravity = Gravity.CENTER
            }
            btnDetectText.addView(ivDetect)
            btnDetectText.addView(tvDetect)
            btnDetectText.setOnClickListener {
                detectTextForInpaint()
            }
            btnContainer.addView(btnDetectText)
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
            layoutParams = LinearLayout.LayoutParams(dpToPx(60), ViewGroup.LayoutParams.WRAP_CONTENT) // Fixed width to prevent truncation
        }
        val ivBE = android.widget.ImageView(this).apply { setColorFilter(Color.WHITE); layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24)) }
        val tvBE = TextView(this).apply { setTextColor(Color.WHITE); textSize = 10f; gravity = Gravity.CENTER }
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
                 // Toast.makeText(this, "Eraser Selected", Toast.LENGTH_SHORT).show()
             } else {
                 canvasView.currentInpaintTool = AstralCanvasView.InpaintTool.BRUSH
                 // Toast.makeText(this, "Brush Selected", Toast.LENGTH_SHORT).show()
             }
             updateBrushEraserState()
        }
        updateBrushEraserState() // Init

        // --- Pair 2: Lasso <-> Touch ---
        val btnLassoTouch = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(dpToPx(60), ViewGroup.LayoutParams.WRAP_CONTENT) // Fixed width to prevent truncation
        }
        val ivLT = android.widget.ImageView(this).apply { setColorFilter(Color.WHITE); layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24)) }
        val tvLT = TextView(this).apply { setTextColor(Color.WHITE); textSize = 10f; gravity = Gravity.CENTER }
        btnLassoTouch.addView(ivLT); btnLassoTouch.addView(tvLT)

        var isLassoActive = false
        var isBaseToolEraser = false // true if Eraser, false if Brush

        fun updateState() {
             // Button 1 Visuals
             if (isBaseToolEraser) {
                 updateButtonVisual(btnBrushEraser, R.drawable.ic_eraser, "Eraser")
                 btnBrushEraser.tag = "ERASER"
             } else {
                 updateButtonVisual(btnBrushEraser, R.drawable.ic_pencil, "Brush")
                 btnBrushEraser.tag = "BRUSH"
             }

             // Button 2 Visuals
             if (isLassoActive) {
                 updateButtonVisual(btnLassoTouch, R.drawable.ic_menu_palette, "Touch")
             } else {
                 updateButtonVisual(btnLassoTouch, R.drawable.ic_pencil, "Lasso")
             }

             // Apply Tool
             if (isLassoActive) {
                 if (isBaseToolEraser) {
                     canvasView.currentInpaintTool = AstralCanvasView.InpaintTool.LASSO_ERASER
                     // Toast.makeText(this, "Lasso Eraser", Toast.LENGTH_SHORT).show()
                 } else {
                     canvasView.currentInpaintTool = AstralCanvasView.InpaintTool.LASSO
                     // Toast.makeText(this, "Lasso Brush", Toast.LENGTH_SHORT).show()
                 }
             } else {
                 if (isBaseToolEraser) {
                     canvasView.currentInpaintTool = AstralCanvasView.InpaintTool.ERASER
                     // Toast.makeText(this, "Eraser", Toast.LENGTH_SHORT).show()
                 } else {
                     canvasView.currentInpaintTool = AstralCanvasView.InpaintTool.BRUSH
                     // Toast.makeText(this, "Brush", Toast.LENGTH_SHORT).show()
                 }
             }
        }

        btnBrushEraser.setOnClickListener {
             isBaseToolEraser = !isBaseToolEraser
             updateState()
        }

        btnLassoTouch.setOnClickListener {
             isLassoActive = !isLassoActive
             updateState()
        }

        // Initial state update
        // Check current canvas tool to sync
        if (canvasView.currentInpaintTool == AstralCanvasView.InpaintTool.ERASER) {
            isBaseToolEraser = true
        }
        updateState()

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
        val layer = canvasView.getSelectedLayer() as? StylableLayer
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
                    val w = (layer as Layer).getWidth()
                    val h = layer.getHeight()
                    layer.perspectivePoints = floatArrayOf(
                        -w/2f, -h/2f, // TL
                        w/2f, -h/2f,  // TR
                        w/2f, h/2f,   // BR
                        -w/2f, h/2f   // BL
                    )
                }
            }

            // Notify Canvas to show/hide tool handles
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

        val adapter = LayerAdapter(canvasView.getLayers(), {
            // On Item Click? Maybe select
            canvasView.selectLayer(it)
            canvasView.invalidate()
        }, { layerToDelete ->
            // On Delete Click
            android.app.AlertDialog.Builder(this)
                .setTitle("Delete Layer")
                .setMessage("Are you sure you want to delete this layer?")
                .setPositiveButton("Delete") { _, _ ->
                    canvasView.removeLayer(layerToDelete)
                    showLayerMenu() // Refresh menu
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
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
    private fun applyStyleToLayer(layer: TextLayer, style: StyleManager.StyleModel) {
        com.astral.typer.utils.UndoManager.saveState(canvasView.getLayers())

        layer.color = style.color
        layer.fontSize = style.fontSize
        layer.fontPath = style.fontPath

        var fontResolved = false
        if (style.fontPath != null) {
            val found = FontManager.getStandardFonts(this).find { it.name == style.fontPath }
                ?: FontManager.getCustomFonts(this).find { it.path == style.fontPath }

            if (found != null) {
                layer.typeface = found.typeface
                fontResolved = true
            }
        }

        if (!fontResolved) {
            layer.typeface = Typeface.DEFAULT
        }
        layer.opacity = style.opacity
        layer.shadowColor = style.shadowColor
        layer.shadowRadius = style.shadowRadius
        layer.shadowDx = style.shadowDx
        layer.shadowDy = style.shadowDy
        layer.isMotionShadow = style.isMotionShadow
        layer.motionShadowAngle = style.motionAngle
        layer.motionShadowDistance = style.motionDist
        layer.isGradient = style.isGradient
        layer.gradientStartColor = style.gradientStart
        layer.gradientEndColor = style.gradientEnd
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

        layer.isPerspective = style.isPerspective
        layer.perspectivePoints = style.perspectivePoints?.clone()
        layer.isWarp = style.isWarp
        layer.warpRows = style.warpRows
        layer.warpCols = style.warpCols
        layer.warpMesh = style.warpMesh?.clone()
        if (layer.isWarp) {
            layer.updateDenseWarpMesh()
        }

        // Formatting
        if (style.textAlign >= 0 && style.textAlign < Layout.Alignment.values().size) {
            layer.textAlign = Layout.Alignment.values()[style.textAlign]
        }
        layer.isJustified = style.isJustified

        // Apply formatting spans from style
        val text = layer.text
        // Remove existing formatting spans
        val existingStyle = text.getSpans(0, text.length, StyleSpan::class.java)
        for (s in existingStyle) text.removeSpan(s)
        val existingUnder = text.getSpans(0, text.length, UnderlineSpan::class.java)
        for (s in existingUnder) text.removeSpan(s)
        val existingStrike = text.getSpans(0, text.length, StrikethroughSpan::class.java)
        for (s in existingStrike) text.removeSpan(s)

        // Apply new spans to the WHOLE text
        if (style.isBold) text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (style.isItalic) text.setSpan(StyleSpan(Typeface.ITALIC), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (style.isUnderline) text.setSpan(UnderlineSpan(), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (style.isStrike) text.setSpan(StrikethroughSpan(), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        canvasView.invalidate()
    }

    private fun showStyleMenu() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() as? TextLayer

        // Add "Save Current Style" button
        if (layer != null) {
            val btnSaveStyle = android.widget.Button(this).apply {
                text = "Save Current Style"
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.DKGRAY)
                    cornerRadius = dpToPx(8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(16,0,16,16)
                }
                setOnClickListener {
                    saveCurrentStyle(layer)
                }
            }
            container.addView(btnSaveStyle)
        }

        val saved = com.astral.typer.utils.StyleManager.getSavedStyles()
        if (saved.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No Saved Styles"
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(0, 32, 0, 0)
            })
        } else {
            val recyclerView = androidx.recyclerview.widget.RecyclerView(this).apply {
                layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@EditorActivity, 3)
                setPadding(8, 8, 8, 8)
            }
            container.addView(recyclerView)

            recyclerView.adapter = StyleAdapter(this, lifecycleScope, saved,
                onApply = { style ->
                    if (layer != null) {
                        applyStyleToLayer(layer, style)
                        Toast.makeText(this, "Style Applied", Toast.LENGTH_SHORT).show()
                    }
                },
                onLongClick = { view, index, style ->
                    val popup = android.widget.PopupMenu(this, view)
                    popup.menu.add("Rename")
                    popup.menu.add("Delete")
                    popup.setOnMenuItemClickListener { item ->
                        when(item.title) {
                            "Rename" -> {
                                val input = EditText(this)
                                input.setText(style.name)
                                android.app.AlertDialog.Builder(this)
                                    .setTitle("Rename Style")
                                    .setView(input)
                                    .setPositiveButton("OK") { _, _ ->
                                        val newName = input.text.toString()
                                        if (newName.isNotBlank()) {
                                            StyleManager.renameStyle(this, index, newName)
                                            showStyleMenu() // Refresh
                                        }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                                true
                            }
                            "Delete" -> {
                                StyleManager.deleteStyle(this, index)
                                showStyleMenu() // Refresh
                                true
                            }
                            else -> false
                        }
                    }
                    popup.show()
                }
            )
        }
    }

    private fun showInsertMenu() {
        binding.bottomMenuContainer.visibility = View.VISIBLE
        binding.menuInsert.visibility = View.VISIBLE
        binding.menuProperties.visibility = View.GONE
    }

    private fun showShapePicker() {
        val dialog = android.app.AlertDialog.Builder(this)
        dialog.setTitle("Pick a Shape")

        val shapes = assets.list("shapes")?.filter { it.endsWith(".svg") } ?: emptyList()
        if (shapes.isEmpty()) {
            Toast.makeText(this, "No shapes found", Toast.LENGTH_SHORT).show()
            return
        }

        val grid = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@EditorActivity, 3)
            setPadding(16, 16, 16, 16)
        }

        val alertDialog = dialog.setView(grid).create()

        grid.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val iv = android.widget.ImageView(this@EditorActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(dpToPx(80), dpToPx(80))
                    setPadding(16, 16, 16, 16)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(iv) {}
            }
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val shape = shapes[position]
                val iv = holder.itemView as android.widget.ImageView
                try {
                    val stream = assets.open("shapes/$shape")
                    val svg = com.caverock.androidsvg.SVG.getFromInputStream(stream)
                    stream.close()
                    val bmp = android.graphics.Bitmap.createBitmap(dpToPx(80), dpToPx(80), android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    svg.renderToCanvas(canvas)
                    iv.setImageBitmap(bmp)
                } catch(e: Exception) {}
                iv.setOnClickListener {
                    canvasView.addShapeLayer("shapes/$shape")
                    alertDialog.dismiss()
                }
            }
            override fun getItemCount() = shapes.size
        }

        alertDialog.show()
    }

    private fun showPropertiesMenu() {
        binding.bottomMenuContainer.visibility = View.VISIBLE
        binding.menuInsert.visibility = View.GONE
        binding.menuProperties.visibility = View.VISIBLE

        val layer = canvasView.getSelectedLayer()
        if (layer is com.astral.typer.models.BrushLayer) {
            binding.btnPropBrush.visibility = View.VISIBLE
            binding.btnPropColor.visibility = View.VISIBLE
            binding.btnPropErase.visibility = View.VISIBLE

            binding.btnPropQuickEdit.visibility = View.GONE
            binding.btnPropStyle.visibility = View.GONE
            binding.btnPropFont.visibility = View.GONE
            binding.btnPropFormat.visibility = View.GONE
            binding.btnPropSpacing.visibility = View.GONE
            binding.btnPropStroke.visibility = View.GONE
            binding.btnPropDoubleStroke.visibility = View.GONE
            binding.btnPropShadow.visibility = View.GONE
            binding.btnPropGradation.visibility = View.GONE
            binding.btnPropEffect.visibility = View.GONE
            binding.btnPropTexture.visibility = View.GONE
            binding.btnPropOpacity.visibility = View.GONE
            binding.btnPropPerspective.visibility = View.GONE
            binding.btnPropWarp.visibility = View.GONE
            binding.btnPropPuppetWarp.visibility = View.GONE
        } else if (layer is ImageLayer) {
            binding.btnPropBrush.visibility = View.GONE
            binding.btnPropQuickEdit.visibility = View.GONE
            binding.btnPropStyle.visibility = View.GONE
            binding.btnPropFont.visibility = View.GONE
            binding.btnPropFormat.visibility = View.GONE
            binding.btnPropColor.visibility = View.GONE
            binding.btnPropSpacing.visibility = View.GONE
            binding.btnPropStroke.visibility = View.GONE
            binding.btnPropDoubleStroke.visibility = View.GONE
            binding.btnPropShadow.visibility = View.GONE
            binding.btnPropGradation.visibility = View.GONE
            binding.btnPropEffect.visibility = View.GONE
            binding.btnPropTexture.visibility = View.GONE

            binding.btnPropOpacity.visibility = View.VISIBLE
            binding.btnPropErase.visibility = View.VISIBLE
            binding.btnPropPerspective.visibility = View.VISIBLE
            binding.btnPropWarp.visibility = View.VISIBLE
            binding.btnPropPuppetWarp.visibility = View.GONE
        } else if (layer is ShapeLayer) {
            binding.btnPropBrush.visibility = View.GONE
            binding.btnPropQuickEdit.visibility = View.GONE
            binding.btnPropStyle.visibility = View.GONE
            binding.btnPropFont.visibility = View.GONE
            binding.btnPropFormat.visibility = View.GONE
            binding.btnPropSpacing.visibility = View.GONE

            // Ensure others are visible
            binding.btnPropColor.visibility = View.VISIBLE
            binding.btnPropStroke.visibility = View.VISIBLE
            binding.btnPropDoubleStroke.visibility = View.VISIBLE
            binding.btnPropShadow.visibility = View.VISIBLE
            binding.btnPropGradation.visibility = View.VISIBLE
            binding.btnPropEffect.visibility = View.VISIBLE
            binding.btnPropTexture.visibility = View.VISIBLE
            binding.btnPropOpacity.visibility = View.VISIBLE
            binding.btnPropErase.visibility = View.VISIBLE
            binding.btnPropPerspective.visibility = View.VISIBLE
            binding.btnPropWarp.visibility = View.VISIBLE
            binding.btnPropPuppetWarp.visibility = View.GONE
        } else {
            binding.btnPropBrush.visibility = View.GONE
            binding.btnPropQuickEdit.visibility = View.VISIBLE
            binding.btnPropStyle.visibility = View.VISIBLE
            binding.btnPropFont.visibility = View.VISIBLE
            binding.btnPropFormat.visibility = View.VISIBLE
            binding.btnPropColor.visibility = View.VISIBLE
            binding.btnPropSpacing.visibility = View.VISIBLE
            binding.btnPropStroke.visibility = View.VISIBLE
            binding.btnPropDoubleStroke.visibility = View.VISIBLE
            binding.btnPropShadow.visibility = View.VISIBLE
            binding.btnPropGradation.visibility = View.VISIBLE
            binding.btnPropEffect.visibility = View.VISIBLE
            binding.btnPropTexture.visibility = View.VISIBLE
            binding.btnPropOpacity.visibility = View.VISIBLE
            binding.btnPropErase.visibility = View.VISIBLE
            binding.btnPropPerspective.visibility = View.VISIBLE
            binding.btnPropWarp.visibility = View.VISIBLE
            binding.btnPropPuppetWarp.visibility = View.VISIBLE
        }

        binding.btnPropPuppetWarp.visibility = if (layer is TextLayer) View.VISIBLE else View.GONE
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

        // Allow deselection again
        canvasView.preventDeselection = false

        // If exiting perspective menu, ensure mode is off
        if (currentMenuType == "PERSPECTIVE") {
            togglePerspectiveMode(false)
        }
        if (currentMenuType == "WARP") {
            toggleWarpMode(false)
        }
        if (currentMenuType == "PUPPET_WARP") {
            togglePuppetWarpMode(false)
        }
        if (currentMenuType == "ERASE") {
            canvasView.setEraseLayerMode(false)
        }
        if (currentMenuType == "GRADATION") {
            canvasView.setGradationMode(false)
        }

        if (currentMenuType == "TYPER") {
            exitTyperMode()
        }

        currentMenuType = null
    }

    private fun toggleMenu(type: String, showAction: () -> Unit) {
        if (currentMenuType == type && binding.propertyDetailContainer.visibility == View.VISIBLE) {
            hidePropertyDetail()
        } else {
            // Switching menus
            if (currentMenuType == "TYPER" && type != "TYPER") {
                toggleTyperMode()
            }
            if (currentMenuType == "PERSPECTIVE" && type != "PERSPECTIVE") {
                togglePerspectiveMode(false)
            }
            if (currentMenuType == "WARP" && type != "WARP") {
                toggleWarpMode(false)
            }
            if (currentMenuType == "PUPPET_WARP" && type != "PUPPET_WARP") {
                togglePuppetWarpMode(false)
            }
            if (currentMenuType == "ERASE" && type != "ERASE") {
                canvasView.setEraseLayerMode(false)
            }
            if (currentMenuType == "GRADATION" && type != "GRADATION") {
                canvasView.setGradationMode(false)
            }
            // Enable prevent deselection for property menus
            if (type != "QUICK_EDIT") {
                canvasView.preventDeselection = true
            }
            showAction()
            currentMenuType = type
        }
    }

    private var isTyperModeActive = false
    private var lastTyperClickTime = 0L

    private fun toggleTyperMode() {
        val now = System.currentTimeMillis()
        if (now - lastTyperClickTime < 500) return
        lastTyperClickTime = now

        if (isTyperModeActive) {
            exitTyperMode()
        } else {
            enterTyperMode()
        }
    }

    private fun enterTyperMode() {
        if (isTyperModeActive) return
        isTyperModeActive = true

        // Enter Typer Mode
        canvasView.setTyperMode(true)
        binding.bottomMenuContainer.visibility = View.GONE
        hidePropertyDetail()
        showTyperMenu()
        currentMenuType = "TYPER"
        // Highlight icon
        binding.btnTopTyper.setColorFilter(Color.CYAN)
    }

    // --- TYPER MENU ---
    private fun showTyperMenu() {
        if (typerPopup != null && typerPopup!!.isShowing) return

        val popupView = layoutInflater.inflate(R.layout.popup_typer, null)
        // Focusable = false to allow interaction with canvas (outside touches pass through)
        typerPopup = android.widget.PopupWindow(popupView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        typerPopup?.elevation = 20f
        // Prevent dismissal on outside touch, but we need to manage focus manually for typing
        typerPopup?.isOutsideTouchable = false
        // Using ColorDrawable fixes issues with Context Menu not showing (Paste).
        // Also helps with robust touch handling.
        typerPopup?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        typerPopup?.inputMethodMode = android.widget.PopupWindow.INPUT_METHOD_NEEDED
        typerPopup?.softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        typerPopup?.isClippingEnabled = false

        // Prevent dismissal when touching outside (especially when focusable is true)
        typerPopup?.setTouchInterceptor { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                // Consume outside touch to prevent dismissal
                true
            } else {
                false
            }
        }

        // Show Popup at Bottom
        typerPopup?.showAtLocation(binding.root, Gravity.BOTTOM, 0, 0)

        val btnImport = popupView.findViewById<android.widget.Button>(R.id.btnImportTxt)
        val btnDetect = popupView.findViewById<android.widget.Button>(R.id.btnDetectBubbles)
        val btnPaste = popupView.findViewById<android.widget.Button>(R.id.btnPasteText)
        val recycler = popupView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerTyperText)
        val tvWarning = popupView.findViewById<TextView>(R.id.tvWarning)

        // Add Floating Tools Sidebar
        val toolsView = layoutInflater.inflate(R.layout.layout_typer_tools, binding.canvasContainer, false)
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            marginStart = dpToPx(16)
            topMargin = dpToPx(16)
        }
        binding.canvasContainer.addView(toolsView, params)
        // Store reference to remove later
        toolsView.tag = "TYPER_TOOLS"

        // Tools Logic
        val btnHand = toolsView.findViewById<android.widget.ImageView>(R.id.btnToolHand)
        val btnRect = toolsView.findViewById<android.widget.ImageView>(R.id.btnToolRect)
        val btnCircle = toolsView.findViewById<android.widget.ImageView>(R.id.btnToolCircle)
        val btnLasso = toolsView.findViewById<android.widget.ImageView>(R.id.btnToolLasso)
        val btnEraser = toolsView.findViewById<android.widget.ImageView>(R.id.btnToolEraser)

        fun updateToolUI(tool: AstralCanvasView.TyperTool) {
            canvasView.currentTyperTool = tool
            btnHand.setColorFilter(if (tool == AstralCanvasView.TyperTool.HAND) Color.CYAN else Color.WHITE)
            btnRect.setColorFilter(if (tool == AstralCanvasView.TyperTool.RECT) Color.CYAN else Color.WHITE)
            btnCircle.setColorFilter(if (tool == AstralCanvasView.TyperTool.CIRCLE) Color.CYAN else Color.WHITE)
            btnLasso.setColorFilter(if (tool == AstralCanvasView.TyperTool.LASSO) Color.CYAN else Color.WHITE)
            btnEraser.setColorFilter(if (tool == AstralCanvasView.TyperTool.ERASER) Color.CYAN else Color.WHITE)
        }

        btnHand.setOnClickListener { updateToolUI(AstralCanvasView.TyperTool.HAND) }
        btnRect.setOnClickListener { updateToolUI(AstralCanvasView.TyperTool.RECT) }
        btnCircle.setOnClickListener { updateToolUI(AstralCanvasView.TyperTool.CIRCLE) }
        btnLasso.setOnClickListener { updateToolUI(AstralCanvasView.TyperTool.LASSO) }
        btnEraser.setOnClickListener { updateToolUI(AstralCanvasView.TyperTool.ERASER) }

        updateToolUI(AstralCanvasView.TyperTool.HAND)

        recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        // Load Styles Check
        val styles = StyleManager.getSavedStyles()
        if (styles.isEmpty()) {
            tvWarning.visibility = View.VISIBLE
        } else {
            tvWarning.visibility = View.GONE
        }

        // Init Adapter (Use stored lines)
        val styleModels = styles
        typerAdapter = TyperTextAdapter(this, typerTextLines, styleModels) { _, _ ->
            // Selection updated
        }
        recycler.adapter = typerAdapter

        btnImport.setOnClickListener {
            importTxtLauncher.launch("text/plain")
        }

        if (!bubbleProcessor.isModelAvailable()) {
            btnDetect.isEnabled = false
            btnDetect.alpha = 0.5f
            btnDetect.text = "Model Missing"
        } else {
            btnDetect.setOnClickListener {
                 detectBubbles()
            }
        }

        btnPaste.setOnClickListener {
            showPasteDialog()
        }
    }

    private fun showPasteDialog() {
        val dialog = androidx.appcompat.app.AppCompatDialog(this)
        dialog.supportRequestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        // Ensure keyboard resizes the dialog
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val view = layoutInflater.inflate(R.layout.dialog_paste_input, null)
        dialog.setContentView(view)

        val etInput = view.findViewById<EditText>(R.id.etPasteInputDialog)
        val btnParse = view.findViewById<android.widget.Button>(R.id.btnParsePasteDialog)

        btnParse.setOnClickListener {
            val text = etInput.text.toString()
            if (text.isNotBlank()) {
                val lines = text.lines().filter { it.isNotBlank() }
                updateTyperList(lines)
                dialog.dismiss()
                Toast.makeText(this, "Parsed ${lines.size} lines", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter text", Toast.LENGTH_SHORT).show()
            }
        }

        // Set Dialog width
        dialog.window?.setLayout(dpToPx(320), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        dialog.show()

        // Focus and Keyboard
        etInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun detectTextForInpaint() {
        val bg = canvasView.getBackgroundImage()
        if (bg == null) return

        loadingDialog?.show()
        lifecycleScope.launch {
            // Class 1 (text_bubble) and 2 (text_free)
            // Use boxScale 1.0f to avoid shrinking the mask (we want to cover the text)
            val rects = bubbleProcessor.detect(bg, setOf(1L, 2L), 1.0f)
            withContext(Dispatchers.Main) {
                loadingDialog?.dismiss()
                if (rects.isNotEmpty()) {
                    canvasView.addInpaintMask(rects)
                    Toast.makeText(this@EditorActivity, "Added ${rects.size} text masks", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@EditorActivity, "No text detected", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exitTyperMode() {
        // Must set flag FALSE first so OnDismissListener doesn't respawn it
        isTyperModeActive = false

        if (typerPopup != null) {
            typerPopup?.dismiss()
            typerPopup = null
        }

        // Remove tools sidebar
        val toolsView = binding.canvasContainer.findViewWithTag<View>("TYPER_TOOLS")
        if (toolsView != null) {
            binding.canvasContainer.removeView(toolsView)
        }

        canvasView.setTyperMode(false)
        canvasView.setDetectedBubbles(emptyList())

        // Reset UI
        binding.bottomMenuContainer.visibility = View.VISIBLE
        binding.btnTopTyper.setColorFilter(Color.WHITE)

        if (currentMenuType == "TYPER") {
            currentMenuType = null
        }
    }

    private fun updateTyperList(lines: List<String>) {
        typerTextLines = lines
        val styles = StyleManager.getSavedStyles()
        typerAdapter = TyperTextAdapter(this, typerTextLines, styles) { _, _ -> }

        if (typerPopup != null && typerPopup!!.isShowing) {
             val recycler = typerPopup!!.contentView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerTyperText)
             recycler.adapter = typerAdapter
        }
    }

    private fun detectBubbles() {
        val bg = canvasView.getBackgroundImage()
        if (bg == null) {
            Toast.makeText(this, "No image to detect", Toast.LENGTH_SHORT).show()
            return
        }

        // User Request: Center loading animation and ensure it's not covered by Popup.
        // Use a Dialog to ensure z-order above PopupWindow.
        loadingDialog?.show()

        lifecycleScope.launch {
            // Increase boxScale to 0.90 as requested
            val rects = bubbleProcessor.detect(bg, boxScale = 0.90f)
            withContext(Dispatchers.Main) {
                loadingDialog?.dismiss()

                if (rects.isNotEmpty()) {
                    val bubbles = rects.map { com.astral.typer.views.AstralCanvasView.TyperBubble(it, true) }
                    canvasView.setDetectedBubbles(bubbles)
                    Toast.makeText(this@EditorActivity, "Detected ${rects.size} bubbles", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@EditorActivity, "No bubbles detected", Toast.LENGTH_SHORT).show()
                }
            }
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
        } else if (span is ForegroundColorSpan) {
            val existing = et.editableText.getSpans(actualStart, actualEnd, ForegroundColorSpan::class.java)
            for (s in existing) et.editableText.removeSpan(s)
            et.editableText.setSpan(span, actualStart, actualEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (span is CustomTypefaceSpan) {
            val existing = et.editableText.getSpans(actualStart, actualEnd, CustomTypefaceSpan::class.java)
            for (s in existing) et.editableText.removeSpan(s)
            et.editableText.setSpan(span, actualStart, actualEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (span is android.text.style.AbsoluteSizeSpan) {
            val existing = et.editableText.getSpans(actualStart, actualEnd, android.text.style.AbsoluteSizeSpan::class.java)
            for (s in existing) et.editableText.removeSpan(s)
            et.editableText.setSpan(span, actualStart, actualEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (span is com.astral.typer.utils.LetterSpacingSpan) {
            val existing = et.editableText.getSpans(actualStart, actualEnd, com.astral.typer.utils.LetterSpacingSpan::class.java)
            for (s in existing) et.editableText.removeSpan(s)
            et.editableText.setSpan(span, actualStart, actualEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            // For other spans just apply (replace)
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

        // Move cursor to end
        activeEditText?.let { et ->
            et.setSelection(et.text.length)
        }

        val toolbarScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 7f // 7 items
            layoutParams = FrameLayout.LayoutParams(
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
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                // Use weight to distribute evenly
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(50), 1f)
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

        // 6. Shape Toggle (Oval/Rect)
        addIcon(if (layer.isOval) R.drawable.ic_shape_oval else R.drawable.ic_crop_square) { view ->
            layer.isOval = !layer.isOval
            (view as android.widget.ImageView).setImageResource(if (layer.isOval) R.drawable.ic_shape_oval else R.drawable.ic_crop_square)
            canvasView.invalidate()
        }

        // 7. Done (Check)
        addIcon(R.drawable.ic_check) {
            hidePropertyDetail()
            showPropertiesMenu()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        }

        toolbarScroll.addView(toolbar)
        container.addView(toolbarScroll)
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
                                         val fontPath = if (font.isCustom) font.path else font.name
                                         applySpanToSelection(CustomTypefaceSpan(font.typeface, fontPath))
                                    } else {
                                        layer.typeface = font.typeface
                                        layer.fontPath = if (font.isCustom) font.path else font.name // Save Identifier
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
                                                     val fontPath = if (font.isCustom) font.path else font.name
                                                     applySpanToSelection(CustomTypefaceSpan(font.typeface, fontPath))
                                                 } else {
                                                     layer.typeface = font.typeface
                                                     layer.fontPath = if (font.isCustom) font.path else font.name
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
        val layer = canvasView.getSelectedLayer() ?: return
        if (layer !is TextLayer && layer !is com.astral.typer.models.ShapeLayer) return

        if (layer is TextLayer) {
            container.addView(createInputView(layer, false))
        }

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
                            if (layer is TextLayer) {
                                layer.color = color; layer.isGradient = false
                            } else if (layer is com.astral.typer.models.ShapeLayer) {
                                layer.color = color; layer.isGradient = false
                            }
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
                val layerColor = if (layer is TextLayer) (layer as TextLayer).color else if (layer is com.astral.typer.models.ShapeLayer) (layer as com.astral.typer.models.ShapeLayer).color else Color.BLACK
                ColorPickerHelper.showColorPickerDialog(this@EditorActivity, layerColor) { color ->
                    val et = activeEditText
                    if (et != null && et.selectionStart != et.selectionEnd) {
                        applySpanToSelection(ForegroundColorSpan(color))
                    } else {
                        // Apply Color and Disable Gradient
                        if (layer is TextLayer) {
                            (layer as TextLayer).color = color; (layer as TextLayer).isGradient = false
                        } else if (layer is com.astral.typer.models.ShapeLayer) {
                            (layer as com.astral.typer.models.ShapeLayer).color = color; (layer as com.astral.typer.models.ShapeLayer).isGradient = false
                        }
                        canvasView.invalidate()
                    }
                    showColorPicker()
                }
            }
        }
        list.addView(btnPalette)

        // Add saved colors view using helper
        val paletteView = ColorPickerHelper.createPaletteView(
            this,
            { color ->
                val et = activeEditText
                if (et != null && et.selectionStart != et.selectionEnd) {
                    applySpanToSelection(ForegroundColorSpan(color))
                } else {
                    val layerColor = if (layer is TextLayer) (layer as TextLayer).color else if (layer is com.astral.typer.models.ShapeLayer) (layer as com.astral.typer.models.ShapeLayer).color else 0
                    val layerIsGradient = if (layer is TextLayer) (layer as TextLayer).isGradient else if (layer is com.astral.typer.models.ShapeLayer) (layer as com.astral.typer.models.ShapeLayer).isGradient else false

                    if (layerColor == color && !layerIsGradient) {
                        // Already active and solid.
                    } else {
                        if (layer is TextLayer) {
                            (layer as TextLayer).color = color; (layer as TextLayer).isGradient = false
                        } else if (layer is com.astral.typer.models.ShapeLayer) {
                            (layer as com.astral.typer.models.ShapeLayer).color = color; (layer as com.astral.typer.models.ShapeLayer).isGradient = false
                        }
                        canvasView.invalidate()
                    }
                }
                showColorPicker()
            },
            null, // No add button here? Or keep it? The helper has logic for saved colors internally.
            if (layer is TextLayer) (layer as TextLayer).color else if (layer is com.astral.typer.models.ShapeLayer) (layer as com.astral.typer.models.ShapeLayer).color else Color.BLACK // Selected color
        )
        list.addView(paletteView)

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

        // Fix: Wrap Formatting View in ScrollView
        val formattingView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(createFormattingTab(layer))
        }

        // Fix: Wrap Size View in ScrollView
        val sizeView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(createSizeTab(layer))
        }

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

        // Letter Spacing
        val letterSpacingRow = createControl("Letter Spacing", String.format("%.2f", layer.letterSpacing), "LETTER_SPACING",
            onMinus = {
                val et = activeEditText
                if (et != null && et.selectionStart != et.selectionEnd) {
                    val currentSpacing = (et.editableText.getSpans(et.selectionStart, et.selectionEnd, com.astral.typer.utils.LetterSpacingSpan::class.java).firstOrNull()?.spacing ?: layer.letterSpacing) - 0.01f
                    applySpanToSelection(com.astral.typer.utils.LetterSpacingSpan(currentSpacing))
                    binding.propertyDetailContainer.findViewWithTag<TextView>("LETTER_SPACING")?.text = String.format("%.2f", currentSpacing)
                } else {
                    layer.letterSpacing -= 0.01f
                    canvasView.invalidate()
                    binding.propertyDetailContainer.findViewWithTag<TextView>("LETTER_SPACING")?.text = String.format("%.2f", layer.letterSpacing)
                }
            },
            onPlus = {
                val et = activeEditText
                if (et != null && et.selectionStart != et.selectionEnd) {
                    val currentSpacing = (et.editableText.getSpans(et.selectionStart, et.selectionEnd, com.astral.typer.utils.LetterSpacingSpan::class.java).firstOrNull()?.spacing ?: layer.letterSpacing) + 0.01f
                    applySpanToSelection(com.astral.typer.utils.LetterSpacingSpan(currentSpacing))
                    binding.propertyDetailContainer.findViewWithTag<TextView>("LETTER_SPACING")?.text = String.format("%.2f", currentSpacing)
                } else {
                    layer.letterSpacing += 0.01f
                    canvasView.invalidate()
                    binding.propertyDetailContainer.findViewWithTag<TextView>("LETTER_SPACING")?.text = String.format("%.2f", layer.letterSpacing)
                }
            }
        )
        layout.addView(letterSpacingRow)

        // Line Spacing
        val lineSpacingRow = createControl("Line Spacing", "${layer.lineSpacing.toInt()}", "LINE_SPACING",
            onMinus = {
                layer.lineSpacing -= 5f
                canvasView.invalidate()
                binding.propertyDetailContainer.findViewWithTag<TextView>("LINE_SPACING")?.text = "${layer.lineSpacing.toInt()}"
            },
            onPlus = {
                layer.lineSpacing += 5f
                canvasView.invalidate()
                binding.propertyDetailContainer.findViewWithTag<TextView>("LINE_SPACING")?.text = "${layer.lineSpacing.toInt()}"
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

        val styleButtons = mutableListOf<TextView>()

        // Helper to update visual state of style buttons
        fun updateStyleButtons() {
            styleButtons.forEach { btn ->
                val type = btn.tag as String
                val isActive = when(type) {
                    "B" -> {
                        val et = activeEditText
                        if (et != null && et.text.isNotEmpty()) {
                            val spans = et.text.getSpans(0, et.text.length, StyleSpan::class.java)
                            spans.any { it.style == Typeface.BOLD || it.style == Typeface.BOLD_ITALIC }
                        } else layer.typeface.isBold
                    }
                    "I" -> {
                        val et = activeEditText
                        if (et != null && et.text.isNotEmpty()) {
                            val spans = et.text.getSpans(0, et.text.length, StyleSpan::class.java)
                            spans.any { it.style == Typeface.ITALIC || it.style == Typeface.BOLD_ITALIC }
                        } else layer.typeface.isItalic
                    }
                    "U" -> {
                        val et = activeEditText
                        if (et != null && et.text.isNotEmpty()) {
                            val spans = et.text.getSpans(0, et.text.length, UnderlineSpan::class.java)
                            spans.isNotEmpty()
                        } else false
                    }
                    "S" -> {
                        val et = activeEditText
                        if (et != null && et.text.isNotEmpty()) {
                            val spans = et.text.getSpans(0, et.text.length, StrikethroughSpan::class.java)
                            spans.isNotEmpty()
                        } else false
                    }
                    else -> false
                }
                if (isActive) {
                    btn.background = GradientDrawable().apply {
                        setStroke(dpToPx(1), Color.WHITE)
                        cornerRadius = dpToPx(4).toFloat()
                    }
                } else {
                    btn.background = null
                }
            }
        }

        fun addStyleBtn(label: String, type: String, spanProvider: () -> Any) {
            val btn = TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 16)
                this.tag = type
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setOnClickListener {
                    applySpanToSelection(spanProvider())
                    updateStyleButtons()
                }
            }
            styleButtons.add(btn)
            stylesRow.addView(btn)
        }

        addStyleBtn("B", "B") { StyleSpan(Typeface.BOLD) }
        addStyleBtn("I", "I") { StyleSpan(Typeface.ITALIC) }
        addStyleBtn("U", "U") { UnderlineSpan() }
        addStyleBtn("S", "S") { StrikethroughSpan() }

        updateStyleButtons()
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

        val alignButtons = mutableListOf<View>()

        fun updateAlignButtons() {
            alignButtons.forEach { btn ->
                val type = btn.tag.toString()
                var isActive = false
                if (type == "JUSTIFY") {
                    isActive = layer.isJustified
                } else {
                    val align = when(type) {
                        "LEFT" -> Layout.Alignment.ALIGN_NORMAL
                        "CENTER" -> Layout.Alignment.ALIGN_CENTER
                        "RIGHT" -> Layout.Alignment.ALIGN_OPPOSITE
                        else -> Layout.Alignment.ALIGN_NORMAL
                    }
                    isActive = (layer.textAlign == align && !layer.isJustified)
                }

                if (isActive) {
                    btn.background = GradientDrawable().apply {
                        setStroke(dpToPx(1), Color.WHITE)
                        cornerRadius = dpToPx(4).toFloat()
                    }
                } else {
                    btn.background = null
                }
            }
        }

        fun addAlignBtn(iconRes: Int, tag: String, onClick: () -> Unit) {
            val btn = android.widget.ImageView(this).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
                setPadding(0, 16, 0, 16)
                this.tag = tag
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setOnClickListener {
                    onClick()
                    updateAlignButtons()
                }
            }
            alignButtons.add(btn)
            alignRow.addView(btn)
        }

        addAlignBtn(R.drawable.ic_format_align_left, "LEFT") {
            layer.textAlign = Layout.Alignment.ALIGN_NORMAL
            layer.isJustified = false
            canvasView.invalidate()
        }
        addAlignBtn(R.drawable.ic_format_align_center, "CENTER") {
            layer.textAlign = Layout.Alignment.ALIGN_CENTER
            layer.isJustified = false
            canvasView.invalidate()
        }
        addAlignBtn(R.drawable.ic_format_align_right, "RIGHT") {
            layer.textAlign = Layout.Alignment.ALIGN_OPPOSITE
            layer.isJustified = false
            canvasView.invalidate()
        }
        addAlignBtn(R.drawable.ic_format_align_justify, "JUSTIFY") {
            layer.textAlign = Layout.Alignment.ALIGN_NORMAL
            layer.isJustified = true
            canvasView.invalidate()
        }
        updateAlignButtons()

        // Transform Row (Mirror)
        val transformRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 16, 0, 0)
        }

        fun addMirrorBtn(iconRes: Int, onClick: () -> Unit) {
             val btn = android.widget.ImageView(this).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
                setPadding(0, 16, 0, 16)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { onClick() }
            }
            transformRow.addView(btn)
        }

        addMirrorBtn(R.drawable.ic_mirror_horizontal) {
            layer.scaleX *= -1
            canvasView.invalidate()
        }
        addMirrorBtn(R.drawable.ic_mirror_vertical) {
            layer.scaleY *= -1
            canvasView.invalidate()
        }

        // Shape Toggle (Oval/Rect)
        val btnShape = android.widget.ImageView(this).apply {
            setImageResource(if (layer.isOval) R.drawable.ic_shape_oval else R.drawable.ic_crop_square)
            setColorFilter(Color.WHITE)
            setPadding(0, 16, 0, 16)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                layer.isOval = !layer.isOval
                setImageResource(if (layer.isOval) R.drawable.ic_shape_oval else R.drawable.ic_crop_square)
                canvasView.invalidate()
            }
        }
        transformRow.addView(btnShape)

        layout.addView(alignRow) // Ensure Align row is added first
        layout.addView(transformRow)

        return layout
    }

    private fun createControl(label: String, valueStr: String, tag: String, onMinus: () -> Unit, onPlus: () -> Unit): View {
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
            this.tag = "MINUS_BTN"
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
            this.tag = tag // Set Tag for Sync
            setTextColor(Color.CYAN)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(80), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val btnPlus = TextView(this).apply {
            this.tag = "PLUS_BTN"
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

    private fun createSizeTab(layer: TextLayer): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }

        // Text Size
        val textSizeRow = createControl("Text Size", "${layer.fontSize.toInt()} pt", "VAL_TEXT_SIZE",
            onMinus = {
                val et = activeEditText
                if (et != null && et.selectionStart != et.selectionEnd) {
                    val currentSize = (et.editableText.getSpans(et.selectionStart, et.selectionEnd, android.text.style.AbsoluteSizeSpan::class.java).firstOrNull()?.size ?: layer.fontSize.toInt()) - 1
                    applySpanToSelection(android.text.style.AbsoluteSizeSpan(currentSize.coerceAtLeast(10)))
                } else {
                    layer.fontSize = (layer.fontSize - 1).coerceAtLeast(10f)
                    canvasView.invalidate()
                    ((canvasView.parent as? View)?.findViewWithTag<TextView>("VAL_TEXT_SIZE"))?.text = "${layer.fontSize.toInt()} pt"
                }
            },
            onPlus = {
                val et = activeEditText
                if (et != null && et.selectionStart != et.selectionEnd) {
                    val currentSize = (et.editableText.getSpans(et.selectionStart, et.selectionEnd, android.text.style.AbsoluteSizeSpan::class.java).firstOrNull()?.size ?: layer.fontSize.toInt()) + 1
                    applySpanToSelection(android.text.style.AbsoluteSizeSpan(currentSize))
                } else {
                    layer.fontSize += 1
                    canvasView.invalidate()
                    ((canvasView.parent as? View)?.findViewWithTag<TextView>("VAL_TEXT_SIZE"))?.text = "${layer.fontSize.toInt()} pt"
                }
            }
        )
        val tvSizeVal = textSizeRow.findViewWithTag<TextView>("VAL_TEXT_SIZE")
        textSizeRow.findViewWithTag<View>("MINUS_BTN")?.setOnClickListener {
            val et = activeEditText
            if (et != null && et.selectionStart != et.selectionEnd) {
                val currentSize = (et.editableText.getSpans(et.selectionStart, et.selectionEnd, android.text.style.AbsoluteSizeSpan::class.java).firstOrNull()?.size ?: layer.fontSize.toInt()) - 1
                applySpanToSelection(android.text.style.AbsoluteSizeSpan(currentSize.coerceAtLeast(10)))
            } else {
                layer.fontSize = (layer.fontSize - 1).coerceAtLeast(10f)
                canvasView.invalidate()
                tvSizeVal?.text = "${layer.fontSize.toInt()} pt"
            }
        }
        textSizeRow.findViewWithTag<View>("PLUS_BTN")?.setOnClickListener {
            val et = activeEditText
            if (et != null && et.selectionStart != et.selectionEnd) {
                val currentSize = (et.editableText.getSpans(et.selectionStart, et.selectionEnd, android.text.style.AbsoluteSizeSpan::class.java).firstOrNull()?.size ?: layer.fontSize.toInt()) + 1
                applySpanToSelection(android.text.style.AbsoluteSizeSpan(currentSize))
            } else {
                layer.fontSize += 1
                canvasView.invalidate()
                tvSizeVal?.text = "${layer.fontSize.toInt()} pt"
            }
        }
        layout.addView(textSizeRow)

        // Box Scale
        val scaleRow = createControl("Box Scale", "${(layer.scale * 100).toInt()}%", "VAL_BOX_SCALE",
            onMinus = {
                val s = (layer.scale - 0.01f).coerceAtLeast(0.01f)
                layer.scaleX = s
                layer.scaleY = s
                canvasView.invalidate()
                ((canvasView.parent as? View)?.findViewWithTag<TextView>("VAL_BOX_SCALE"))?.text = "${(layer.scale * 100).toInt()}%"
            },
            onPlus = {
                val s = layer.scale + 0.01f
                layer.scaleX = s
                layer.scaleY = s
                canvasView.invalidate()
                ((canvasView.parent as? View)?.findViewWithTag<TextView>("VAL_BOX_SCALE"))?.text = "${(layer.scale * 100).toInt()}%"
            }
        )
        val tvScaleVal = scaleRow.findViewWithTag<TextView>("VAL_BOX_SCALE")
        scaleRow.findViewWithTag<View>("MINUS_BTN")?.setOnClickListener {
            val s = (layer.scale - 0.01f).coerceAtLeast(0.01f)
            layer.scaleX = s
            layer.scaleY = s
            canvasView.invalidate()
            tvScaleVal?.text = "${(layer.scale * 100).toInt()}%"
        }
        scaleRow.findViewWithTag<View>("PLUS_BTN")?.setOnClickListener {
            val s = layer.scale + 0.01f
            layer.scaleX = s
            layer.scaleY = s
            canvasView.invalidate()
            tvScaleVal?.text = "${(layer.scale * 100).toInt()}%"
        }
        layout.addView(scaleRow)

        // Box Width
        val widthVal = layer.boxWidth ?: 0f
        val widthStr = if (widthVal <= 0) "Auto" else "${widthVal.toInt()} pt"
        val widthRow = createControl("Box Width", widthStr, "VAL_BOX_WIDTH",
            onMinus = {
                val w = (layer.boxWidth ?: layer.getWidth()) - 1f
                layer.boxWidth = w.coerceAtLeast(50f)
                canvasView.invalidate()
                ((canvasView.parent as? View)?.findViewWithTag<TextView>("VAL_BOX_WIDTH"))?.text = "${layer.boxWidth!!.toInt()} pt"
            },
            onPlus = {
                 val w = (layer.boxWidth ?: layer.getWidth()) + 1f
                layer.boxWidth = w
                canvasView.invalidate()
                ((canvasView.parent as? View)?.findViewWithTag<TextView>("VAL_BOX_WIDTH"))?.text = "${layer.boxWidth!!.toInt()} pt"
            }
        )
        val tvWidthVal = widthRow.findViewWithTag<TextView>("VAL_BOX_WIDTH")
        widthRow.findViewWithTag<View>("MINUS_BTN")?.setOnClickListener {
            val w = (layer.boxWidth ?: layer.getWidth()) - 1f
            layer.boxWidth = w.coerceAtLeast(50f)
            canvasView.invalidate()
            tvWidthVal?.text = "${layer.boxWidth!!.toInt()} pt"
        }
        widthRow.findViewWithTag<View>("PLUS_BTN")?.setOnClickListener {
            val w = (layer.boxWidth ?: layer.getWidth()) + 1f
            layer.boxWidth = w
            canvasView.invalidate()
            tvWidthVal?.text = "${layer.boxWidth!!.toInt()} pt"
        }
        layout.addView(widthRow)

        // Rotate
        val rotateRow = createControl("Rotate", "${layer.rotation.toInt()}°", "VAL_ROTATE",
            onMinus = {
                layer.rotation -= 1f
                canvasView.invalidate()
                ((canvasView.parent as? View)?.findViewWithTag<TextView>("VAL_ROTATE"))?.text = "${layer.rotation.toInt()}°"
            },
            onPlus = {
                layer.rotation += 1f
                canvasView.invalidate()
                ((canvasView.parent as? View)?.findViewWithTag<TextView>("VAL_ROTATE"))?.text = "${layer.rotation.toInt()}°"
            }
        )
        val tvRotateVal = rotateRow.findViewWithTag<TextView>("VAL_ROTATE")
        rotateRow.findViewWithTag<View>("MINUS_BTN")?.setOnClickListener {
            layer.rotation -= 1f
            canvasView.invalidate()
            tvRotateVal?.text = "${layer.rotation.toInt()}°"
        }
        rotateRow.findViewWithTag<View>("PLUS_BTN")?.setOnClickListener {
            layer.rotation += 1f
            canvasView.invalidate()
            tvRotateVal?.text = "${layer.rotation.toInt()}°"
        }
        layout.addView(rotateRow)

        return layout
    }

    private fun createSlider(label: String, initial: Int, max: Int, onChange: (Int) -> Unit): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0,8,0,8) }
        val tv = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            tag = "SLIDER_LABEL"
        }
        val sb = SeekBar(this).apply {
            this.max = max
            progress = initial
            tag = "SLIDER_BAR"
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
                if (currentColor == color) {
                     onColorPicked(color)
                } else {
                     onColorPicked(color)
                }
            },
            { onPaletteClick(); 0 },
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

        // Eyedropper Button
        val btnEyedropper = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_menu_eyedropper)
            setColorFilter(Color.WHITE)
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply { setColor(Color.DKGRAY); cornerRadius = dpToPx(8).toFloat() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 16, 0)
            }
            setOnClickListener {
                canvasView.setEyedropperMode(true)
                canvasView.onColorPickedListener = { color ->
                    onColorPicked(color)
                    Toast.makeText(context, "Color Picked", Toast.LENGTH_SHORT).show()
                }
                Toast.makeText(context, "Tap canvas to pick", Toast.LENGTH_SHORT).show()
            }
        }
        list.addView(btnEyedropper)

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
        val layer = canvasView.getSelectedLayer() ?: return
        val stylableLayer = layer as? StylableLayer ?: return

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
                setPadding(16, 8, 16, 8)
            }
            val currentShadowRadius = stylableLayer.shadowRadius
            val currentShadowDx = stylableLayer.shadowDx
            val currentShadowDy = stylableLayer.shadowDy

            // Color
            layout.addView(createColorScroll(stylableLayer.shadowColor,
                { c ->
                    if (stylableLayer.shadowColor == c && stylableLayer.shadowRadius > 0) stylableLayer.shadowRadius = 0f
                    else {
                        stylableLayer.shadowColor = c
                        if (stylableLayer.shadowRadius == 0f) stylableLayer.shadowRadius = 10f
                    }
                    canvasView.invalidate()
                },
                {
                    showColorWheelDialogForProperty(stylableLayer.shadowColor) { c ->
                        stylableLayer.shadowColor = c
                        if (stylableLayer.shadowRadius == 0f) stylableLayer.shadowRadius = 10f
                        canvasView.invalidate()
                    }
                }
            ))

            layout.addView(createSlider("Blur Radius", currentShadowRadius.toInt(), 50) {
                stylableLayer.shadowRadius = it.toFloat()
                canvasView.invalidate()
            })
            layout.addView(createSlider("DX", (currentShadowDx + 50).toInt(), 100) {
                stylableLayer.shadowDx = (it - 50).toFloat()
                canvasView.invalidate()
            })
            layout.addView(createSlider("DY", (currentShadowDy + 50).toInt(), 100) {
                stylableLayer.shadowDy = (it - 50).toFloat()
                canvasView.invalidate()
            })
            val btnCenter = android.widget.Button(this@EditorActivity).apply {
                text = "Center"
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.DKGRAY)
                    cornerRadius = dpToPx(8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 16, 0, 16)
                }
                setOnClickListener {
                    stylableLayer.shadowDx = 0f
                    stylableLayer.shadowDy = 0f
                    canvasView.invalidate()
                    showShadowControls()
                }
            }
            layout.addView(btnCenter)
            addView(layout)
        }

        // 2. Motion Shadow
        val motionShadowView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            val layout = LinearLayout(this@EditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 8, 16, 8)
            }
            val currentMShadowDist = stylableLayer.motionShadowDistance
            val currentMShadowAngle = stylableLayer.motionShadowAngle
            val currentMShadowStroke = stylableLayer.isMotionShadowIncludeStroke

            // Color
            layout.addView(createColorScroll(stylableLayer.shadowColor,
                { c ->
                    if (stylableLayer.shadowColor == c && stylableLayer.motionShadowDistance > 0) stylableLayer.motionShadowDistance = 0f
                    else {
                        stylableLayer.shadowColor = c
                        if (stylableLayer.motionShadowDistance == 0f) stylableLayer.motionShadowDistance = 20f
                    }
                    canvasView.invalidate()
                },
                {
                    showColorWheelDialogForProperty(stylableLayer.shadowColor) { c ->
                        stylableLayer.shadowColor = c
                        if (stylableLayer.motionShadowDistance == 0f) stylableLayer.motionShadowDistance = 20f
                        canvasView.invalidate()
                    }
                }
            ))

            // Angle
            val angleLabel = TextView(this@EditorActivity).apply {
                text = "Blur Angle: ${currentMShadowAngle}°"
                setTextColor(Color.WHITE)
            }
            layout.addView(angleLabel)

            val sbAngle = SeekBar(this@EditorActivity).apply {
                max = 360
                progress = currentMShadowAngle
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        stylableLayer.motionShadowAngle = p
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
                text = "Blur Distance: ${currentMShadowDist.toInt()}"
                setTextColor(Color.WHITE)
                setPadding(0, 16, 0, 0)
            }
            layout.addView(distLabel)

            val sbDist = SeekBar(this@EditorActivity).apply {
                max = 200
                progress = currentMShadowDist.toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        stylableLayer.motionShadowDistance = p.toFloat()
                        distLabel.text = "Blur Distance: $p"
                        canvasView.invalidate()
                    }

                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            layout.addView(sbDist)

            // Thickness
            layout.addView(createSlider("Thickness", stylableLayer.motionShadowThickness.toInt(), 20) {
                stylableLayer.motionShadowThickness = it.toFloat()
                canvasView.invalidate()
            })

            // Include Stroke Checkbox
            val cbIncludeStroke = android.widget.CheckBox(this@EditorActivity).apply {
                text = "Include stroke"
                setTextColor(Color.WHITE)
                isChecked = currentMShadowStroke
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.CYAN)
                setOnCheckedChangeListener { _, isChecked ->
                    stylableLayer.isMotionShadowIncludeStroke = isChecked
                    canvasView.invalidate()
                }
            }
            layout.addView(cbIncludeStroke)

            addView(layout)
        }

        fun selectTab(isDrop: Boolean) {
            contentContainer.removeAllViews()
            contentContainer.addView(if (isDrop) dropShadowView else motionShadowView)

            stylableLayer.isMotionShadow = !isDrop
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
        val layer = canvasView.getSelectedLayer() ?: return
        val layerAsText = layer as? TextLayer
        val layerAsShape = layer as? com.astral.typer.models.ShapeLayer
        if (layerAsText == null && layerAsShape == null) return
        val isGradationMode = canvasView.currentModeName() == "GRADATION"
        // Do not auto-apply gradient
        // layer.isGradient = true
        // canvasView.invalidate()

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // Enter Gradation Mode Button
        val btnGradMode = android.widget.Button(this).apply {
            text = if (canvasView.currentModeName() == "GRADATION") "Exit Gradation Mode" else "Enter Gradation Mode"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (canvasView.currentModeName() == "GRADATION") Color.CYAN else Color.DKGRAY)
                cornerRadius = dpToPx(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 16)
            }
            setOnClickListener {
                val isActive = canvasView.currentModeName() == "GRADATION"
                canvasView.setGradationMode(!isActive)
                if (!isActive) {
                    canvasView.pendingGradientStart = if (layerAsText != null) layerAsText.gradientStartColor else layerAsShape!!.gradientStartColor
                    canvasView.pendingGradientEnd = if (layerAsText != null) layerAsText.gradientEndColor else layerAsShape!!.gradientEndColor
                    canvasView.targetGradientText = if (layerAsText != null) layerAsText.isGradientText else layerAsShape!!.isGradientText
                    canvasView.targetGradientStroke = if (layerAsText != null) layerAsText.isGradientStroke else layerAsShape!!.isGradientStroke
                    canvasView.targetGradientShadow = if (layerAsText != null) layerAsText.isGradientShadow else layerAsShape!!.isGradientShadow
                }
                showGradationControls()
            }
        }
        mainLayout.addView(btnGradMode)

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

        val layerIsGradientText = if (layerAsText != null) layerAsText.isGradientText else layerAsShape!!.isGradientText
        val layerIsGradientStroke = if (layerAsText != null) layerAsText.isGradientStroke else layerAsShape!!.isGradientStroke
        val layerIsGradientShadow = if (layerAsText != null) layerAsText.isGradientShadow else layerAsShape!!.isGradientShadow

        togglesLayout.addView(createToggle(if (layerAsShape != null) "Fill" else "Text", if (isGradationMode) canvasView.targetGradientText else layerIsGradientText) { b ->
            if (isGradationMode) canvasView.targetGradientText = b else { if (layerAsText != null) layerAsText.isGradientText = b else layerAsShape!!.isGradientText = b; canvasView.invalidate() }
        })
        togglesLayout.addView(createToggle("Stroke", if (isGradationMode) canvasView.targetGradientStroke else layerIsGradientStroke) { b ->
            if (isGradationMode) canvasView.targetGradientStroke = b else { if (layerAsText != null) layerAsText.isGradientStroke = b else layerAsShape!!.isGradientStroke = b; canvasView.invalidate() }
        })
        togglesLayout.addView(createToggle("Shadow", if (isGradationMode) canvasView.targetGradientShadow else layerIsGradientShadow) { b ->
            if (isGradationMode) canvasView.targetGradientShadow = b else { if (layerAsText != null) layerAsText.isGradientShadow = b else layerAsShape!!.isGradientShadow = b; canvasView.invalidate() }
        })

        mainLayout.addView(togglesLayout)

        // Start Color
        mainLayout.addView(TextView(this).apply { text = "Start Color"; setTextColor(Color.LTGRAY) })
        mainLayout.addView(createColorScroll(if (isGradationMode) canvasView.pendingGradientStart else (if (layerAsText != null) layerAsText.gradientStartColor else layerAsShape!!.gradientStartColor),
             { c ->
                 if (isGradationMode) {
                     canvasView.pendingGradientStart = c
                     canvasView.invalidate()
                 } else {
                     if (layerAsText != null) { layerAsText.gradientStartColor = c; if (!layerAsText.isGradient) layerAsText.isGradient = true }
                     else if (layerAsShape != null) { layerAsShape.gradientStartColor = c; if (!layerAsShape.isGradient) layerAsShape.isGradient = true }
                     canvasView.invalidate()
                 }
                 showGradationControls()
             },
             {
                 val current = if (isGradationMode) canvasView.pendingGradientStart else (if (layerAsText != null) layerAsText.gradientStartColor else layerAsShape!!.gradientStartColor)
                 showColorWheelDialogForProperty(current) { c ->
                     if (isGradationMode) {
                         canvasView.pendingGradientStart = c
                         canvasView.invalidate()
                     } else {
                         if (layerAsText != null) { layerAsText.gradientStartColor = c; if (!layerAsText.isGradient) layerAsText.isGradient = true }
                         else if (layerAsShape != null) { layerAsShape.gradientStartColor = c; if (!layerAsShape.isGradient) layerAsShape.isGradient = true }
                         canvasView.invalidate()
                     }
                     showGradationControls()
                 }
             }
        ))

        // End Color
        mainLayout.addView(TextView(this).apply { text = "End Color"; setTextColor(Color.LTGRAY); setPadding(0,16,0,0) })
        mainLayout.addView(createColorScroll(if (isGradationMode) canvasView.pendingGradientEnd else (if (layerAsText != null) layerAsText.gradientEndColor else layerAsShape!!.gradientEndColor),
             { c ->
                 if (isGradationMode) {
                     canvasView.pendingGradientEnd = c
                     canvasView.invalidate()
                 } else {
                     if (layerAsText != null) { layerAsText.gradientEndColor = c; if (!layerAsText.isGradient) layerAsText.isGradient = true }
                     else if (layerAsShape != null) { layerAsShape.gradientEndColor = c; if (!layerAsShape.isGradient) layerAsShape.isGradient = true }
                     canvasView.invalidate()
                 }
                 showGradationControls()
             },
             {
                 val current = if (isGradationMode) canvasView.pendingGradientEnd else (if (layerAsText != null) layerAsText.gradientEndColor else layerAsShape!!.gradientEndColor)
                 showColorWheelDialogForProperty(current) { c ->
                     if (isGradationMode) {
                         canvasView.pendingGradientEnd = c
                         canvasView.invalidate()
                     } else {
                         if (layerAsText != null) { layerAsText.gradientEndColor = c; if (!layerAsText.isGradient) layerAsText.isGradient = true }
                         else if (layerAsShape != null) { layerAsShape.gradientEndColor = c; if (!layerAsShape.isGradient) layerAsShape.isGradient = true }
                         canvasView.invalidate()
                     }
                     showGradationControls()
                 }
             }
        ))

        // Angle
        if (!isGradationMode) {
            val currentGradAngle = if (layerAsText != null) layerAsText.gradientAngle else layerAsShape!!.gradientAngle
            val angleSlider = createSlider("Gradient Angle: ${currentGradAngle}°", currentGradAngle, 360) {
                 if (layerAsText != null) layerAsText.gradientAngle = it else layerAsShape!!.gradientAngle = it
                 canvasView.invalidate()
            }
            val angleLabel = angleSlider.findViewWithTag<TextView>("SLIDER_LABEL")
            angleSlider.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                    if (layerAsText != null) layerAsText.gradientAngle = p else layerAsShape!!.gradientAngle = p
                    angleLabel?.text = "Gradient Angle: $p°"
                    canvasView.invalidate()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            mainLayout.addView(angleSlider)
        }

        scroll.addView(mainLayout)
        container.addView(scroll)
    }

    private fun showStrokeMenu() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() ?: return
        val stylableLayer = layer as? StylableLayer ?: return

        val mainLayout = LinearLayout(this).apply {
             orientation = LinearLayout.VERTICAL
             layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Color List (Moved to Top)
        val colorList = createColorScroll(stylableLayer.strokeColor,
             { c ->
                 if (stylableLayer.strokeColor == c && stylableLayer.strokeWidth > 0) stylableLayer.strokeWidth = 0f
                 else { stylableLayer.strokeColor = c; if (stylableLayer.strokeWidth == 0f) stylableLayer.strokeWidth = 5f }
                 canvasView.invalidate()
                 showStrokeMenu()
             },
             { showColorWheelDialogForProperty(stylableLayer.strokeColor) { c ->
                 stylableLayer.strokeColor = c; if(stylableLayer.strokeWidth==0f) stylableLayer.strokeWidth=5f
                 canvasView.invalidate()
                 showStrokeMenu()
             } }
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

        val currentStrokeWidth = stylableLayer.strokeWidth
        val tvValue = TextView(this).apply {
            tag = "STROKE_WIDTH_VAL"
            text = "${currentStrokeWidth.toInt()} pt"
            setTextColor(Color.CYAN)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(80), ViewGroup.LayoutParams.WRAP_CONTENT)
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
                stylableLayer.strokeWidth = (stylableLayer.strokeWidth - 1).coerceAtLeast(0f)
                tvValue.text = "${stylableLayer.strokeWidth.toInt()} pt"
                canvasView.invalidate()
            }
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
                stylableLayer.strokeWidth += 1
                tvValue.text = "${stylableLayer.strokeWidth.toInt()} pt"
                canvasView.invalidate()
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
        val layer = canvasView.getSelectedLayer() ?: return
        val stylableLayer = layer as? StylableLayer ?: return

        val sw = stylableLayer.strokeWidth
        if (sw <= 0f) {
             Toast.makeText(this, "Enable Stroke first!", Toast.LENGTH_SHORT).show()
        }

        val container = prepareContainer()
        val mainLayout = LinearLayout(this).apply {
             orientation = LinearLayout.VERTICAL
             layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Color List (Moved to Top)
        val colorList = createColorScroll(stylableLayer.doubleStrokeColor,
             { c ->
                 if (stylableLayer.doubleStrokeColor == c && stylableLayer.doubleStrokeWidth > 0) stylableLayer.doubleStrokeWidth = 0f
                 else { stylableLayer.doubleStrokeColor = c; if (stylableLayer.doubleStrokeWidth == 0f) stylableLayer.doubleStrokeWidth = 5f }
                 canvasView.invalidate()
                 showDoubleStrokeMenu()
             },
             { showColorWheelDialogForProperty(stylableLayer.doubleStrokeColor) { c ->
                 stylableLayer.doubleStrokeColor = c; if(stylableLayer.doubleStrokeWidth==0f) stylableLayer.doubleStrokeWidth=5f
                 canvasView.invalidate()
                 showDoubleStrokeMenu()
             } }
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

        val currentDStrokeWidth = stylableLayer.doubleStrokeWidth
        val tvValue = TextView(this).apply {
            tag = "DBL_STROKE_WIDTH_VAL"
            text = "${currentDStrokeWidth.toInt()} pt"
            setTextColor(Color.CYAN)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(80), ViewGroup.LayoutParams.WRAP_CONTENT)
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
                stylableLayer.doubleStrokeWidth = (stylableLayer.doubleStrokeWidth - 1).coerceAtLeast(0f)
                tvValue.text = "${stylableLayer.doubleStrokeWidth.toInt()} pt"
                canvasView.invalidate()
            }
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
                stylableLayer.doubleStrokeWidth += 1
                tvValue.text = "${stylableLayer.doubleStrokeWidth.toInt()} pt"
                canvasView.invalidate()
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
        showColorWheelDialogForProperty(layer.color) { color ->
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

    private fun showColorWheelDialogForProperty(initialColor: Int, applyColor: (Int) -> Unit) {
        ColorPickerHelper.showColorPickerDialog(
            this,
            initialColor,
            applyColor
        )
    }

    private fun showWarpMenu() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() ?: return
        val stylableLayer = layer as? StylableLayer ?: return

        if (layer is TextLayer) {
            layer.selectedWarpIndex = -1
        }

        toggleWarpMode(true)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }

        // Row/Col Controls
        val row = LinearLayout(this).apply {
             orientation = LinearLayout.HORIZONTAL
             gravity = Gravity.CENTER_VERTICAL
             setPadding(0, 8, 0, 8)
        }

        fun createCounter(label: String, value: Int, min: Int, onChange: (Int) -> Unit): View {
            val sub = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
            }
            val tv = TextView(this).apply { text = "$label: $value"; setTextColor(Color.WHITE); textSize = 14f; setPadding(0,0,8,0) }
            val btnMinus = TextView(this).apply {
                text = "-"
                setTextColor(Color.WHITE)
                textSize=20f
                setPadding(16,0,16,0)
                setOnClickListener { onChange((value - 1).coerceAtLeast(min)) }
            }
            val btnPlus = TextView(this).apply {
                text = "+"
                setTextColor(Color.WHITE)
                textSize=20f
                setPadding(16,0,16,0)
                setOnClickListener { onChange(value + 1) }
            }
            sub.addView(btnMinus); sub.addView(tv); sub.addView(btnPlus)
            return sub
        }

        val layerWRows = stylableLayer.warpRows
        val layerWCols = stylableLayer.warpCols

        row.addView(createCounter("Rows", layerWRows, 1) {
            initWarpMesh(layer, it, stylableLayer.warpCols)
            canvasView.invalidate()
            showWarpMenu() // Refresh UI
        })

        row.addView(createCounter("Cols", layerWCols, 1) {
            initWarpMesh(layer, stylableLayer.warpRows, it)
            canvasView.invalidate()
            showWarpMenu() // Refresh UI
        })

        layout.addView(row)

        // Reset Button
        val btnReset = android.widget.Button(this).apply {
            text = "Reset Points"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.DKGRAY)
                cornerRadius = dpToPx(8).toFloat()
            }
            setOnClickListener {
                initWarpMesh(layer, stylableLayer.warpRows, stylableLayer.warpCols, forceReset = true)
                canvasView.invalidate()
            }
        }
        layout.addView(btnReset)

        container.addView(layout)
    }

    private fun toggleWarpMode(enabled: Boolean) {
        val layer = canvasView.getSelectedLayer()
        if (layer is StylableLayer) {
            if (enabled) {
                layer.isWarp = true
                if (layer.warpMesh == null) initWarpMesh(layer as Layer, layer.warpRows, layer.warpCols)
            }
            canvasView.setWarpToolActive(enabled)
        }
    }

    private fun initWarpMesh(layer: Layer, rows: Int, cols: Int, forceReset: Boolean = false) {
         val w = layer.getWidth(); val h = layer.getHeight()
         val count = (rows + 1) * (cols + 1); val mesh = FloatArray(count * 2); var index = 0
         val hasOldMesh = !forceReset && (layer is StylableLayer) && (layer.warpMesh != null)
         val outPoint = FloatArray(2)
         for (r in 0..rows) {
             val v = r / rows.toFloat()
             for (c in 0..cols) {
                 val u = c / cols.toFloat()
                 if (hasOldMesh) {
                     layer.evaluateBezierSurface(u, v, outPoint)
                     mesh[index++] = outPoint[0]; mesh[index++] = outPoint[1]
                 } else {
                     val x = -w/2f + (w * u)
                     val y = -h/2f + (h * v)
                     mesh[index++] = x; mesh[index++] = y
                 }
             }
         }
         if (layer is StylableLayer) {
             layer.warpMesh = mesh; layer.warpRows = rows; layer.warpCols = cols
         }
    }

    private fun togglePuppetWarpMode(enabled: Boolean) {
        val layer = canvasView.getSelectedLayer()
        if (layer is StylableLayer) {
            if (enabled) {
                layer.isWarp = true
                if (layer is TextLayer) {
                    val targets = layer.getWarpTargets().filter { it.index != -1 }
                    if (targets.isNotEmpty() && (layer.selectedWarpIndex == -1 || layer.selectedWarpIndex >= targets.size)) {
                        layer.selectedWarpIndex = targets[0].index
                    }
                    if (layer.warpMesh == null) {
                        layer.initWarpMeshForTarget(layer.selectedWarpIndex, layer.warpRows, layer.warpCols)
                    }
                }
            } else {
                if (layer is TextLayer) {
                    layer.selectedWarpIndex = -1
                }
            }
            canvasView.setWarpToolActive(enabled)
        }
    }

    private fun showPuppetWarpMenu() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() ?: return
        val textLayer = layer as? TextLayer ?: return

        togglePuppetWarpMode(true)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }

        val title = TextView(this).apply {
            text = "Puppet Warp - Select Character:"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 0, 0, 8)
        }
        layout.addView(title)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val grid = GridLayout(this).apply {
            columnCount = 6
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val targets = textLayer.getWarpTargets().filter { it.index != -1 }
        for (target in targets) {
            val btn = android.widget.Button(this).apply {
                text = target.label
                val isSelectedTarget = textLayer.selectedWarpIndex == target.index
                setTextColor(if (isSelectedTarget) Color.BLACK else Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(if (isSelectedTarget) Color.YELLOW else Color.DKGRAY)
                    cornerRadius = dpToPx(6).toFloat()
                }

                val params = GridLayout.LayoutParams().apply {
                    width = dpToPx(40)
                    height = dpToPx(40)
                    setMargins(6, 6, 6, 6)
                }
                layoutParams = params

                setOnClickListener {
                    textLayer.selectedWarpIndex = target.index
                    if (textLayer.warpMesh == null) {
                        textLayer.initWarpMeshForTarget(target.index, textLayer.warpRows, textLayer.warpCols)
                    }
                    canvasView.invalidate()
                    showPuppetWarpMenu()
                }
            }
            grid.addView(btn)
        }
        scroll.addView(grid)
        layout.addView(scroll)

        if (textLayer.selectedWarpIndex != -1) {
            val controlRow = LinearLayout(this).apply {
                 orientation = LinearLayout.HORIZONTAL
                 gravity = Gravity.CENTER_VERTICAL
                 setPadding(0, 8, 0, 8)
            }

            fun createCounter(label: String, value: Int, min: Int, onChange: (Int) -> Unit): View {
                val sub = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    gravity = Gravity.CENTER
                }
                val tv = TextView(this).apply { text = "$label: $value"; setTextColor(Color.WHITE); textSize = 14f; setPadding(0,0,8,0) }
                val btnMinus = TextView(this).apply {
                    text = "-"
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    setPadding(16,0,16,0)
                    setOnClickListener { onChange((value - 1).coerceAtLeast(min)) }
                }
                val btnPlus = TextView(this).apply {
                    text = "+"
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    setPadding(16,0,16,0)
                    setOnClickListener { onChange(value + 1) }
                }
                sub.addView(btnMinus); sub.addView(tv); sub.addView(btnPlus)
                return sub
            }

            val letterRows = textLayer.warpRows
            val letterCols = textLayer.warpCols

            controlRow.addView(createCounter("Rows", letterRows, 1) {
                textLayer.initWarpMeshForTarget(textLayer.selectedWarpIndex, it, textLayer.warpCols)
                canvasView.invalidate()
                showPuppetWarpMenu()
            })

            controlRow.addView(createCounter("Cols", letterCols, 1) {
                textLayer.initWarpMeshForTarget(textLayer.selectedWarpIndex, textLayer.warpRows, it)
                canvasView.invalidate()
                showPuppetWarpMenu()
            })

            layout.addView(controlRow)

            val btnReset = android.widget.Button(this).apply {
                text = "Reset Letter Points"
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.DKGRAY)
                    cornerRadius = dpToPx(8).toFloat()
                }
                setOnClickListener {
                    textLayer.initWarpMeshForTarget(textLayer.selectedWarpIndex, textLayer.warpRows, textLayer.warpCols, forceReset = true)
                    canvasView.invalidate()
                }
            }
            layout.addView(btnReset)
        }

        container.addView(layout)
    }

    private fun showPerspectiveMenu() {
        val layer = canvasView.getSelectedLayer()
        if (layer == null || layer !is StylableLayer) return
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
                val stylable = canvasView.getSelectedLayer() as? StylableLayer ?: return@setOnClickListener
                val layer = stylable as Layer
                val w = layer.getWidth(); val h = layer.getHeight()
                val pts = floatArrayOf(-w/2f, -h/2f, w/2f, -h/2f, w/2f, h/2f, -w/2f, h/2f)
                stylable.perspectivePoints = pts
                canvasView.invalidate()
                Toast.makeText(this@EditorActivity, "Perspective Reset", Toast.LENGTH_SHORT).show()
            }
        }

        layout.addView(btnReset)
        container.addView(layout)
    }

    private fun addWatermarkLayer(isAuto: Boolean) {
        val watermarkFile = java.io.File(filesDir, "watermark.png")
        if (!watermarkFile.exists()) {
            if (!isAuto) Toast.makeText(this, "Watermark file not found. Please import it in Settings.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(watermarkFile.absolutePath)
            if (bitmap != null) {
                val settingsPrefs = getSharedPreferences("settings_prefs", MODE_PRIVATE)
                val opacity = settingsPrefs.getInt("watermark_opacity", 255)
                val position = if (isAuto) settingsPrefs.getString("watermark_position", "Center") ?: "Center" else "Center"

                val layer = ImageLayer(bitmap, null)
                layer.opacity = opacity

                val cw = canvasView.canvasWidth
                val ch = canvasView.canvasHeight

                val posPoint = calculateWatermarkPosition(position, cw, ch, bitmap.width, bitmap.height)
                layer.x = posPoint.x
                layer.y = posPoint.y

                canvasView.getLayers().add(layer)
                canvasView.invalidate()
                if (!isAuto) Toast.makeText(this, "Watermark Inserted", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun calculateWatermarkPosition(pos: String, canvasW: Int, canvasH: Int, layerW: Int, layerH: Int): android.graphics.PointF {
        val margin = 50f
        return when (pos) {
            "Upper left" -> android.graphics.PointF(margin + layerW / 2f, margin + layerH / 2f)
            "Top" -> android.graphics.PointF(canvasW / 2f, margin + layerH / 2f)
            "Upper Right" -> android.graphics.PointF(canvasW - margin - layerW / 2f, margin + layerH / 2f)
            "Middle Left" -> android.graphics.PointF(margin + layerW / 2f, canvasH / 2f)
            "Center" -> android.graphics.PointF(canvasW / 2f, canvasH / 2f)
            "Middle Right" -> android.graphics.PointF(canvasW - margin - layerW / 2f, canvasH / 2f)
            "Bottom Left" -> android.graphics.PointF(margin + layerW / 2f, canvasH - margin - layerH / 2f)
            "Bottom" -> android.graphics.PointF(canvasW / 2f, canvasH - margin - layerH / 2f)
            "Bottom Right" -> android.graphics.PointF(canvasW - margin - layerW / 2f, canvasH - margin - layerH / 2f)
            "Random" -> {
                val rx = (margin + layerW / 2f) + (Math.random() * (canvasW - 2 * margin - layerW)).toFloat()
                val ry = (margin + layerH / 2f) + (Math.random() * (canvasH - 2 * margin - layerH)).toFloat()
                android.graphics.PointF(rx, ry)
            }
            else -> android.graphics.PointF(canvasW / 2f, canvasH / 2f)
        }
    }

    private fun showOpacityMenu() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() ?: return
        if (layer !is StylableLayer) return

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,16,16,16) }

        // 1. Blend Mode
        val modes = arrayOf("NORMAL", "OVERLAY", "ADD", "MULTIPLY", "SCREEN", "DARKEN", "LIGHTEN")
        val spinner = android.widget.Spinner(this)
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        // Set selection
        var currentIdx = 0
        for ((i, m) in modes.withIndex()) {
            if (m == layer.blendMode) currentIdx = i
        }
        spinner.setSelection(currentIdx)

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                layer.blendMode = modes[pos]
                canvasView.invalidate()
            }
            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
        }
        layout.addView(TextView(this).apply { text = "Blend Mode"; setTextColor(Color.LTGRAY) })
        layout.addView(spinner)

        // 2. Global Opacity
        val s1 = createSlider("Opacity: ${(layer.opacity/2.55f).toInt()}%", layer.opacity, 255) {
            layer.opacity = it
            canvasView.invalidate()
        }
        val tv1 = s1.findViewWithTag<TextView>("SLIDER_LABEL")
        val sb1 = s1.findViewWithTag<SeekBar>("SLIDER_BAR")
        sb1?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                layer.opacity = p
                tv1?.text = "Opacity: ${(p/2.55f).toInt()}%"
                canvasView.invalidate()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        layout.addView(s1)

        // 3. Gradient Opacity
        val toggleGrad = android.widget.CheckBox(this).apply {
            text = "Gradient Opacity"
            isChecked = layer.isOpacityGradient
            setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.CYAN)
            setOnCheckedChangeListener { _, b ->
                layer.isOpacityGradient = b
                canvasView.invalidate()
            }
        }
        layout.addView(toggleGrad)

        // Start Alpha
        val layerOpStart = layer.opacityStart
        val s2 = createSlider("Left Alpha: ${(layerOpStart/2.55f).toInt()}%", layerOpStart, 255) {
            layer.opacityStart = it
            canvasView.invalidate()
        }
        val tv2 = s2.findViewWithTag<TextView>("SLIDER_LABEL")
        val sb2 = s2.findViewWithTag<SeekBar>("SLIDER_BAR")
        sb2?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                layer.opacityStart = p
                tv2?.text = "Left Alpha: ${(p/2.55f).toInt()}%"
                canvasView.invalidate()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        layout.addView(s2)

        // End Alpha
        val layerOpEnd = layer.opacityEnd
        val s3 = createSlider("Right Alpha: ${(layerOpEnd/2.55f).toInt()}%", layerOpEnd, 255) {
            layer.opacityEnd = it
            canvasView.invalidate()
        }
        val tv3 = s3.findViewWithTag<TextView>("SLIDER_LABEL")
        val sb3 = s3.findViewWithTag<SeekBar>("SLIDER_BAR")
        sb3?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                layer.opacityEnd = p
                tv3?.text = "Right Alpha: ${(p/2.55f).toInt()}%"
                canvasView.invalidate()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        layout.addView(s3)

        // Angle
        val layerOpAngle = layer.opacityAngle
        val s4 = createSlider("Angle: ${layerOpAngle}°", layerOpAngle, 360) {
            layer.opacityAngle = it
            canvasView.invalidate()
        }
        val tv4 = s4.findViewWithTag<TextView>("SLIDER_LABEL")
        val sb4 = s4.findViewWithTag<SeekBar>("SLIDER_BAR")
        sb4?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                layer.opacityAngle = p
                tv4?.text = "Angle: $p°"
                canvasView.invalidate()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        layout.addView(s4)

        scroll.addView(layout)
        container.addView(scroll)
    }


    private fun addBrushLayer() {
        val bmp = android.graphics.Bitmap.createBitmap(canvasView.canvasWidth, canvasView.canvasHeight, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        val layer = com.astral.typer.models.BrushLayer(bmp)

        com.astral.typer.utils.UndoManager.saveState(canvasView.getLayers())
        canvasView.getLayers().add(layer)
        canvasView.selectLayer(layer)
        canvasView.invalidate()
        Toast.makeText(this, "Brush Layer Added!", Toast.LENGTH_SHORT).show()
    }

    private fun showBrushMenu() {
        val container = prepareContainer()
        val layer = canvasView.getSelectedLayer() as? com.astral.typer.models.BrushLayer ?: return

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val menuScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }
        menuScroll.addView(scrollContent)
        mainLayout.addView(menuScroll)

        val sSize = createSlider("Brush Size: ${layer.brushSize.toInt()} pt", layer.brushSize.toInt(), 200) {
            layer.brushSize = it.toFloat().coerceAtLeast(1f)
        }
        val tvSize = sSize.findViewWithTag<TextView>("SLIDER_LABEL")
        sSize.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                val v = p.coerceAtLeast(1)
                layer.brushSize = v.toFloat()
                tvSize?.text = "Brush Size: $v pt"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        scrollContent.addView(sSize)

        val sHardness = createSlider("Hardness: ${(layer.brushHardness * 100).toInt()}%", (layer.brushHardness * 100).toInt(), 100) {
            layer.brushHardness = it / 100f
        }
        val tvHardness = sHardness.findViewWithTag<TextView>("SLIDER_LABEL")
        sHardness.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                layer.brushHardness = p / 100f
                tvHardness?.text = "Hardness: $p%"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        scrollContent.addView(sHardness)

        val sOpacity = createSlider("Opacity: ${(layer.brushOpacity / 2.55f).toInt()}%", layer.brushOpacity, 255) {
            layer.brushOpacity = it
        }
        val tvOpacity = sOpacity.findViewWithTag<TextView>("SLIDER_LABEL")
        sOpacity.findViewWithTag<SeekBar>("SLIDER_BAR")?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                layer.brushOpacity = p
                tvOpacity?.text = "Opacity: ${(p / 2.55f).toInt()}%"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        scrollContent.addView(sOpacity)

        val categories = listOf("classic", "Dieterle", "deevad", "experimental", "kaerhon_v1", "ramon", "tanda")
        val tabsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val tabsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        tabsScroll.addView(tabsLayout)
        scrollContent.addView(tabsScroll)

        val gridContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scrollContent.addView(gridContainer)

        var activeTab = "classic"

        fun loadBrushesForCategory(category: String) {
            gridContainer.removeAllViews()
            activeTab = category

            val grid = GridLayout(this@EditorActivity).apply {
                columnCount = 4
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            gridContainer.addView(grid)

            val files = assets.list("brushes/$category") ?: emptyArray()
            val brushNames = files.filter { it.endsWith(".myb") }.map { it.removeSuffix(".myb") }.sorted()

            for (name in brushNames) {
                val item = LinearLayout(this@EditorActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(8, 8, 8, 8)
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = dpToPx(72)
                        height = dpToPx(100)
                        setMargins(4, 4, 4, 4)
                    }

                    val isActive = layer.brushName == "$category/$name"
                    background = GradientDrawable().apply {
                        setColor(if (isActive) Color.DKGRAY else Color.TRANSPARENT)
                        cornerRadius = dpToPx(8).toFloat()
                        if (isActive) setStroke(dpToPx(2), Color.CYAN)
                    }

                    setOnClickListener {
                        layer.brushName = "$category/$name"
                        loadBrushesForCategory(category)
                    }
                }

                val img = android.widget.ImageView(this@EditorActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(56), dpToPx(56))
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER

                    val thumb = try {
                        assets.open("brushes/$category/${name}_prev.png").use { input ->
                            android.graphics.BitmapFactory.decodeStream(input)
                        }
                    } catch (e: Exception) {
                        null
                    }
                    if (thumb != null) {
                        setImageBitmap(thumb)
                    } else {
                        setImageResource(R.drawable.ic_brush)
                        setColorFilter(Color.WHITE)
                    }
                }
                item.addView(img)

                val tv = TextView(this@EditorActivity).apply {
                    text = name
                    setTextColor(Color.WHITE)
                    textSize = 10f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                item.addView(tv)

                grid.addView(item)
            }
        }

        for (cat in categories) {
            val btn = TextView(this).apply {
                text = cat
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(16, 8, 16, 8)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

                val isSelectedCat = cat == activeTab
                background = GradientDrawable().apply {
                    setColor(if (isSelectedCat) Color.DKGRAY else Color.TRANSPARENT)
                    cornerRadius = dpToPx(16).toFloat()
                    if (isSelectedCat) setStroke(dpToPx(1), Color.CYAN)
                }

                setOnClickListener {
                    loadBrushesForCategory(cat)
                    for (i in 0 until tabsLayout.childCount) {
                        val child = tabsLayout.getChildAt(i) as TextView
                        val isSel = child.text == cat
                        child.background = GradientDrawable().apply {
                            setColor(if (isSel) Color.DKGRAY else Color.TRANSPARENT)
                            cornerRadius = dpToPx(16).toFloat()
                            if (isSel) setStroke(dpToPx(1), Color.CYAN)
                        }
                    }
                }
            }
            tabsLayout.addView(btn)
        }

        loadBrushesForCategory("classic")
        container.addView(mainLayout)
    }
}
