package com.astral.typer.views

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.VectorDrawable
import android.util.AttributeSet
import android.view.GestureDetector
import androidx.core.content.ContextCompat
import com.astral.typer.R
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.astral.typer.models.Layer
import com.astral.typer.models.TextLayer
import com.astral.typer.models.ImageLayer
import com.astral.typer.models.ShapeLayer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

class AstralCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Tiled Rendering Configuration
    data class ImageTile(val bitmap: android.graphics.Bitmap, val rect: RectF)
    private val backgroundTiles = mutableListOf<ImageTile>()
    private val TILE_SIZE = 1024

    var isBackgroundModified: Boolean = false

    // Canvas Configuration
    var canvasWidth = 1080
        private set
    var canvasHeight = 1080
        private set
    var canvasColor = Color.WHITE
    // private var canvasBitmap: android.graphics.Bitmap? = null // Removed

    // Drawing Tools
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundRect = RectF()
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // Layers
    private val layers = mutableListOf<Layer>()
    private var selectedLayer: Layer? = null

    // Modes
    private var isPerspectiveMode = false
    private var isInpaintMode = false
    private var isWarpToolActive = false
    var isTyperActive = false
    var preventDeselection = false

    // Grid Snap
    private var showVerticalCenterLine = false
    private var showHorizontalCenterLine = false
    private val snapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // Cut Mode
    private var cutPoints: FloatArray? = null

    // RAW Panel
    enum class RawPanelMode { ON_TOP, BESIDE }
    private val rawPanelTiles = mutableListOf<ImageTile>()
    private var rawPanelWidth = 0
    private var rawPanelHeight = 0
    var rawPanelOpacity: Int = 255
    var rawPanelMode: RawPanelMode = RawPanelMode.ON_TOP

    // Layer Erase Settings
    var layerEraseSize = 50f
    var showEraseSizePreview = false
    var layerEraseOpacity = 255
    var layerEraseHardness = 0f

    // Temp path for layer erase
    private val currentLayerErasePath = Path()
    private val currentLayerErasePoints = mutableListOf<com.astral.typer.models.ErasePoint>()

    // Saved brush state variables for BrushLayer erase toggle
    private var savedBrushName: String = "pencil"
    private var savedBrushSize: Float = 20f
    private var savedBrushHardness: Float = 0.5f
    private var savedBrushOpacity: Int = 255
    private var savedBrushPreset: com.astral.typer.utils.MyPaintBrushHelper.BrushPreset? = null

    // Inpaint Tools
    enum class InpaintTool {
        BRUSH, ERASER, LASSO, LASSO_ERASER, MAGIC_WAND, MAGIC_WAND_ERASER
    }

    enum class TyperTool {
        HAND, RECT, CIRCLE, LASSO, ERASER
    }

    data class TyperBubble(val rect: RectF, val isOval: Boolean)

    var currentInpaintTool = InpaintTool.BRUSH
    var currentTyperTool = TyperTool.HAND
    private val inpaintOps = mutableListOf<Pair<Path, InpaintTool>>()
    private val redoOps = mutableListOf<Pair<Path, InpaintTool>>()
    private var currentInpaintPath = Path()
    private val currentTyperPath = Path()

    // Cached Mask Bitmap REMOVED
    // private var cachedMaskBitmap: android.graphics.Bitmap? = null
    // private var isMaskDirty = true

    // Mode Flags
    private var isGradationMode = false
    private var isEraseLayerMode = false
    var pendingGradientStart: Int = Color.RED
    var pendingGradientEnd: Int = Color.BLUE
    var pendingHasMiddleColor: Boolean = false
    var pendingGradientMiddleColor: Int = Color.GREEN
    var pendingGradientStartPos: Float = 0.0f
    var pendingGradientMiddlePos: Float = 0.5f
    var pendingGradientEndPos: Float = 1.0f
    var targetGradientText: Boolean = true
    var targetGradientStroke: Boolean = false
    var targetGradientShadow: Boolean = false

    var magicWandSensitivity: Int = 30
    var magicWandExpand: Int = 0

    var brushSize = 50f
        set(value) {
            field = value
            inpaintPaint.strokeWidth = value
            eraserPaint.strokeWidth = value
            invalidate()
        }

    fun performMagicWand(startX: Int, startY: Int) {
        if (backgroundTiles.isEmpty()) return

        val originalW = canvasWidth
        val originalH = canvasHeight
        val sX = startX.coerceIn(0, originalW - 1)
        val sY = startY.coerceIn(0, originalH - 1)

        val pixelCache = TiledPixelCache(backgroundTiles, TILE_SIZE)
        val targetColor = pixelCache.getPixel(sX, sY)

        // Map sensitivity 0..100 to Manhattan color distance threshold in 0..300
        val maxDiff = (magicWandSensitivity / 100f * 300f).toInt()

        val r1 = (targetColor shr 16) and 0xFF
        val g1 = (targetColor shr 8) and 0xFF
        val b1 = targetColor and 0xFF

        val visited = TiledVisitedTracker(TILE_SIZE)
        val queue = IntQueue()

        queue.enqueue((sX shl 16) or (sY and 0xFFFF))
        visited.visit(sX, sY)

        var minX = sX
        var maxX = sX
        var minY = sY
        var maxY = sY

        var pixelCount = 0
        val maxPixels = 5000000

        while (!queue.isEmpty() && pixelCount < maxPixels) {
            val encoded = queue.dequeue()
            val cx = encoded ushr 16
            val cy = encoded and 0xFFFF
            pixelCount++

            if (cx < minX) minX = cx
            if (cx > maxX) maxX = cx
            if (cy < minY) minY = cy
            if (cy > maxY) maxY = cy

            // Check Left neighbor
            if (cx > 0) {
                val nx = cx - 1
                val ny = cy
                val c2 = pixelCache.getPixel(nx, ny)
                val r2 = (c2 shr 16) and 0xFF
                val g2 = (c2 shr 8) and 0xFF
                val b2 = c2 and 0xFF
                val diff = abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2)
                if (diff <= maxDiff) {
                    if (visited.visit(nx, ny)) {
                        queue.enqueue((nx shl 16) or (ny and 0xFFFF))
                    }
                }
            }
            // Check Right neighbor
            if (cx < originalW - 1) {
                val nx = cx + 1
                val ny = cy
                val c2 = pixelCache.getPixel(nx, ny)
                val r2 = (c2 shr 16) and 0xFF
                val g2 = (c2 shr 8) and 0xFF
                val b2 = c2 and 0xFF
                val diff = abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2)
                if (diff <= maxDiff) {
                    if (visited.visit(nx, ny)) {
                        queue.enqueue((nx shl 16) or (ny and 0xFFFF))
                    }
                }
            }
            // Check Top neighbor
            if (cy > 0) {
                val nx = cx
                val ny = cy - 1
                val c2 = pixelCache.getPixel(nx, ny)
                val r2 = (c2 shr 16) and 0xFF
                val g2 = (c2 shr 8) and 0xFF
                val b2 = c2 and 0xFF
                val diff = abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2)
                if (diff <= maxDiff) {
                    if (visited.visit(nx, ny)) {
                        queue.enqueue((nx shl 16) or (ny and 0xFFFF))
                    }
                }
            }
            // Check Bottom neighbor
            if (cy < originalH - 1) {
                val nx = cx
                val ny = cy + 1
                val c2 = pixelCache.getPixel(nx, ny)
                val r2 = (c2 shr 16) and 0xFF
                val g2 = (c2 shr 8) and 0xFF
                val b2 = c2 and 0xFF
                val diff = abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2)
                if (diff <= maxDiff) {
                    if (visited.visit(nx, ny)) {
                        queue.enqueue((nx shl 16) or (ny and 0xFFFF))
                    }
                }
            }
        }

        // Bounding box with expand/reduce margins
        val pad = abs(magicWandExpand) + 2
        val maskMinX = (minX - pad).coerceAtLeast(0)
        val maskMinY = (minY - pad).coerceAtLeast(0)
        val maskMaxX = (maxX + pad).coerceAtMost(originalW - 1)
        val maskMaxY = (maxY + pad).coerceAtMost(originalH - 1)

        val maskW = maskMaxX - maskMinX + 1
        val maskH = maskMaxY - maskMinY + 1

        var localVisited = java.util.BitSet(maskW * maskH)
        for (y in minY..maxY) {
            val ly = y - maskMinY
            for (x in minX..maxX) {
                if (visited.isVisited(x, y)) {
                    localVisited.set(ly * maskW + (x - maskMinX))
                }
            }
        }

        val expandAmount = magicWandExpand

        if (expandAmount > 0) {
            // Dilate (Expand selection)
            for (step in 0 until expandAmount) {
                val nextVisited = java.util.BitSet(maskW * maskH)
                for (y in 0 until maskH) {
                    for (x in 0 until maskW) {
                        val idx = y * maskW + x
                        if (localVisited.get(idx)) {
                            nextVisited.set(idx)
                            if (x > 0) nextVisited.set(idx - 1)
                            if (x < maskW - 1) nextVisited.set(idx + 1)
                            if (y > 0) nextVisited.set(idx - maskW)
                            if (y < maskH - 1) nextVisited.set(idx + maskW)
                        }
                    }
                }
                localVisited = nextVisited
            }
        } else if (expandAmount < 0) {
            // Erode (Reduce selection)
            val shrinkAmount = -expandAmount
            for (step in 0 until shrinkAmount) {
                val nextVisited = java.util.BitSet(maskW * maskH)
                for (y in 0 until maskH) {
                    for (x in 0 until maskW) {
                        val idx = y * maskW + x
                        if (localVisited.get(idx)) {
                            val left = x > 0 && localVisited.get(idx - 1)
                            val right = x < maskW - 1 && localVisited.get(idx + 1)
                            val top = y > 0 && localVisited.get(idx - maskW)
                            val bottom = y < maskH - 1 && localVisited.get(idx + maskW)
                            if (left && right && top && bottom) {
                                nextVisited.set(idx)
                            }
                        }
                    }
                }
                localVisited = nextVisited
            }
        }

        val path = Path()
        // Group into horizontal segments to build path efficiently
        for (y in 0 until maskH) {
            var x = 0
            while (x < maskW) {
                if (localVisited.get(y * maskW + x)) {
                    var xEnd = x
                    while (xEnd < maskW && localVisited.get(y * maskW + xEnd)) {
                        xEnd++
                    }
                    val left = (maskMinX + x).toFloat()
                    val top = (maskMinY + y).toFloat()
                    val right = (maskMinX + xEnd).toFloat()
                    val bottom = (maskMinY + y + 1f).toFloat()
                    path.addRect(left, top, right, bottom, Path.Direction.CW)
                    x = xEnd
                } else {
                    x++
                }
            }
        }

        if (!path.isEmpty) {
            inpaintOps.add(Pair(path, currentInpaintTool))
            redoOps.clear()
            invalidate()
        }
    }

    private val inpaintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = brushSize
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 128
    }

    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        style = Paint.Style.STROKE
        strokeWidth = brushSize
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
        alpha = 128
    }

    private val lassoErasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        style = Paint.Style.FILL
    }

    private val lassoStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * Computes the tight bounding box around all active inpaint operations.
     */
    fun getInpaintMaskBounds(): android.graphics.Rect {
        val bounds = android.graphics.RectF()
        if (inpaintOps.isEmpty()) {
            return android.graphics.Rect(0, 0, canvasWidth, canvasHeight)
        }
        for ((path, tool) in inpaintOps) {
            val temp = android.graphics.RectF()
            path.computeBounds(temp, true)
            if (tool == InpaintTool.BRUSH || tool == InpaintTool.ERASER) {
                // Pad by brushSize to account for stroke thickness
                temp.inset(-brushSize / 2f - 2f, -brushSize / 2f - 2f)
            }
            bounds.union(temp)
        }
        val rect = android.graphics.Rect()
        bounds.roundOut(rect)
        rect.left = rect.left.coerceIn(0, canvasWidth)
        rect.right = rect.right.coerceIn(0, canvasWidth)
        rect.top = rect.top.coerceIn(0, canvasHeight)
        rect.bottom = rect.bottom.coerceIn(0, canvasHeight)

        if (rect.isEmpty) {
            rect.set(0, 0, canvasWidth, canvasHeight)
        }
        return rect
    }

    /**
     * Generates the Inpaint mask on demand.
     * Note: This allocates a full bitmap. Use with care on large canvases.
     * Use getRegionAsBitmap for tiled access if possible.
     */
    fun getInpaintMask(): android.graphics.Bitmap {
        return getInpaintMask(android.graphics.Rect(0, 0, canvasWidth, canvasHeight))
    }

    /**
     * Overloaded version of getInpaintMask that generates a cropped mask within specific bounds.
     */
    fun getInpaintMask(bounds: android.graphics.Rect): android.graphics.Bitmap {
        val w = bounds.width().coerceAtLeast(1)
        val h = bounds.height().coerceAtLeast(1)
        val bmp = try {
            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("AstralCanvasView", "OOM generating inpaint mask")
            return android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        }

        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        // Translate coordinates so they are drawn relative to the crop region
        canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())

        val brushP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = brushSize
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val eraseP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
            style = Paint.Style.STROKE
            strokeWidth = brushSize
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val lassoP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val lassoEraseP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
            style = Paint.Style.FILL
        }

        for ((path, tool) in inpaintOps) {
            when(tool) {
                InpaintTool.BRUSH -> canvas.drawPath(path, brushP)
                InpaintTool.ERASER -> canvas.drawPath(path, eraseP)
                InpaintTool.LASSO -> canvas.drawPath(path, lassoP)
                InpaintTool.LASSO_ERASER -> canvas.drawPath(path, lassoEraseP)
                InpaintTool.MAGIC_WAND -> canvas.drawPath(path, lassoP)
                InpaintTool.MAGIC_WAND_ERASER -> canvas.drawPath(path, lassoEraseP)
            }
        }
        return bmp
    }

    fun clearInpaintMask() {
        inpaintOps.clear()
        redoOps.clear()
        currentInpaintPath.reset()
        invalidate()
    }

    fun undoInpaintMask(): Boolean {
        if (inpaintOps.isNotEmpty()) {
            val last = inpaintOps.removeAt(inpaintOps.size - 1)
            redoOps.add(last)
            invalidate()
            return true
        }
        return false
    }

    fun redoInpaintMask(): Boolean {
        if (redoOps.isNotEmpty()) {
            val last = redoOps.removeAt(redoOps.size - 1)
            inpaintOps.add(last)
            invalidate()
            return true
        }
        return false
    }

    fun addInpaintMask(rects: List<RectF>) {
        if (!isInpaintMode) return

        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        val shape = prefs.getString("inpaint_mask_shape", "Rectangle") ?: "Rectangle"
        val padding = prefs.getInt("inpaint_text_padding", 0).toFloat()

        val path = Path()
        for (rect in rects) {
            val paddedRect = RectF(rect)
            if (padding != 0f) {
                paddedRect.inset(-padding, -padding)
            }

            if (shape == "Rounded") {
                path.addRoundRect(paddedRect, 16f, 16f, Path.Direction.CW)
            } else {
                path.addRect(paddedRect, Path.Direction.CW)
            }
        }
        if (!path.isEmpty) {
            inpaintOps.add(Pair(path, InpaintTool.LASSO))
            redoOps.clear()
            invalidate()
        }
    }

    fun setInpaintMode(enabled: Boolean) {
        isInpaintMode = enabled
        if (enabled) {
            // Re-enabled Hardware Acceleration thanks to Tiled Rendering
            setLayerType(LAYER_TYPE_HARDWARE, null)
            selectLayer(null)
            currentMode = Mode.INPAINT
        } else {
            setLayerType(LAYER_TYPE_HARDWARE, null)
            currentMode = Mode.NONE
        }
        invalidate()
    }

    fun isRawPanelLoaded(): Boolean {
        return rawPanelTiles.isNotEmpty()
    }


    fun enterCutMode() {
        if (selectedLayer is ImageLayer) {
            val layer = selectedLayer as ImageLayer
            val w = layer.getWidth()
            val h = layer.getHeight()
            // Init points relative to layer center (Local Space)
            cutPoints = floatArrayOf(
                -w/2f, -h/2f, // TL
                w/2f, -h/2f,  // TR
                w/2f, h/2f,   // BR
                -w/2f, h/2f   // BL
            )
            invalidate()
        }
    }

    fun exitCutMode() {
        cutPoints = null
        currentMode = Mode.NONE
        invalidate()
    }

    fun applyCut() {
        val layer = selectedLayer as? ImageLayer ?: return
        val pts = cutPoints ?: return

        // pts are in local layer space relative to (0,0) center.
        // We need to map them to Bitmap coordinates.
        // Bitmap (0,0) corresponds to local (-w/2, -h/2).
        val w = layer.getWidth()
        val h = layer.getHeight()
        val offsetX = w / 2f
        val offsetY = h / 2f

        val path = Path()
        path.moveTo(pts[0] + offsetX, pts[1] + offsetY)
        path.lineTo(pts[2] + offsetX, pts[3] + offsetY)
        path.lineTo(pts[4] + offsetX, pts[5] + offsetY)
        path.lineTo(pts[6] + offsetX, pts[7] + offsetY)
        path.close()

        // Calculate bounding box of the cut path
        val bounds = RectF()
        path.computeBounds(bounds, true)

        if (bounds.width() <= 0 || bounds.height() <= 0) return

        // Create new bitmap
        try {
            val newBitmap = android.graphics.Bitmap.createBitmap(
                bounds.width().toInt(),
                bounds.height().toInt(),
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(newBitmap)

            // Translate so that the top-left of the bounds is at (0,0)
            canvas.translate(-bounds.left, -bounds.top)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Draw path as mask
            canvas.drawPath(path, paint)

            // Draw original bitmap with SRC_IN to keep only intersection
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(layer.bitmap, 0f, 0f, paint)

            // Update Layer
            com.astral.typer.utils.UndoManager.saveState(layers) // Save before modifying
            layer.bitmap = newBitmap

            // We need to adjust layer position because the center has changed!
            val localCenterShiftX = bounds.centerX() - offsetX
            val localCenterShiftY = bounds.centerY() - offsetY

            // Transform shift to global
            val rad = Math.toRadians(layer.rotation.toDouble())
            val cos = Math.cos(rad)
            val sin = Math.sin(rad)

            val globalShiftX = (localCenterShiftX * layer.scaleX * cos - localCenterShiftY * layer.scaleY * sin).toFloat()
            val globalShiftY = (localCenterShiftX * layer.scaleX * sin + localCenterShiftY * layer.scaleY * cos).toFloat()

            layer.x += globalShiftX
            layer.y += globalShiftY

            // Reset state
            exitCutMode()

        } catch (e: Exception) {
            android.util.Log.e("AstralCanvasView", "Cut Failed", e)
        }
    }

    fun getViewportCenter(): FloatArray {
        val inverse = Matrix()
        viewMatrix.invert(inverse)
        val center = floatArrayOf(width / 2f, height / 2f)
        inverse.mapPoints(center)
        return center
    }

    fun getBackgroundImage(): android.graphics.Bitmap? {
        // Reconstruct full bitmap from tiles for compatibility (e.g. Saving)
        // Warning: This may OOM on huge images.
        if (backgroundTiles.isEmpty()) return null

        try {
            val bitmap = android.graphics.Bitmap.createBitmap(canvasWidth, canvasHeight, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            for (tile in backgroundTiles) {
                canvas.drawBitmap(tile.bitmap, tile.rect.left, tile.rect.top, null)
            }
            return bitmap
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("AstralCanvasView", "OOM in getBackgroundImage (ARGB_8888), trying RGB_565 fallback")
            System.gc()
            try {
                val bitmap = android.graphics.Bitmap.createBitmap(canvasWidth, canvasHeight, android.graphics.Bitmap.Config.RGB_565)
                val canvas = Canvas(bitmap)
                for (tile in backgroundTiles) {
                    canvas.drawBitmap(tile.bitmap, tile.rect.left, tile.rect.top, null)
                }
                return bitmap
            } catch (e2: OutOfMemoryError) {
                android.util.Log.e("AstralCanvasView", "OOM in getBackgroundImage (RGB_565) fallback as well")
                return null
            }
        }
    }

    fun getDownsampledBackgroundImage(maxDim: Int): android.graphics.Bitmap? {
        if (backgroundTiles.isEmpty()) return null
        val originalW = canvasWidth
        val originalH = canvasHeight
        if (originalW <= maxDim && originalH <= maxDim) {
            return getBackgroundImage()
        }
        val scale = maxDim.toFloat() / max(originalW, originalH)
        val dstW = (originalW * scale).toInt().coerceAtLeast(1)
        val dstH = (originalH * scale).toInt().coerceAtLeast(1)
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(dstW, dstH, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (tile in backgroundTiles) {
                canvas.drawBitmap(tile.bitmap, tile.rect.left, tile.rect.top, tilePaint)
            }
            return bitmap
        } catch (e: Throwable) {
            android.util.Log.e("AstralCanvasView", "Error in getDownsampledBackgroundImage", e)
            return null
        }
    }


    /**
     * Extracts a specific region of the background image as a single Bitmap.
     * Efficiently stitches relevant tiles.
     */
    fun getRegionAsBitmap(rect: RectF): android.graphics.Bitmap {
        val width = rect.width().toInt().coerceAtLeast(1)
        val height = rect.height().toInt().coerceAtLeast(1)

        val output = try {
            android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("AstralCanvasView", "OOM in getRegionAsBitmap")
            return android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        }

        val canvas = Canvas(output)
        // Shift canvas so that rect.left, rect.top aligns with 0,0
        canvas.translate(-rect.left, -rect.top)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        for (tile in backgroundTiles) {
            if (RectF.intersects(tile.rect, rect)) {
                canvas.drawBitmap(tile.bitmap, tile.rect.left, tile.rect.top, paint)
            }
        }
        return output
    }

    /**
     * Pastes a source bitmap (e.g. Inpaint result) back onto the background tiles.
     * Updates the specific tiles that intersect with the position.
     */
    fun pasteBitmapToTiles(source: android.graphics.Bitmap, position: PointF) {
        val srcRect = RectF(
            position.x,
            position.y,
            position.x + source.width,
            position.y + source.height
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Xfermode SRC to replace content
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)

        for (tile in backgroundTiles) {
            if (RectF.intersects(tile.rect, srcRect)) {
                // We need to draw the relevant part of 'source' onto 'tile.bitmap'

                // 1. Create a canvas for the tile
                val tileCanvas = Canvas(tile.bitmap)

                // 2. We want to draw 'source' at 'position'
                // But we are drawing into 'tile' which is at 'tile.rect.left, tile.rect.top'
                // So the source should be drawn at (position.x - tile.left, position.y - tile.top)
                val drawX = position.x - tile.rect.left
                val drawY = position.y - tile.rect.top

                tileCanvas.drawBitmap(source, drawX, drawY, paint)
            }
        }
        isBackgroundModified = true
        invalidate()
    }

    fun getLayers(): MutableList<Layer> {
        return layers
    }

    fun setLayers(newLayers: List<Layer>) {
        layers.clear()
        layers.addAll(newLayers)
        selectedLayer = null
        layers.forEach { it.isSelected = false }
        invalidate()
    }

    fun getSelectedLayer(): Layer? {
        return selectedLayer
    }

    fun setPerspectiveMode(enabled: Boolean) {
        isPerspectiveMode = enabled
        if (!enabled) {
            if (currentMode == Mode.PERSPECTIVE_DRAG_TL || currentMode == Mode.PERSPECTIVE_DRAG_TR ||
                currentMode == Mode.PERSPECTIVE_DRAG_BR || currentMode == Mode.PERSPECTIVE_DRAG_BL) {
                currentMode = Mode.NONE
            }
        }
        invalidate()
    }

    fun setWarpToolActive(enabled: Boolean) {
        isWarpToolActive = enabled
        if (!enabled && currentMode == Mode.WARP_DRAG) {
            currentMode = Mode.NONE
        }
        invalidate()
    }

    fun setEraseLayerMode(enabled: Boolean) {
        isEraseLayerMode = enabled
        if (enabled) {
            currentMode = Mode.ERASE_LAYER
            val brushLayer = selectedLayer as? com.astral.typer.models.BrushLayer
            if (brushLayer != null) {
                savedBrushName = brushLayer.brushName
                savedBrushSize = brushLayer.brushSize
                savedBrushHardness = brushLayer.brushHardness
                savedBrushOpacity = brushLayer.brushOpacity
                savedBrushPreset = brushLayer.activePreset
            }
        } else {
            if (currentMode == Mode.ERASE_LAYER) {
                currentMode = Mode.NONE
            }
            val brushLayer = selectedLayer as? com.astral.typer.models.BrushLayer
            if (brushLayer != null) {
                brushLayer.brushName = savedBrushName
                brushLayer.brushSize = savedBrushSize
                brushLayer.brushHardness = savedBrushHardness
                brushLayer.brushOpacity = savedBrushOpacity
                brushLayer.activePreset = savedBrushPreset
            }
        }
        invalidate()
    }

    fun undoLayerErase() {
        (selectedLayer as? com.astral.typer.models.StylableLayer)?.let {
            it.undoLastErasePath(null)
            invalidate()
        }
    }

    // Handles Constants
    private val HANDLE_RADIUS = 30f

    // Geometry Helper
    private data class HandleGeometry(val radius: Float, val offset: Float, val scale: Float)

    private fun getHandleGeometry(layer: Layer): HandleGeometry {
        val viewScale = getCurrentViewScale()
        val avgLayerScale = (abs(layer.scaleX) + abs(layer.scaleY)) / 2f
        val totalScale = avgLayerScale * viewScale

        // Screen dimensions of the layer
        val screenW = layer.getWidth() * totalScale
        val screenH = layer.getHeight() * totalScale
        val minScreenDim = min(screenW, screenH)

        // Calculate a base radius that shrinks for very small layers, but has a minimum for usability
        val adaptiveRadius = if (minScreenDim < 150f) {
            max(15f, minScreenDim / 5f) // Increased min from 10 to 15
        } else {
            HANDLE_RADIUS
        }

        // Ensure the handle is always large enough to be seen and tapped regardless of zoom
        val minScreenRadius = 24f
        val targetScreenRadius = max(adaptiveRadius, minScreenRadius)

        val localRadius = targetScreenRadius / totalScale
        val localIconScale = localRadius / 15f
        val handleOffset = localRadius * 1.5f

        return HandleGeometry(localRadius, handleOffset, localIconScale)
    }

    // Paths for Icons
    private val pathRotate = Path()
    private val pathResize = Path()
    private val pathStretchH = Path()
    private val pathStretchV = Path()
    private val pathBoxWidth = Path()
    private val pathDelete = Path()
    private val pathDuplicate = Path()
    private val pathCopyStyle = Path()

    init {
        // Rotate: Curved Arrow
        pathRotate.moveTo(10f, 0f)
        pathRotate.arcTo(RectF(-10f, -10f, 10f, 10f), 0f, 270f, false)
        pathRotate.lineTo(0f, -15f)
        pathRotate.moveTo(0f, -10f)
        pathRotate.lineTo(0f, -5f)

        // Delete: X
        pathDelete.moveTo(-8f, -8f)
        pathDelete.lineTo(8f, 8f)
        pathDelete.moveTo(8f, -8f)
        pathDelete.lineTo(-8f, 8f)

        // Resize: Diagonal Arrows
        pathResize.moveTo(-8f, -8f)
        pathResize.lineTo(8f, 8f)
        pathResize.moveTo(8f, 8f)
        pathResize.lineTo(8f, 2f)
        pathResize.moveTo(8f, 8f)
        pathResize.lineTo(2f, 8f)
        pathResize.moveTo(-8f, -8f)
        pathResize.lineTo(-8f, -2f)
        pathResize.moveTo(-8f, -8f)
        pathResize.lineTo(-2f, -8f)

        // Stretch H
        pathStretchH.moveTo(-10f, 0f)
        pathStretchH.lineTo(10f, 0f)
        pathStretchH.moveTo(-10f, 0f)
        pathStretchH.lineTo(-5f, -5f)
        pathStretchH.moveTo(-10f, 0f)
        pathStretchH.lineTo(-5f, 5f)
        pathStretchH.moveTo(10f, 0f)
        pathStretchH.lineTo(5f, -5f)
        pathStretchH.moveTo(10f, 0f)
        pathStretchH.lineTo(5f, 5f)

        // Stretch V
        pathStretchV.moveTo(0f, -10f)
        pathStretchV.lineTo(0f, 10f)
        pathStretchV.moveTo(0f, -10f)
        pathStretchV.lineTo(-5f, -5f)
        pathStretchV.moveTo(0f, -10f)
        pathStretchV.lineTo(5f, -5f)
        pathStretchV.moveTo(0f, 10f)
        pathStretchV.lineTo(-5f, 5f)
        pathStretchV.moveTo(0f, 10f)
        pathStretchV.lineTo(5f, 5f)

        // Box Width: |<->|
        pathBoxWidth.moveTo(-8f, -8f); pathBoxWidth.lineTo(-8f, 8f)
        pathBoxWidth.moveTo(8f, -8f); pathBoxWidth.lineTo(8f, 8f)
        pathBoxWidth.moveTo(-8f, 0f); pathBoxWidth.lineTo(8f, 0f)
        pathBoxWidth.moveTo(-8f, 0f); pathBoxWidth.lineTo(-4f, -4f)
        pathBoxWidth.moveTo(-8f, 0f); pathBoxWidth.lineTo(-4f, 4f)
        pathBoxWidth.moveTo(8f, 0f); pathBoxWidth.lineTo(4f, -4f)
        pathBoxWidth.moveTo(8f, 0f); pathBoxWidth.lineTo(4f, 4f)
    }

    // Interaction Modes
    private enum class Mode {
        NONE,
        DRAG_LAYER,
        ROTATE_LAYER,
        RESIZE_LAYER,
        STRETCH_H,
        STRETCH_V,
        BOX_WIDTH,
        PAN_ZOOM,
        EYEDROPPER,
        EDIT_LAYER,
        PERSPECTIVE_DRAG_TL,
        PERSPECTIVE_DRAG_TR,
        PERSPECTIVE_DRAG_BR,
        PERSPECTIVE_DRAG_BL,
        INPAINT,
        WARP_DRAG,
        ERASE_LAYER,
        CUT_DRAG_TL,
        CUT_DRAG_TR,
        CUT_DRAG_BR,
        CUT_DRAG_BL,
        TYPER,
        GRADATION
    }

    private var currentMode = Mode.NONE

    private var gradationStart: PointF? = null
    private var gradationEnd: PointF? = null
    private val gradationLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 5f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }
    private var detectedBubbles: List<TyperBubble>? = null
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        alpha = 80 // Semi-transparent
    }
    private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    fun setTyperMode(enabled: Boolean) {
        isTyperActive = enabled
        if (enabled) {
            currentMode = Mode.TYPER
            selectLayer(null)
        } else {
            if (currentMode == Mode.TYPER) currentMode = Mode.NONE
        }
        invalidate()
    }

    fun currentModeName(): String {
        return currentMode.name
    }

    fun setGradationMode(enabled: Boolean) {
        isGradationMode = enabled
        if (enabled) {
            currentMode = Mode.GRADATION
            gradationStart = null
            gradationEnd = null
        } else {
            if (currentMode == Mode.GRADATION) {
                currentMode = Mode.NONE
                gradationStart = null
                gradationEnd = null
            }
        }
        invalidate()
    }

    fun setDetectedBubbles(bubbles: List<TyperBubble>) {
        detectedBubbles = bubbles
        invalidate()
    }

    fun getDetectedBubbles(): List<TyperBubble> {
        return detectedBubbles ?: emptyList()
    }

    fun removeDetectedBubble(bubble: TyperBubble) {
        if (detectedBubbles != null) {
            detectedBubbles = detectedBubbles!!.filter { it != bubble }
            invalidate()
        }
    }
    private var warpPointIndex = -1

    var onColorPickedListener: ((Int) -> Unit)? = null

    fun setEyedropperMode(enabled: Boolean) {
        currentMode = if (enabled) Mode.EYEDROPPER else Mode.NONE
        invalidate()
    }

    private fun getPixelColor(x: Float, y: Float): Int {
        if (rawPanelTiles.isNotEmpty()) {
            val rx = if (rawPanelMode == RawPanelMode.ON_TOP) x else x - canvasWidth
            val ry = y

            if (rx >= 0 && rx < rawPanelWidth && ry >= 0 && ry < rawPanelHeight) {
                for (tile in rawPanelTiles) {
                    if (tile.rect.contains(rx, ry)) {
                        val tx = (rx - tile.rect.left).toInt()
                        val ty = (ry - tile.rect.top).toInt()
                        return tile.bitmap.getPixel(tx, ty)
                    }
                }
            }
        }

        if (x < 0 || x >= canvasWidth || y < 0 || y >= canvasHeight) return Color.WHITE
        val bmp = renderToBitmap()
        val pixel = bmp.getPixel(x.toInt(), y.toInt())
        return pixel
    }

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastLocalX = 0f
    private var lastLocalY = 0f

    private fun getLocalPoint(cx: Float, cy: Float, layer: Layer): FloatArray {
        val localPoint = floatArrayOf(cx, cy)
        val globalToLocal = Matrix()
        globalToLocal.postTranslate(-layer.x, -layer.y)
        globalToLocal.postRotate(-layer.rotation)
        globalToLocal.postScale(1/layer.scaleX, 1/layer.scaleY)
        globalToLocal.mapPoints(localPoint)
        return localPoint
    }
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var hasMoved = false
    private var wasSelectedInitially = false
    private var initialRotation = 0f
    private var initialScaleX = 1f
    private var initialScaleY = 1f
    private var initialBoxWidth = 0f
    private var initialFixedHeight = 0f // New property for fixed height resize

    private var centerX = 0f
    private var centerY = 0f
    private var startAngle = 0f
    private var startDist = 0f
    private var startX = 0f
    private var startY = 0f

    private var eyedropperX = 0f
    private var eyedropperY = 0f
    private var eyedropperScreenX = 0f
    private var eyedropperScreenY = 0f

    private val viewMatrix = Matrix()
    private val invertedMatrix = Matrix()

    private fun getCurrentViewScale(): Float {
        val values = FloatArray(9)
        viewMatrix.getValues(values)
        val scaleX = values[Matrix.MSCALE_X]
        val skewY = values[Matrix.MSKEW_Y]
        return kotlin.math.sqrt(scaleX * scaleX + skewY * skewY)
    }

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    interface OnLayerSelectedListener {
        fun onLayerSelected(layer: Layer?)
    }

    interface OnLayerEditListener {
        fun onLayerDoubleTap(layer: Layer)
    }

    interface OnLayerUpdateListener {
        fun onLayerUpdate(layer: Layer)
    }

    var onLayerSelectedListener: OnLayerSelectedListener? = null
    var onLayerEditListener: OnLayerEditListener? = null
    var onLayerUpdateListener: OnLayerUpdateListener? = null
    var onBubbleClickListener: ((TyperBubble) -> Unit)? = null

    fun addTextLayer(text: String) {
        val center = getViewportCenter()
        val layer = TextLayer(text).apply {
            x = center[0]
            y = center[1]
            color = Color.BLACK
        }
        layers.add(layer)
        selectLayer(layer)
    }

    fun addBrushLayer() {
        val layer = com.astral.typer.models.BrushLayer(canvasWidth, canvasHeight).apply {
            name = "Brush ${layers.count { it is com.astral.typer.models.BrushLayer } + 1}"
        }
        layers.add(layer)
        selectLayer(layer)
    }

    fun addShapeLayer(shapeName: String) {
        val center = getViewportCenter()
        val layer = com.astral.typer.models.ShapeLayer(shapeName).apply {
            x = center[0]
            y = center[1]
            color = Color.BLACK

            // Calculate initial scale for reasonable size
            val w = getWidth(); val h = getHeight()
            val targetDim = 300f
            val scale = if (w > h) targetDim / w else targetDim / h
            scaleX = scale; scaleY = scale
        }
        layers.add(layer)
        selectLayer(layer)
    }

    fun addImageLayer(bitmap: android.graphics.Bitmap, path: String? = null) {
        var scale = 1f
        if (bitmap.width > canvasWidth * 0.8f) {
            scale = (canvasWidth * 0.8f) / bitmap.width
        }

        val center = getViewportCenter()
        val layer = ImageLayer(bitmap, path).apply {
            x = center[0]
            y = center[1]
            this.scaleX = scale
            this.scaleY = scale
        }
        layers.add(layer)
        selectLayer(layer)
    }

    fun setRawPanelImage(bitmap: android.graphics.Bitmap?) {
        for (tile in rawPanelTiles) {
            tile.bitmap.recycle()
        }
        rawPanelTiles.clear()

        if (bitmap != null) {
            rawPanelWidth = bitmap.width
            rawPanelHeight = bitmap.height
            rawPanelTiles.addAll(createTilesFromBitmap(bitmap))
        } else {
            rawPanelWidth = 0
            rawPanelHeight = 0
        }
        invalidate()
    }

    fun selectLayer(layer: Layer?) {
        if (selectedLayer != layer) {
            selectedLayer = layer
            layers.forEach { it.isSelected = (it == layer) }
            onLayerSelectedListener?.onLayerSelected(layer)
            isPerspectiveMode = false
            exitCutMode()
            invalidate()
        } else {
            layers.forEach { it.isSelected = (it == layer) }
        }
    }

    fun deleteSelectedLayer() {
        selectedLayer?.let {
            com.astral.typer.utils.UndoManager.saveState(layers)
            removeLayerAndClipped(it)
        }
    }

    fun removeLayer(layer: Layer) {
        com.astral.typer.utils.UndoManager.saveState(layers)
        removeLayerAndClipped(layer)
    }

    private fun removeLayerAndClipped(layer: Layer) {
        val index = layers.indexOf(layer)
        if (index != -1) {
            var countToDelete = 1
            while (index + countToDelete < layers.size && layers[index + countToDelete].isClipped) {
                countToDelete++
            }

            // Check if selectedLayer is one of the layers to be deleted
            var shouldDeselect = false
            for (i in 0 until countToDelete) {
                if (selectedLayer == layers[index + i]) {
                    shouldDeselect = true
                    break
                }
            }

            // Delete them
            for (i in 0 until countToDelete) {
                layers.removeAt(index)
            }

            if (shouldDeselect) {
                selectLayer(null)
            } else {
                invalidate()
            }
        }
    }

    fun moveLayerBlock(fromAdapterPos: Int, toAdapterPos: Int): Boolean {
        val fromListIdx = layers.size - 1 - fromAdapterPos
        val toListIdx = layers.size - 1 - toAdapterPos

        if (fromListIdx < 0 || fromListIdx >= layers.size || toListIdx < 0 || toListIdx >= layers.size) return false
        if (fromListIdx == toListIdx) return false

        // Group into blocks
        val blocks = mutableListOf<MutableList<Layer>>()
        var currentBlock: MutableList<Layer>? = null
        for (layer in layers) {
            if (!layer.isClipped || currentBlock == null) {
                currentBlock = mutableListOf(layer)
                blocks.add(currentBlock)
            } else {
                currentBlock.add(layer)
            }
        }

        val fromLayer = layers[fromListIdx]
        val toLayer = layers[toListIdx]

        var fromBlockIdx = -1
        var toBlockIdx = -1
        for (i in 0 until blocks.size) {
            if (blocks[i].contains(fromLayer)) {
                fromBlockIdx = i
            }
            if (blocks[i].contains(toLayer)) {
                toBlockIdx = i
            }
        }

        if (fromBlockIdx == -1 || toBlockIdx == -1) return false

        if (fromBlockIdx == toBlockIdx) {
            // Internal rearrangement within the same block
            val block = blocks[fromBlockIdx]
            val fromInBlockIdx = block.indexOf(fromLayer)
            val toInBlockIdx = block.indexOf(toLayer)
            if (fromInBlockIdx != -1 && toInBlockIdx != -1) {
                block.removeAt(fromInBlockIdx)
                block.add(toInBlockIdx, fromLayer)
                // Enforce clipping rules
                for (j in 0 until block.size) {
                    block[j].isClipped = (j > 0)
                }
            }
        } else {
            // Move block to new position
            val blockToMove = blocks.removeAt(fromBlockIdx)
            blocks.add(toBlockIdx, blockToMove)
        }

        // Reconstruct layers list
        layers.clear()
        for (b in blocks) {
            layers.addAll(b)
        }

        invalidate()
        return true
    }

    fun initCanvas(width: Int, height: Int, color: Int) {
        canvasWidth = width
        canvasHeight = height
        canvasColor = color

        backgroundRect.set(0f, 0f, width.toFloat(), height.toFloat())

        for (tile in backgroundTiles) {
            tile.bitmap.recycle()
        }
        backgroundTiles.clear()
        isBackgroundModified = false

        for (tile in rawPanelTiles) {
            tile.bitmap.recycle()
        }
        rawPanelTiles.clear()
        rawPanelWidth = 0
        rawPanelHeight = 0

        post {
             centerCanvas()
        }
    }

    private fun createTilesFromBitmap(bitmap: android.graphics.Bitmap): List<ImageTile> {
        val tiles = mutableListOf<ImageTile>()
        val w = bitmap.width
        val h = bitmap.height

        for (y in 0 until h step TILE_SIZE) {
            for (x in 0 until w step TILE_SIZE) {
                val tileW = min(TILE_SIZE, w - x)
                val tileH = min(TILE_SIZE, h - y)

                val tileBitmap = android.graphics.Bitmap.createBitmap(bitmap, x, y, tileW, tileH)
                val tileRect = RectF(x.toFloat(), y.toFloat(), (x + tileW).toFloat(), (y + tileH).toFloat())

                tiles.add(ImageTile(tileBitmap, tileRect))
            }
        }
        return tiles
    }

    fun setBackgroundImage(bitmap: android.graphics.Bitmap) {
        for (tile in backgroundTiles) {
            tile.bitmap.recycle()
        }
        backgroundTiles.clear()
        backgroundTiles.addAll(createTilesFromBitmap(bitmap))
        isBackgroundModified = true
        invalidate()
    }

    private fun renderToBitmapHardware(scale: Float = 1f): android.graphics.Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return null
        val limit = com.astral.typer.utils.ProjectManager.getMaxTextureSize()
        val maxDim = minOf(2048, limit)
        val scaledWidth = (canvasWidth * scale).toInt()
        val scaledHeight = (canvasHeight * scale).toInt()
        if (scaledWidth <= maxDim && scaledHeight <= maxDim) {
            try {
                val reader = android.media.ImageReader.newInstance(
                    scaledWidth, scaledHeight,
                    android.graphics.PixelFormat.RGBA_8888, 1,
                    android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                )
                val surface = reader.surface

                val rootNode = android.graphics.RenderNode("RootNode")
                rootNode.setPosition(0, 0, scaledWidth, scaledHeight)
                val canvas = rootNode.beginRecording()

                canvas.scale(scale, scale)

                // Draw Background
                val bgPaint = Paint()
                bgPaint.color = canvasColor
                bgPaint.style = Paint.Style.FILL
                canvas.drawRect(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat(), bgPaint)

                // Draw Background Tiles
                val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
                for (tile in backgroundTiles) {
                    canvas.drawBitmap(tile.bitmap, tile.rect.left, tile.rect.top, tilePaint)
                }

                // Draw Layers
                drawLayers(canvas, layers)
                rootNode.endRecording()

                val renderer = android.graphics.HardwareRenderer()
                renderer.setContentRoot(rootNode)
                renderer.setSurface(surface)
                renderer.setLightSourceAlpha(0f, 0f)
                renderer.setLightSourceGeometry(0f, 0f, 0f, 1f)

                val request = renderer.createRenderRequest()
                request.setWaitForPresent(true)
                request.syncAndDraw()

                val image = reader.acquireNextImage()
                if (image != null) {
                    val hardwareBuffer = image.hardwareBuffer
                    if (hardwareBuffer != null) {
                        val bmp = android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
                        val softwareBmp = bmp?.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                        hardwareBuffer.close()
                        image.close()
                        renderer.destroy()
                        reader.close()
                        return softwareBmp
                    }
                    image.close()
                }
                renderer.destroy()
                reader.close()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            return null
        } else {
            try {
                val finalBitmap = android.graphics.Bitmap.createBitmap(scaledWidth, scaledHeight, android.graphics.Bitmap.Config.ARGB_8888)
                val finalCanvas = Canvas(finalBitmap)
                for (y in 0 until scaledHeight step maxDim) {
                    val sliceH = min(maxDim, scaledHeight - y)
                    for (x in 0 until scaledWidth step maxDim) {
                        val sliceW = min(maxDim, scaledWidth - x)
                        val sliceBmp = renderSliceHardware(x, y, sliceW, sliceH, scale)
                        if (sliceBmp != null) {
                            finalCanvas.drawBitmap(sliceBmp, x.toFloat(), y.toFloat(), null)
                            sliceBmp.recycle()
                        } else {
                            finalBitmap.recycle()
                            return null
                        }
                    }
                }
                return finalBitmap
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            return null
        }
    }

    private fun renderSliceHardware(startX: Int, startY: Int, sliceW: Int, sliceH: Int, scale: Float = 1f): android.graphics.Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return null
        try {
            val reader = android.media.ImageReader.newInstance(
                sliceW, sliceH,
                android.graphics.PixelFormat.RGBA_8888, 1,
                android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
            )
            val surface = reader.surface

            val rootNode = android.graphics.RenderNode("SliceNode")
            rootNode.setPosition(0, 0, sliceW, sliceH)
            val canvas = rootNode.beginRecording()

            canvas.translate(-startX.toFloat(), -startY.toFloat())
            canvas.scale(scale, scale)

            // Draw Background
            val bgPaint = Paint()
            bgPaint.color = canvasColor
            bgPaint.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat(), bgPaint)

            // Draw Background Tiles
            val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (tile in backgroundTiles) {
                canvas.drawBitmap(tile.bitmap, tile.rect.left, tile.rect.top, tilePaint)
            }

            // Draw Layers
            drawLayers(canvas, layers)
            rootNode.endRecording()

            val renderer = android.graphics.HardwareRenderer()
            renderer.setContentRoot(rootNode)
            renderer.setSurface(surface)
            renderer.setLightSourceAlpha(0f, 0f)
            renderer.setLightSourceGeometry(0f, 0f, 0f, 1f)

            val request = renderer.createRenderRequest()
            request.setWaitForPresent(true)
            request.syncAndDraw()

            val image = reader.acquireNextImage()
            if (image != null) {
                val hardwareBuffer = image.hardwareBuffer
                if (hardwareBuffer != null) {
                    val bmp = android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
                    val softwareBmp = bmp?.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                    hardwareBuffer.close()
                    image.close()
                    renderer.destroy()
                    reader.close()
                    return softwareBmp
                }
                image.close()
            }
            renderer.destroy()
            reader.close()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return null
    }

    private fun isBitmapBlankOrBlack(bitmap: android.graphics.Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return true

        val readableBitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            bitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
            bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }

        if (readableBitmap == null) return true

        val firstPixel = readableBitmap.getPixel(0, 0)
        if (firstPixel != 0 && firstPixel != Color.BLACK) {
            if (readableBitmap !== bitmap) {
                readableBitmap.recycle()
            }
            return false
        }

        val stepX = (width / 20).coerceAtLeast(1)
        val stepY = (height / 20).coerceAtLeast(1)

        for (y in 0 until height step stepY) {
            for (x in 0 until width step stepX) {
                val pixel = readableBitmap.getPixel(x, y)
                if (pixel != firstPixel) {
                    if (readableBitmap !== bitmap) {
                        readableBitmap.recycle()
                    }
                    return false
                }
            }
        }

        val checkPoints = arrayOf(
            0 to 0,
            width - 1 to 0,
            0 to height - 1,
            width - 1 to height - 1,
            width / 2 to height / 2
        )
        for ((cx, cy) in checkPoints) {
            if (cx in 0 until width && cy in 0 until height) {
                if (readableBitmap.getPixel(cx, cy) != firstPixel) {
                    if (readableBitmap !== bitmap) {
                        readableBitmap.recycle()
                    }
                    return false
                }
            }
        }

        if (readableBitmap !== bitmap) {
            readableBitmap.recycle()
        }
        return true
    }

    private fun isBitmapCropped(bitmap: android.graphics.Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 10 || h <= 10) return false

        val hasBackground = backgroundTiles.isNotEmpty() || canvasColor != android.graphics.Color.TRANSPARENT
        if (hasBackground) {
            var rightColumnIsZero = true
            for (y in 0 until h step (h / 10).coerceAtLeast(1)) {
                if (bitmap.getPixel(w - 1, y) != 0) {
                    rightColumnIsZero = false
                    break
                }
            }
            if (rightColumnIsZero) return true

            var bottomRowIsZero = true
            for (x in 0 until w step (w / 10).coerceAtLeast(1)) {
                if (bitmap.getPixel(x, h - 1) != 0) {
                    bottomRowIsZero = false
                    break
                }
            }
            if (bottomRowIsZero) return true
        }
        return false
    }

    fun renderToBitmap(scale: Float = 1f): android.graphics.Bitmap {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val hwBmp = renderToBitmapHardware(scale)
            if (hwBmp != null && !isBitmapBlankOrBlack(hwBmp) && !isBitmapCropped(hwBmp)) {
                return hwBmp
            }
        }

        val scaledWidth = (canvasWidth * scale).toInt()
        val scaledHeight = (canvasHeight * scale).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(scaledWidth, scaledHeight, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.scale(scale, scale)

        // Draw Background
        val bgPaint = Paint()
        bgPaint.color = canvasColor
        bgPaint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat(), bgPaint)

        // Draw Background Tiles
        val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (tile in backgroundTiles) {
            canvas.drawBitmap(tile.bitmap, tile.rect.left, tile.rect.top, tilePaint)
        }

        // Draw Layers
        drawLayers(canvas, layers)

        return bitmap
    }

    private fun centerCanvas() {
        if (width == 0 || height == 0) return

        val scaleX = width.toFloat() / canvasWidth
        val scaleY = height.toFloat() / canvasHeight

        val scale = minOf(scaleX, scaleY) * 0.8f

        val dx = (width - canvasWidth * scale) / 2f
        val dy = (height - canvasHeight * scale) / 2f

        viewMatrix.reset()
        viewMatrix.postScale(scale, scale)
        viewMatrix.postTranslate(dx, dy)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save()
        canvas.concat(viewMatrix)

        // Draw Canvas Background
        paint.color = canvasColor
        paint.style = Paint.Style.FILL
        canvas.drawRect(backgroundRect, paint)

        // Draw Background Tiles with Frustum Culling
        if (backgroundTiles.isNotEmpty()) {
            val visibleViewport = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val inverse = Matrix()
            viewMatrix.invert(inverse)
            inverse.mapRect(visibleViewport)

            val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (tile in backgroundTiles) {
                if (RectF.intersects(tile.rect, visibleViewport)) {
                    canvas.drawBitmap(tile.bitmap, tile.rect.left, tile.rect.top, tilePaint)
                }
            }
        }

        // Draw Border
        paint.color = Color.LTGRAY
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRect(backgroundRect, paint)

        // Draw Layers
        drawScene(canvas)

        // Draw RAW Panel with Frustum Culling
        if (rawPanelTiles.isNotEmpty()) {
            val visibleViewport = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val inverse = Matrix()
            viewMatrix.invert(inverse)
            inverse.mapRect(visibleViewport)

            val rawPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            rawPaint.alpha = rawPanelOpacity

            canvas.save()
            if (rawPanelMode == RawPanelMode.BESIDE) {
                canvas.translate(canvasWidth.toFloat(), 0f)
                visibleViewport.offset(-canvasWidth.toFloat(), 0f)
            }

            for (tile in rawPanelTiles) {
                if (RectF.intersects(tile.rect, visibleViewport)) {
                    canvas.drawBitmap(tile.bitmap, tile.rect.left, tile.rect.top, rawPaint)
                }
            }
            canvas.restore()
        }

        // Draw Detected Bubbles (Bottom Layer overlay)
        if (isTyperActive && detectedBubbles != null) {
            for (bubble in detectedBubbles!!) {
                if (bubble.isOval) {
                    canvas.drawOval(bubble.rect, bubblePaint)
                    canvas.drawOval(bubble.rect, bubbleStrokePaint)
                } else {
                    canvas.drawRect(bubble.rect, bubblePaint)
                    canvas.drawRect(bubble.rect, bubbleStrokePaint)
                }
            }

            // Draw Typer Current Path
            if (!currentTyperPath.isEmpty) {
                 if (currentTyperTool == TyperTool.LASSO) {
                     canvas.drawPath(currentTyperPath, lassoStrokePaint)
                 } else if (currentTyperTool == TyperTool.RECT) {
                     canvas.drawPath(currentTyperPath, bubbleStrokePaint)
                 } else if (currentTyperTool == TyperTool.CIRCLE) {
                     canvas.drawPath(currentTyperPath, bubbleStrokePaint)
                 }
            }
        }

        // Draw Inpaint Path (Vector optimized, no cache bitmap)
        if (isInpaintMode) {
            val inpaintBounds = RectF()
            for (op in inpaintOps) {
                val tempBounds = RectF()
                op.first.computeBounds(tempBounds, true)
                inpaintBounds.union(tempBounds)
            }
            if (!currentInpaintPath.isEmpty) {
                val tempBounds = RectF()
                currentInpaintPath.computeBounds(tempBounds, true)
                inpaintBounds.union(tempBounds)
            }
            inpaintBounds.inset(-brushSize, -brushSize)

            val saveCount = canvas.saveLayer(inpaintBounds, null)

            // Draw Paths directly
            val brushP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.RED
                style = Paint.Style.STROKE
                strokeWidth = brushSize
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                alpha = 128
            }

            val eraseP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                style = Paint.Style.STROKE
                strokeWidth = brushSize
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val lassoP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.RED
                style = Paint.Style.FILL
                alpha = 128
            }
            val lassoEraseP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                style = Paint.Style.FILL
            }

            // Draw History
            for ((path, tool) in inpaintOps) {
                when(tool) {
                    InpaintTool.BRUSH -> canvas.drawPath(path, brushP)
                    InpaintTool.ERASER -> canvas.drawPath(path, eraseP)
                    InpaintTool.LASSO -> canvas.drawPath(path, lassoP)
                    InpaintTool.LASSO_ERASER -> canvas.drawPath(path, lassoEraseP)
                    InpaintTool.MAGIC_WAND -> canvas.drawPath(path, lassoP)
                    InpaintTool.MAGIC_WAND_ERASER -> canvas.drawPath(path, lassoEraseP)
                }
            }

            // Draw Current Path
            if (!currentInpaintPath.isEmpty) {
                 when(currentInpaintTool) {
                    InpaintTool.BRUSH -> canvas.drawPath(currentInpaintPath, inpaintPaint)
                    InpaintTool.ERASER -> canvas.drawPath(currentInpaintPath, eraserPaint)
                    InpaintTool.LASSO -> canvas.drawPath(currentInpaintPath, lassoStrokePaint)
                    InpaintTool.LASSO_ERASER -> canvas.drawPath(currentInpaintPath, lassoStrokePaint)
                    InpaintTool.MAGIC_WAND -> {}
                    InpaintTool.MAGIC_WAND_ERASER -> {}
                }
            }
            canvas.restoreToCount(saveCount)
        }

        // Draw Selection Overlay
        if (currentMode != Mode.EYEDROPPER && !isInpaintMode && currentMode != Mode.GRADATION) {
             selectedLayer?.let { drawSelectionOverlay(canvas, it) }
        }

        // Draw Gradation Line
        if (currentMode == Mode.GRADATION) {
            val p1 = gradationStart
            val p2 = gradationEnd
            if (p1 != null && p2 != null) {
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, gradationLinePaint)
            }
        }

        canvas.restore()

        // Draw Eraser Size Preview in Screen Space (Viewport-centric)
        if (isEraseLayerMode && showEraseSizePreview) {
            val cx = width / 2f
            val cy = height / 2f
            val values = FloatArray(9)
            viewMatrix.getValues(values)
            val scale = values[android.graphics.Matrix.MSCALE_X]
            val radius = (layerEraseSize / 2f) * if (scale != 0f) scale else 1.0f

            val pShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
                color = Color.BLACK
            }
            val pCircle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.WHITE
            }
            canvas.drawCircle(cx, cy, radius, pShadow)
            canvas.drawCircle(cx, cy, radius, pCircle)
        }

        // Draw Grid Lines (Screen Space)
        if (showVerticalCenterLine) {
            val pts = floatArrayOf(canvasWidth / 2f, 0f, canvasWidth / 2f, canvasHeight.toFloat())
            viewMatrix.mapPoints(pts)
            canvas.drawLine(pts[0], pts[1], pts[2], pts[3], snapPaint)
        }
        if (showHorizontalCenterLine) {
            val pts = floatArrayOf(0f, canvasHeight / 2f, canvasWidth.toFloat(), canvasHeight / 2f)
            viewMatrix.mapPoints(pts)
            canvas.drawLine(pts[0], pts[1], pts[2], pts[3], snapPaint)
        }

        // Draw Eyedropper UI
        if (currentMode == Mode.EYEDROPPER) {
             paint.style = Paint.Style.STROKE
             paint.color = Color.BLACK
             paint.strokeWidth = 2f
             val size = 30f
             canvas.drawLine(eyedropperScreenX - size, eyedropperScreenY, eyedropperScreenX + size, eyedropperScreenY, paint)
             canvas.drawLine(eyedropperScreenX, eyedropperScreenY - size, eyedropperScreenX, eyedropperScreenY + size, paint)
             paint.color = Color.WHITE
             paint.strokeWidth = 1f
             canvas.drawLine(eyedropperScreenX - size, eyedropperScreenY - 1f, eyedropperScreenX + size, eyedropperScreenY - 1f, paint)

             canvas.save()
             val boxSize = 200f
             val boxMargin = 30f
             val boxRect = RectF(width - boxSize - boxMargin, boxMargin, width - boxMargin, boxMargin + boxSize)

             paint.style = Paint.Style.FILL
             paint.color = Color.BLACK
             canvas.drawRect(boxRect, paint)

             canvas.save()
             canvas.clipRect(boxRect)
             canvas.translate(boxRect.centerX(), boxRect.centerY())
             val zoomLevel = 4f
             canvas.scale(zoomLevel, zoomLevel)
             canvas.translate(-eyedropperX, -eyedropperY)

             paint.color = canvasColor
             paint.style = Paint.Style.FILL
             canvas.drawRect(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat(), paint)

             // Draw Tiles in Eyedropper
             val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
             for (tile in backgroundTiles) {
                 canvas.drawBitmap(tile.bitmap, tile.rect.left, tile.rect.top, tilePaint)
             }

             drawLayers(canvas, layers)

             // Draw RAW Panel in Magnifying Glass
             if (rawPanelTiles.isNotEmpty()) {
                 val rawPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                 rawPaint.alpha = rawPanelOpacity

                 canvas.save()
                 if (rawPanelMode == RawPanelMode.BESIDE) {
                     canvas.translate(canvasWidth.toFloat(), 0f)
                 }

                 for (tile in rawPanelTiles) {
                     canvas.drawBitmap(tile.bitmap, tile.rect.left, tile.rect.top, rawPaint)
                 }
                 canvas.restore()
             }

             canvas.restore()

             paint.style = Paint.Style.STROKE
             paint.color = Color.WHITE
             paint.strokeWidth = 4f
             canvas.drawRect(boxRect, paint)

             paint.color = Color.RED
             paint.strokeWidth = 2f
             canvas.drawLine(boxRect.centerX() - 10, boxRect.centerY(), boxRect.centerX() + 10, boxRect.centerY(), paint)
             canvas.drawLine(boxRect.centerX(), boxRect.centerY() - 10, boxRect.centerX(), boxRect.centerY() + 10, paint)

             canvas.restore()
        }
    }

    private fun drawChain(canvas: Canvas, layersList: List<Layer>, index: Int, viewport: RectF? = null, skipEffects: Boolean = false, viewScale: Float = 1.0f): Int {
        val layer = layersList[index]
        val nextIndex = index + 1
        val hasClippedChildren = nextIndex < layersList.size && layersList[nextIndex].isClipped && layersList[nextIndex].isVisible

        val isVisibleInViewport = viewport == null || RectF.intersects(layer.getCanvasBounds(), viewport)

        if (hasClippedChildren) {
            if (!isVisibleInViewport) {
                // If base layer is outside viewport, check if any clipped children are visible
                var scan = nextIndex
                var hasVisibleChild = false
                while (scan < layersList.size && layersList[scan].isClipped) {
                    if (layersList[scan].isVisible && (viewport == null || RectF.intersects(layersList[scan].getCanvasBounds(), viewport))) {
                        hasVisibleChild = true
                        break
                    }
                    scan++
                }
                if (!hasVisibleChild) {
                    var endScan = nextIndex
                    while (endScan < layersList.size && layersList[endScan].isClipped) {
                        endScan++
                    }
                    return endScan
                }
            }

            // First, draw the base layer normally (with strokes, shadows, fill, etc.)
            val saveNormal = canvas.saveLayer(null, null)
            layer.isDrawingClippingMask = false
            layer.draw(canvas, skipEffects, viewScale)
            canvas.restoreToCount(saveNormal)

            // Now draw the clipping mask and clipped layers on top of it
            val saveCount = canvas.saveLayer(null, null)
            layer.isDrawingClippingMask = true
            layer.draw(canvas, skipEffects, viewScale)
            layer.isDrawingClippingMask = false

            val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)
            }
            canvas.saveLayer(null, clipPaint)
            val finalIndex = drawChain(canvas, layersList, nextIndex, viewport, skipEffects, viewScale)
            canvas.restore()
            canvas.restoreToCount(saveCount)
            return finalIndex
        } else {
            if (isVisibleInViewport) {
                val saveCount = canvas.saveLayer(null, null)
                layer.isDrawingClippingMask = false
                layer.draw(canvas, skipEffects, viewScale)
                canvas.restoreToCount(saveCount)
            }

            if (nextIndex < layersList.size && layersList[nextIndex].isClipped) {
                var scan = nextIndex
                while (scan < layersList.size && layersList[scan].isClipped) {
                    scan++
                }
                return scan
            }
            return nextIndex
        }
    }

    private fun drawLayers(canvas: Canvas, layersList: List<Layer>, viewport: RectF? = null, skipEffects: Boolean = false, viewScale: Float = 1.0f) {
        var i = 0
        while (i < layersList.size) {
            val layer = layersList[i]
            if (!layer.isVisible) {
                i++
                while (i < layersList.size && layersList[i].isClipped) {
                    i++
                }
                continue
            }
            i = drawChain(canvas, layersList, i, viewport, skipEffects, viewScale)
        }
    }

    private fun drawScene(canvas: Canvas) {
        val visibleViewport = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val inverse = Matrix()
        viewMatrix.invert(inverse)
        inverse.mapRect(visibleViewport)

        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        val disableFastInteraction = prefs.getBoolean("disable_fast_interaction", false)
        val isFastInteraction = !disableFastInteraction && currentMode == Mode.PAN_ZOOM

        val disableLOD = prefs.getBoolean("disable_lod_scaling", false)
        val viewScale = if (disableLOD) 1.0f else getCurrentViewScale()

        drawLayers(canvas, layers, visibleViewport, isFastInteraction, viewScale)
    }

    private fun drawSelectionOverlay(canvas: Canvas, layer: Layer) {
        if (layer is com.astral.typer.models.BrushLayer) {
            return
        }
        canvas.save()
        canvas.translate(layer.x, layer.y)
        canvas.rotate(layer.rotation)
        canvas.scale(layer.scaleX, layer.scaleY)

        if (cutPoints != null && layer is ImageLayer) {
             val pts = cutPoints!!
             val viewScale = getCurrentViewScale()
             paint.style = Paint.Style.STROKE
             paint.color = Color.MAGENTA
             paint.strokeWidth = 2f / (((abs(layer.scaleX) + abs(layer.scaleY))/2f) * viewScale)
             val path = Path()
             path.moveTo(pts[0], pts[1])
             path.lineTo(pts[2], pts[3])
             path.lineTo(pts[4], pts[5])
             path.lineTo(pts[6], pts[7])
             path.close()
             canvas.drawPath(path, paint)

             val handleRadius = 20f / (((abs(layer.scaleX) + abs(layer.scaleY))/2f) * viewScale)
             handlePaint.color = Color.MAGENTA

             canvas.drawCircle(pts[0], pts[1], handleRadius, handlePaint)
             canvas.drawCircle(pts[2], pts[3], handleRadius, handlePaint)
             canvas.drawCircle(pts[4], pts[5], handleRadius, handlePaint)
             canvas.drawCircle(pts[6], pts[7], handleRadius, handlePaint)

             canvas.restore()
             return
        }

        val stylableLayer = layer as? com.astral.typer.models.StylableLayer
        val isWarpActive = stylableLayer?.isWarp ?: false

        if (isWarpActive && (isWarpToolActive || isPerspectiveMode) && stylableLayer != null) {
            val mesh = stylableLayer.warpMesh
            val rows = stylableLayer.warpRows
            val cols = stylableLayer.warpCols
            val viewScale = getCurrentViewScale()
            if (mesh != null) {
                paint.style = Paint.Style.STROKE
                paint.color = Color.CYAN
                paint.strokeWidth = 2f / viewScale

                val denseSteps = 20
                val outPoint = FloatArray(2)

                fun eval(u: Float, v: Float, out: FloatArray) {
                    layer.evaluateBezierSurface(u, v, out)
                }

                for (r in 0..rows) {
                    val path = Path()
                    val v = r.toFloat() / rows
                    eval(0f, v, outPoint)
                    path.moveTo(outPoint[0], outPoint[1])
                    for (step in 1..denseSteps) {
                        eval(step.toFloat() / denseSteps, v, outPoint)
                        path.lineTo(outPoint[0], outPoint[1])
                    }
                    canvas.drawPath(path, paint)
                }
                for (c in 0..cols) {
                    val path = Path()
                    val u = c.toFloat() / cols
                    eval(u, 0f, outPoint)
                    path.moveTo(outPoint[0], outPoint[1])
                    for (step in 1..denseSteps) {
                        eval(u, step.toFloat() / denseSteps, outPoint)
                        path.lineTo(outPoint[0], outPoint[1])
                    }
                    canvas.drawPath(path, paint)
                }
                val handleRadius = 15f / (((abs(layer.scaleX) + abs(layer.scaleY))/2f) * viewScale)
                handlePaint.color = Color.YELLOW
                for (i in 0 until (mesh.size / 2)) canvas.drawCircle(mesh[i*2], mesh[i*2+1], handleRadius, handlePaint)
            }
            canvas.restore()
            return
        }

        val halfW = layer.getWidth() / 2f
        val halfH = layer.getHeight() / 2f

        val geometry = getHandleGeometry(layer)
        val handleOffset = geometry.offset
        val localIconScale = geometry.scale
        val avgScale = (abs(layer.scaleX) + abs(layer.scaleY)) / 2f

        val isSpecialMode = (stylableLayer != null && (stylableLayer.isWarp && (isWarpToolActive || isPerspectiveMode)))

        if (!isSpecialMode) {
            paint.style = Paint.Style.STROKE
            paint.color = Color.BLUE
            val viewScale = getCurrentViewScale()
            paint.strokeWidth = 3f / (avgScale * viewScale)
            val box = RectF(-halfW - 10, -halfH - 10, halfW + 10, halfH + 10)

            if ((layer is TextLayer && layer.isOval)) {
                canvas.drawOval(box, paint)
            } else {
                canvas.drawRect(box, paint)
            }
        }

        if (isEraseLayerMode) {
            canvas.restore()
            return
        }

        fun drawIconHandle(x: Float, y: Float, path: Path, iconColor: Int, useStroke: Boolean = true) {
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(localIconScale, localIconScale)

            iconPaint.color = iconColor
            iconPaint.strokeWidth = 3f
            iconPaint.style = Paint.Style.STROKE

            val shadowPaint = Paint(iconPaint).apply {
                this.color = Color.BLACK
                this.strokeWidth = 5f
            }
            if (useStroke) canvas.drawPath(path, shadowPaint)
            canvas.drawPath(path, iconPaint)

            canvas.restore()
        }

        val isTyperHand = isTyperActive && currentTyperTool == TyperTool.HAND

        if (!isSpecialMode) {
            drawIconHandle(-halfW - handleOffset, -halfH - handleOffset, pathDelete, Color.RED)
            drawIconHandle(halfW + handleOffset, -halfH - handleOffset, pathRotate, Color.GREEN)
            drawIconHandle(halfW + handleOffset, halfH + handleOffset, pathResize, Color.BLUE)
            if (!isTyperHand) {
                drawIconHandle(-halfW - handleOffset, 0f, pathStretchH, Color.DKGRAY)
                drawIconHandle(0f, halfH + handleOffset, pathStretchV, Color.DKGRAY)
            }

            if (layer is TextLayer) {
                drawIconHandle(halfW + handleOffset, 0f, pathBoxWidth, Color.MAGENTA)
            }
        }

        if (currentMode == Mode.RESIZE_LAYER && (layer is TextLayer || layer is ShapeLayer || layer is ImageLayer) && this.selectedLayer == layer) {
             canvas.save()
             // Move to top-right handle position
             canvas.translate(halfW + handleOffset, -halfH - handleOffset)
             // We want the text to be right-side up, so we undo the layer's rotation and scale
             canvas.scale(1 / layer.scaleX, 1 / layer.scaleY)
             canvas.rotate(-layer.rotation)

             val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                 color = Color.WHITE
                 textSize = 40f // Fixed screen size
                 setShadowLayer(5f, 0f, 0f, Color.BLACK)
                 textAlign = Paint.Align.LEFT
             }
             val scaleText = "${(layer.scale * 100).toInt()}%"

             // Draw text slightly offset to top-right to avoid overlapping the handle
             canvas.drawText(scaleText, 30f, -10f, textPaint)
             canvas.restore()
        }

        if (currentMode == Mode.ROTATE_LAYER && this.selectedLayer == layer) {
             canvas.save()
             // Move to top-right handle position (Rotate Handle)
             canvas.translate(halfW + handleOffset, -halfH - handleOffset)
             canvas.scale(1 / layer.scaleX, 1 / layer.scaleY)
             canvas.rotate(-layer.rotation)

             val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                 color = Color.WHITE
                 textSize = 40f
                 setShadowLayer(5f, 0f, 0f, Color.BLACK)
                 textAlign = Paint.Align.LEFT
             }
             // Normalize rotation to 0-359 for display
             var displayRot = layer.rotation % 360
             if (displayRot < 0) displayRot += 360
             val rotateText = "${displayRot.toInt()}°"

             canvas.drawText(rotateText, 30f, -10f, textPaint)
             canvas.restore()
        }

        if (!isTyperHand) {
            val topY = -halfH - handleOffset * 2.5f
            val iconSpacing = geometry.radius * 2.5f

            val dupX = -iconSpacing / 1.5f

            canvas.save()
            canvas.translate(dupX, topY)
            canvas.scale(localIconScale, localIconScale)

            val dupP = Paint(iconPaint).apply { color = Color.LTGRAY; style = Paint.Style.STROKE }
            val dupShadow = Paint(dupP).apply { color = Color.BLACK; strokeWidth = 5f }

            val r1 = RectF(-8f, -8f, 2f, 2f)
            val r2 = RectF(-2f, -2f, 8f, 8f)

            canvas.drawRect(r1, dupShadow); canvas.drawRect(r2, dupShadow)
            canvas.drawRect(r1, dupP); canvas.drawRect(r2, dupP)

            canvas.restore()

            val copyX = iconSpacing / 1.5f
            canvas.save()
            canvas.translate(copyX, topY)
            canvas.scale(localIconScale, localIconScale)

            val copyP = Paint(iconPaint).apply { color = Color.YELLOW; style = Paint.Style.STROKE }
            val copyShadow = Paint(copyP).apply { color = Color.BLACK; strokeWidth = 5f }

            canvas.drawCircle(0f, 0f, 8f, copyShadow)
            canvas.drawCircle(0f, 0f, 8f, copyP)

            val fillP = Paint(copyP).apply { style = Paint.Style.FILL; alpha = 150 }
            canvas.drawCircle(0f, 0f, 5f, fillP)

            canvas.restore()
        }

        canvas.restore()
    }

    private fun lineIntersectsLayer(p1: PointF, p2: PointF, layer: Layer): Boolean {
        val inverse = Matrix()
        val matrix = Matrix()
        matrix.setTranslate(layer.x, layer.y)
        matrix.preRotate(layer.rotation)
        matrix.preScale(layer.scaleX, layer.scaleY)
        if (!matrix.invert(inverse)) return false

        val pts = floatArrayOf(p1.x, p1.y, p2.x, p2.y)
        inverse.mapPoints(pts)

        val lx1 = pts[0]
        val ly1 = pts[1]
        val lx2 = pts[2]
        val ly2 = pts[3]

        val halfW = layer.getWidth() / 2f
        val halfH = layer.getHeight() / 2f

        return lineIntersectsRect(lx1, ly1, lx2, ly2, -halfW, -halfH, halfW, halfH)
    }

    private fun lineIntersectsRect(x1: Float, y1: Float, x2: Float, y2: Float, left: Float, top: Float, right: Float, bottom: Float): Boolean {
        // Liang-Barsky algorithm or simpler Cohen-Sutherland approach
        // Even simpler: segment-rect intersection using min/max t
        var tmin = 0f
        var tmax = 1f
        val dx = x2 - x1
        val dy = y2 - y1

        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(x1 - left, right - x1, y1 - top, bottom - y1)

        for (i in 0..3) {
            if (p[i] == 0f) {
                if (q[i] < 0) return false
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0) {
                    if (t > tmax) return false
                    if (t > tmin) tmin = t
                } else {
                    if (t < tmin) return false
                    if (t < tmax) tmax = t
                }
            }
        }
        return tmin <= tmax
    }

    private fun getDistance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun getAngle(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pointerCount = event.pointerCount

        if (pointerCount >= 2) {
            if (currentMode != Mode.EYEDROPPER) {
                currentMode = Mode.PAN_ZOOM
            }
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            return true
        }

        val touchPoint = floatArrayOf(event.x, event.y)
        viewMatrix.invert(invertedMatrix)
        invertedMatrix.mapPoints(touchPoint)
        val cx = touchPoint[0]
        val cy = touchPoint[1]

        val brushLayer = selectedLayer as? com.astral.typer.models.BrushLayer
        if (brushLayer != null && !isEraseLayerMode && currentMode != Mode.EYEDROPPER && !isInpaintMode && !isGradationMode && !isTyperActive) {
            if (pointerCount >= 2 || currentMode == Mode.PAN_ZOOM) {
                currentMode = Mode.PAN_ZOOM
                scaleDetector.onTouchEvent(event)
                gestureDetector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    currentMode = Mode.NONE
                }
                return true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    currentMode = Mode.NONE
                    com.astral.typer.utils.UndoManager.saveState(layers)
                    brushLayer.startStroke(cx, cy)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    brushLayer.continueStroke(cx, cy)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    brushLayer.endStroke()
                    currentMode = Mode.NONE
                    invalidate()
                }
            }
            return true
        }

        if (currentMode == Mode.EYEDROPPER) {
             eyedropperX = cx
             eyedropperY = cy
             eyedropperScreenX = event.x
             eyedropperScreenY = event.y
             invalidate()

             if (event.actionMasked == MotionEvent.ACTION_UP) {
                 val color = getPixelColor(cx, cy)
                 onColorPickedListener?.invoke(color)
                 setEyedropperMode(false)
             }
             return true
        }

        if (isTyperActive && pointerCount == 1 && !isInpaintMode) {
            if (currentTyperTool == TyperTool.HAND) {
                // Pass through for Layer Controls check.
                // If no layer is hit, we will handle Pan/Zoom in the fall-through logic.
            } else {
                when(currentTyperTool) {
                    TyperTool.RECT, TyperTool.CIRCLE -> {
                        when(event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                 startTouchX = cx
                                 startTouchY = cy
                                 currentTyperPath.reset()
                                 if (currentTyperTool == TyperTool.CIRCLE) {
                                     currentTyperPath.addOval(cx, cy, cx, cy, Path.Direction.CW)
                                 } else {
                                     currentTyperPath.addRect(cx, cy, cx, cy, Path.Direction.CW)
                                 }
                                 invalidate()
                            }
                            MotionEvent.ACTION_MOVE -> {
                                 currentTyperPath.reset()
                                 if (currentTyperTool == TyperTool.CIRCLE) {
                                     val rect = RectF(startTouchX, startTouchY, cx, cy)
                                     rect.sort()
                                     currentTyperPath.addOval(rect, Path.Direction.CW)
                                 } else {
                                     currentTyperPath.addRect(startTouchX, startTouchY, cx, cy, Path.Direction.CW)
                                 }
                                 invalidate()
                            }
                            MotionEvent.ACTION_UP -> {
                                 val rect = RectF(startTouchX, startTouchY, cx, cy)
                                 rect.sort()
                                 if (rect.width() > 10 && rect.height() > 10) {
                                      val isOval = (currentTyperTool == TyperTool.CIRCLE)
                                      val newList = (detectedBubbles ?: emptyList()) + TyperBubble(rect, isOval)
                                      setDetectedBubbles(newList)
                                 }
                                 currentTyperPath.reset()
                                 invalidate()
                            }
                        }
                    }
                    TyperTool.LASSO -> {
                         when(event.actionMasked) {
                             MotionEvent.ACTION_DOWN -> {
                                 currentTyperPath.reset()
                                 currentTyperPath.moveTo(cx, cy)
                                 invalidate()
                             }
                             MotionEvent.ACTION_MOVE -> {
                                 currentTyperPath.lineTo(cx, cy)
                                 invalidate()
                             }
                             MotionEvent.ACTION_UP -> {
                                 val bounds = RectF()
                                 currentTyperPath.computeBounds(bounds, true)
                                 if (bounds.width() > 10 && bounds.height() > 10) {
                                      // Lasso creates Oval bubble as per requirement
                                      val newList = (detectedBubbles ?: emptyList()) + TyperBubble(bounds, true)
                                      setDetectedBubbles(newList)
                                 }
                                 currentTyperPath.reset()
                                 invalidate()
                             }
                         }
                    }
                    TyperTool.ERASER -> {
                         if (event.actionMasked == MotionEvent.ACTION_UP) {
                             if (detectedBubbles != null) {
                                 // Find bubble to delete
                                 val toDelete = detectedBubbles!!.find { it.rect.contains(cx, cy) }
                                 if (toDelete != null) {
                                     removeDetectedBubble(toDelete)
                                 }
                             }
                         }
                    }
                    else -> {}
                }
                return true
            }
        }


        if (isInpaintMode) {
            if (pointerCount >= 2 || currentMode == Mode.PAN_ZOOM) {
                if (!currentInpaintPath.isEmpty) {
                    currentInpaintPath.reset()
                    invalidate()
                }
                currentMode = Mode.PAN_ZOOM
                scaleDetector.onTouchEvent(event)
                gestureDetector.onTouchEvent(event)

                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    currentMode = Mode.INPAINT
                }
                return true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (currentInpaintTool != InpaintTool.MAGIC_WAND && currentInpaintTool != InpaintTool.MAGIC_WAND_ERASER) {
                        currentInpaintPath.reset()
                        currentInpaintPath.moveTo(cx, cy)
                    }
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && currentInpaintTool != InpaintTool.MAGIC_WAND && currentInpaintTool != InpaintTool.MAGIC_WAND_ERASER) {
                        currentInpaintPath.lineTo(cx, cy)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (currentInpaintTool == InpaintTool.MAGIC_WAND || currentInpaintTool == InpaintTool.MAGIC_WAND_ERASER) {
                        performMagicWand(cx.toInt(), cy.toInt())
                    } else {
                        if (!currentInpaintPath.isEmpty) {
                             if (currentInpaintTool == InpaintTool.LASSO || currentInpaintTool == InpaintTool.LASSO_ERASER) {
                                 currentInpaintPath.close()
                             }
                             inpaintOps.add(Pair(Path(currentInpaintPath), currentInpaintTool))
                             redoOps.clear()
                             currentInpaintPath.reset()
                        }
                    }
                    invalidate()
                }
            }
            return true
        }

        if (isGradationMode) {
            if (pointerCount >= 2 || currentMode == Mode.PAN_ZOOM) {
                currentMode = Mode.PAN_ZOOM
                scaleDetector.onTouchEvent(event)
                gestureDetector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) currentMode = Mode.GRADATION
                return true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gradationStart = PointF(cx, cy); gradationEnd = PointF(cx, cy); invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    gradationEnd?.set(cx, cy); invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val p1 = gradationStart; val p2 = gradationEnd
                    if (p1 != null && p2 != null && getDistance(p1.x, p1.y, p2.x, p2.y) > 10f) {
                        val angle = getAngle(p1.x, p1.y, p2.x, p2.y).toInt()
                        com.astral.typer.utils.UndoManager.saveState(layers)
                        for (layer in layers) {
                            if (lineIntersectsLayer(p1, p2, layer) && layer is com.astral.typer.models.StylableLayer) {
                                layer.gradientAngle = angle
                                layer.isGradient = true
                                layer.isGlobalGradient = true
                                layer.globalP1.set(p1.x, p1.y)
                                layer.globalP2.set(p2.x, p2.y)
                                layer.gradientStartColor = pendingGradientStart
                                layer.gradientEndColor = pendingGradientEnd
                                layer.hasMiddleColor = pendingHasMiddleColor
                                layer.gradientMiddleColor = pendingGradientMiddleColor
                                layer.gradientStartPos = pendingGradientStartPos
                                layer.gradientMiddlePos = pendingGradientMiddlePos
                                layer.gradientEndPos = pendingGradientEndPos
                                layer.isGradientText = targetGradientText
                                layer.isGradientStroke = targetGradientStroke
                                layer.isGradientShadow = targetGradientShadow
                            }
                        }
                    }
                    gradationStart = null; gradationEnd = null; invalidate()
                }
            }
            return true
        }

        if (isEraseLayerMode && selectedLayer is com.astral.typer.models.BrushLayer) {
            val brushLayer = selectedLayer as com.astral.typer.models.BrushLayer
            if (pointerCount >= 2 || currentMode == Mode.PAN_ZOOM) {
                currentMode = Mode.PAN_ZOOM
                scaleDetector.onTouchEvent(event)
                gestureDetector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    currentMode = Mode.ERASE_LAYER
                }
                return true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    currentMode = Mode.NONE
                    com.astral.typer.utils.UndoManager.saveState(layers)

                    val eraserPreset = com.astral.typer.utils.MyPaintBrushHelper.loadPreset(context, "brushes/classic/ink_eraser.myb")
                    brushLayer.activePreset = eraserPreset
                    brushLayer.brushName = "ink_eraser"
                    brushLayer.brushSize = layerEraseSize
                    brushLayer.brushHardness = layerEraseHardness / 100f
                    brushLayer.brushOpacity = layerEraseOpacity

                    brushLayer.startStroke(cx, cy)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    brushLayer.brushSize = layerEraseSize
                    brushLayer.brushHardness = layerEraseHardness / 100f
                    brushLayer.brushOpacity = layerEraseOpacity

                    brushLayer.continueStroke(cx, cy)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    brushLayer.endStroke()
                    currentMode = Mode.NONE
                    invalidate()
                }
            }
            return true
        }

        if (isEraseLayerMode && selectedLayer is com.astral.typer.models.StylableLayer) {
            val layer = selectedLayer!!
            val stylable = layer as com.astral.typer.models.StylableLayer
            if (pointerCount >= 2 || currentMode == Mode.PAN_ZOOM) {
                currentMode = Mode.PAN_ZOOM; scaleDetector.onTouchEvent(event); gestureDetector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) currentMode = Mode.ERASE_LAYER
                return true
            }
            val localPoint = floatArrayOf(cx, cy); val globalToLocal = Matrix()
            globalToLocal.postTranslate(-layer.x, -layer.y); globalToLocal.postRotate(-layer.rotation); globalToLocal.postScale(1/layer.scaleX, 1/layer.scaleY); globalToLocal.mapPoints(localPoint)
            val pad = stylable.calculatePadding()
            val w = layer.getWidth().toInt().coerceAtLeast(1); val h = layer.getHeight().toInt().coerceAtLeast(1)
            val maskW = (w + pad * 2).toInt().coerceAtLeast(1)
            val maskH = (h + pad * 2).toInt().coerceAtLeast(1)
            val maskX = localPoint[0]; val maskY = localPoint[1]
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    com.astral.typer.utils.UndoManager.saveState(layers)
                    stylable.eraseDragRevision++
                    currentLayerErasePath.reset(); currentLayerErasePath.moveTo(maskX, maskY)
                    currentLayerErasePoints.clear()
                    currentLayerErasePoints.add(com.astral.typer.models.ErasePoint(maskX, maskY))
                    if (stylable.eraseMask == null) {
                         stylable.eraseMask = android.graphics.Bitmap.createBitmap(maskW, maskH, android.graphics.Bitmap.Config.ARGB_8888)
                    }
                    stylable.activeErasePath = currentLayerErasePath
                    stylable.activeEraseSize = layerEraseSize
                    stylable.activeEraseOpacity = layerEraseOpacity
                    stylable.activeEraseHardness = layerEraseHardness
                }
                MotionEvent.ACTION_MOVE -> {
                    stylable.eraseDragRevision++
                    currentLayerErasePath.lineTo(maskX, maskY)
                    currentLayerErasePoints.add(com.astral.typer.models.ErasePoint(maskX, maskY))
                    stylable.activeErasePath = currentLayerErasePath
                    stylable.activeEraseSize = layerEraseSize
                    stylable.activeEraseOpacity = layerEraseOpacity
                    stylable.activeEraseHardness = layerEraseHardness
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    stylable.eraseDragRevision++
                    stylable.activeErasePath = null
                    if (!currentLayerErasePath.isEmpty) {
                        stylable.addErasePath(Path(currentLayerErasePath), layerEraseSize, layerEraseOpacity, layerEraseHardness, currentLayerErasePoints.toList())
                        currentLayerErasePath.reset()
                        currentLayerErasePoints.clear()
                    }
                    invalidate()
                }
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentMode = Mode.NONE
                hasMoved = false
                startTouchX = cx
                startTouchY = cy

                if (selectedLayer != null) {
                    val layer = selectedLayer!!

                    val localPoint = floatArrayOf(cx, cy)
                    val globalToLocal = Matrix()
                    globalToLocal.postTranslate(-layer.x, -layer.y)
                    globalToLocal.postRotate(-layer.rotation)
                    globalToLocal.postScale(1/layer.scaleX, 1/layer.scaleY)
                    globalToLocal.mapPoints(localPoint)

                    val lx = localPoint[0]
                    val ly = localPoint[1]

                    if (cutPoints != null && layer is ImageLayer) {
                        val pts = cutPoints!!
                        val hitRadius = 60f / ((abs(layer.scaleX) + abs(layer.scaleY))/2f)
                        if (getDistance(lx, ly, pts[0], pts[1]) < hitRadius) { currentMode = Mode.CUT_DRAG_TL; return true }
                        if (getDistance(lx, ly, pts[2], pts[3]) < hitRadius) { currentMode = Mode.CUT_DRAG_TR; return true }
                        if (getDistance(lx, ly, pts[4], pts[5]) < hitRadius) { currentMode = Mode.CUT_DRAG_BR; return true }
                        if (getDistance(lx, ly, pts[6], pts[7]) < hitRadius) { currentMode = Mode.CUT_DRAG_BL; return true }
                        return true
                    }

                    val stylableLayer = layer as? com.astral.typer.models.StylableLayer
                    val isWarpActive = stylableLayer?.isWarp ?: false

                    if (isWarpActive && (isWarpToolActive || isPerspectiveMode) && stylableLayer != null) {
                         val mesh = stylableLayer.warpMesh
                         if (mesh != null) {
                             val viewScale = getCurrentViewScale()
                             val hitRadius = 40f / (((abs(layer.scaleX) + abs(layer.scaleY))/2f) * viewScale)
                             var bestIdx = -1; var minD = Float.MAX_VALUE
                             for (i in 0 until (mesh.size / 2)) {
                                 val d = getDistance(lx, ly, mesh[i*2], mesh[i*2+1])
                                 if (d < hitRadius && d < minD) { minD = d; bestIdx = i }
                             }
                             if (bestIdx != -1) { warpPointIndex = bestIdx; currentMode = Mode.WARP_DRAG; return true }
                         }
                    }

                    if (!isPerspectiveMode && !isWarpToolActive) {
                        val halfW = layer.getWidth() / 2f
                        val halfH = layer.getHeight() / 2f

                        val geometry = getHandleGeometry(layer)
                        val handleOffset = geometry.offset
                        val hitRadius = geometry.radius * 2.0f

                        val topY = -halfH - handleOffset * 2.5f
                        val iconSpacing = geometry.radius * 2.5f
                        val dupX = -iconSpacing / 1.5f
                        val copyX = iconSpacing / 1.5f

                        val isTyperHand = isTyperActive && currentTyperTool == TyperTool.HAND

                        if (!isTyperHand && getDistance(lx, ly, dupX, topY) <= hitRadius) {
                            com.astral.typer.utils.UndoManager.saveState(layers)
                            val newLayer = layer.clone()
                            newLayer.x += 20
                            newLayer.y += 20
                            layers.add(newLayer)
                            selectLayer(newLayer)
                            return true
                        }

                        if (!isTyperHand && getDistance(lx, ly, copyX, topY) <= hitRadius) {
                            if (layer is TextLayer) {
                                com.astral.typer.utils.StyleManager.copyStyle(layer)
                                com.astral.typer.utils.StyleManager.saveStyle(context, layer)
                                android.widget.Toast.makeText(context, "Style Copied to Menu", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            return true
                        }

                        if (getDistance(lx, ly, -halfW - handleOffset, -halfH - handleOffset) <= hitRadius) {
                            com.astral.typer.utils.UndoManager.saveState(layers)
                            deleteSelectedLayer()
                            return true
                        }
                        if (getDistance(lx, ly, halfW + handleOffset, -halfH - handleOffset) <= hitRadius) {
                            com.astral.typer.utils.UndoManager.saveState(layers)
                            currentMode = Mode.ROTATE_LAYER
                            initialRotation = layer.rotation
                            centerX = layer.x
                            centerY = layer.y
                            startAngle = getAngle(centerX, centerY, cx, cy)
                            return true
                        }
                        if (getDistance(lx, ly, halfW + handleOffset, halfH + handleOffset) <= hitRadius) {
                            com.astral.typer.utils.UndoManager.saveState(layers)
                            currentMode = Mode.RESIZE_LAYER
                            initialScaleX = layer.scaleX
                            initialScaleY = layer.scaleY
                            centerX = layer.x
                            centerY = layer.y
                            startDist = getDistance(centerX, centerY, cx, cy)
                            return true
                        }
                        if (!isTyperHand && getDistance(lx, ly, -halfW - handleOffset, 0f) <= hitRadius) {
                            com.astral.typer.utils.UndoManager.saveState(layers)
                            currentMode = Mode.STRETCH_H
                            initialScaleX = layer.scaleX
                            centerX = layer.x
                            centerY = layer.y
                            startX = lx
                            return true
                        }
                        if (!isTyperHand && getDistance(lx, ly, 0f, halfH + handleOffset) <= hitRadius) {
                            com.astral.typer.utils.UndoManager.saveState(layers)
                            currentMode = Mode.STRETCH_V
                            if (layer is TextLayer) {
                                // For TextLayer, this is height resize
                                initialFixedHeight = layer.getHeight()
                            } else {
                                initialScaleY = layer.scaleY
                            }
                            centerX = layer.x
                            centerY = layer.y
                            startY = ly
                            return true
                        }
                        if ((layer is TextLayer || layer is com.astral.typer.models.ShapeLayer) && getDistance(lx, ly, halfW + handleOffset, 0f) <= hitRadius) {
                            com.astral.typer.utils.UndoManager.saveState(layers)
                            if (layer is TextLayer) {
                                currentMode = Mode.BOX_WIDTH; initialBoxWidth = layer.getWidth(); centerX = layer.x; centerY = layer.y; startDist = lx; return true
                            } else {
                                // ShapeLayer box width? User didn't ask but for consistency:
                                // return true
                            }
                        }
                    }
                }

                val hitLayer = layers.findLast { it.contains(cx, cy) }
                if (hitLayer != null) {
                    wasSelectedInitially = (selectedLayer == hitLayer)
                    selectLayer(hitLayer)
                    invalidate()

                    if (currentMode != Mode.NONE) {
                    } else {
                        com.astral.typer.utils.UndoManager.saveState(layers)
                        currentMode = Mode.DRAG_LAYER
                        lastTouchX = cx
                        lastTouchY = cy
                        val localPoint = getLocalPoint(cx, cy, hitLayer)
                        lastLocalX = localPoint[0]
                        lastLocalY = localPoint[1]
                        invalidate()
                    }
                } else {
                    // Empty space touch
                    if (isTyperActive && currentTyperTool == TyperTool.HAND) {
                        currentMode = Mode.PAN_ZOOM
                        scaleDetector.onTouchEvent(event)
                        gestureDetector.onTouchEvent(event)
                        startTouchX = cx
                        startTouchY = cy
                        hasMoved = false
                    } else {
                        // Standard mode empty space
                        // Do NOT deselect immediately to allow panning without closing menus (Task 6)
                        currentMode = Mode.NONE
                        // We do not call selectLayer(null) here anymore.
                        // We wait for ACTION_UP to confirm it was a tap, not a pan.
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (currentMode == Mode.PAN_ZOOM) {
                    if (event.pointerCount == 1 && isTyperActive && currentTyperTool == TyperTool.HAND) {
                        if (!hasMoved && getDistance(cx, cy, startTouchX, startTouchY) > 10f) {
                             hasMoved = true
                        }
                        scaleDetector.onTouchEvent(event)
                        gestureDetector.onTouchEvent(event)
                    }
                    return true
                }
                if (currentMode == Mode.NONE) return true

                if (!hasMoved && getDistance(cx, cy, startTouchX, startTouchY) > 5f) {
                    hasMoved = true
                }

                if (selectedLayer != null) {
                    val layer = selectedLayer!!

                    if (currentMode == Mode.WARP_DRAG && layer is com.astral.typer.models.StylableLayer) {
                         val localPoint = floatArrayOf(cx, cy); val globalToLocal = Matrix()
                         globalToLocal.postTranslate(-layer.x, -layer.y); globalToLocal.postRotate(-layer.rotation); globalToLocal.postScale(1/layer.scaleX, 1/layer.scaleY); globalToLocal.mapPoints(localPoint)
                         val mesh = layer.warpMesh
                         if (mesh != null && warpPointIndex != -1) {
                             mesh[warpPointIndex*2] = localPoint[0]; mesh[warpPointIndex*2+1] = localPoint[1]
                             layer.updateDenseWarpMesh()
                             invalidate()
                         }
                         return true
                    }

                    if (cutPoints != null && layer is ImageLayer) {
                        val localPoint = floatArrayOf(cx, cy)
                        val globalToLocal = Matrix()
                        globalToLocal.postTranslate(-layer.x, -layer.y)
                        globalToLocal.postRotate(-layer.rotation)
                        globalToLocal.postScale(1/layer.scaleX, 1/layer.scaleY)
                        globalToLocal.mapPoints(localPoint)

                        val lx = localPoint[0]
                        val ly = localPoint[1]
                        val pts = cutPoints!!

                        val w = layer.getWidth()
                        val h = layer.getHeight()
                        val boundLeft = -w / 2f
                        val boundRight = w / 2f
                        val boundTop = -h / 2f
                        val boundBottom = h / 2f

                        fun constrain(v: Float, min: Float, max: Float): Float {
                            return v.coerceIn(min, max)
                        }

                        when (currentMode) {
                            Mode.CUT_DRAG_TL -> {
                                val newX = constrain(lx, boundLeft, pts[2] - 10)
                                val newY = constrain(ly, boundTop, pts[7] - 10)
                                pts[0] = newX; pts[1] = newY
                                pts[6] = newX
                                pts[3] = newY
                            }
                            Mode.CUT_DRAG_TR -> {
                                val newX = constrain(lx, pts[0] + 10, boundRight)
                                val newY = constrain(ly, boundTop, pts[5] - 10)
                                pts[2] = newX; pts[3] = newY
                                pts[4] = newX
                                pts[1] = newY
                            }
                            Mode.CUT_DRAG_BR -> {
                                val newX = constrain(lx, pts[6] + 10, boundRight)
                                val newY = constrain(ly, pts[3] + 10, boundBottom)
                                pts[4] = newX; pts[5] = newY
                                pts[2] = newX
                                pts[7] = newY
                            }
                            Mode.CUT_DRAG_BL -> {
                                val newX = constrain(lx, boundLeft, pts[4] - 10)
                                val newY = constrain(ly, pts[1] + 10, boundBottom)
                                pts[6] = newX; pts[7] = newY
                                pts[0] = newX
                                pts[5] = newY
                            }
                            else -> {}
                        }
                        invalidate()
                        return true
                    }


                    when (currentMode) {
                        Mode.DRAG_LAYER -> {
                            if (layer is TextLayer && isWarpToolActive && layer.selectedWarpIndex != -1) {
                                val localPoint = getLocalPoint(cx, cy, layer)
                                val lx = localPoint[0]
                                val ly = localPoint[1]
                                val ldx = lx - lastLocalX
                                val ldy = ly - lastLocalY

                                val mesh = layer.warpMesh
                                if (mesh != null) {
                                    for (i in 0 until (mesh.size / 2)) {
                                        mesh[i * 2] = mesh[i * 2] + ldx
                                        mesh[i * 2 + 1] = mesh[i * 2 + 1] + ldy
                                    }
                                    layer.updateDenseWarpMesh()
                                }
                                lastLocalX = lx
                                lastLocalY = ly
                                lastTouchX = cx
                                lastTouchY = cy
                                invalidate()
                                onLayerUpdateListener?.onLayerUpdate(layer)
                            } else {
                                var dx = cx - lastTouchX
                                var dy = cy - lastTouchY

                                val nextX = layer.x + dx
                                val nextY = layer.y + dy

                                val snapThreshold = 20f
                                var snappedX = false
                                var snappedY = false
                                val currentSnapThreshold = 6f

                                val sharedPrefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
                                val disableSnap = sharedPrefs.getBoolean("disable_snap_to_center", false)

                                if (!disableSnap && abs(nextX - canvasWidth / 2f) < currentSnapThreshold) {
                                    dx = (canvasWidth / 2f) - layer.x
                                    showVerticalCenterLine = true
                                    snappedX = true
                                } else {
                                    showVerticalCenterLine = false
                                }

                                if (!disableSnap && abs(nextY - canvasHeight / 2f) < currentSnapThreshold) {
                                    dy = (canvasHeight / 2f) - layer.y
                                    showHorizontalCenterLine = true
                                    snappedY = true
                                } else {
                                    showHorizontalCenterLine = false
                                }

                                layer.x += dx
                                layer.y += dy
                                lastTouchX = cx
                                lastTouchY = cy
                                invalidate()
                                onLayerUpdateListener?.onLayerUpdate(layer)
                            }
                        }
                        Mode.ROTATE_LAYER -> {
                            val currentAngle = getAngle(centerX, centerY, cx, cy)
                            val angleDiff = currentAngle - startAngle
                            layer.rotation = initialRotation + angleDiff
                            invalidate()
                            onLayerUpdateListener?.onLayerUpdate(layer)
                        }
                        Mode.RESIZE_LAYER -> {
                            val currentDist = getDistance(centerX, centerY, cx, cy)
                            if (startDist > 0) {
                                val scaleFactor = currentDist / startDist
                                layer.scaleX = initialScaleX * scaleFactor
                                layer.scaleY = initialScaleY * scaleFactor
                                invalidate()
                                onLayerUpdateListener?.onLayerUpdate(layer)
                            }
                        }
                        Mode.STRETCH_H -> {
                            val rad = Math.toRadians(layer.rotation.toDouble())
                            val cos = Math.cos(rad)
                            val sin = Math.sin(rad)
                            val dx = cx - centerX
                            val dy = cy - centerY
                            val proj = -(dx * cos + dy * sin)
                            if (abs(proj) > 10) {
                                 val s = (proj / (layer.getWidth() / 2f)).toFloat()
                                 if (abs(s) >= 0.1f) {
                                     layer.scaleX = s
                                     invalidate()
                                     onLayerUpdateListener?.onLayerUpdate(layer)
                                 }
                            }
                        }
                        Mode.STRETCH_V -> {
                             val rad = Math.toRadians(layer.rotation.toDouble())
                             val cos = Math.cos(rad)
                             val sin = Math.sin(rad)
                             val dx = cx - centerX
                             val dy = cy - centerY
                             val proj = -dx * sin + dy * cos
                             if (abs(proj) > 10) {
                                 if (layer is TextLayer) {
                                     // Resize fixedHeight
                                     // proj is distance from center to handle (half height in screen space rotated/projected)
                                     // We want full height in local space.
                                     // fullHeight = (abs(proj) * 2) / scaleY
                                     val newH = (abs(proj) / layer.scaleY * 2f).toFloat().coerceAtLeast(20f)
                                     layer.fixedHeight = newH
                                     invalidate()
                                     onLayerUpdateListener?.onLayerUpdate(layer)
                                 } else {
                                     val s = (proj / (layer.getHeight() / 2f)).toFloat()
                                     if (abs(s) >= 0.1f) {
                                         layer.scaleY = s
                                         invalidate()
                                         onLayerUpdateListener?.onLayerUpdate(layer)
                                     }
                                 }
                             }
                        }
                        Mode.BOX_WIDTH -> {
                            if (layer is TextLayer) {
                                val rad = Math.toRadians(layer.rotation.toDouble())
                                val cos = Math.cos(rad)
                                val sin = Math.sin(rad)
                                val dx = cx - centerX
                                val dy = cy - centerY
                                val proj = dx * cos + dy * sin
                                val calculatedWidth = ((proj / layer.scaleX) * 2f).toFloat()
                                if (calculatedWidth > 20) {
                                    layer.boxWidth = calculatedWidth
                                    invalidate()
                                    onLayerUpdateListener?.onLayerUpdate(layer)
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (currentMode == Mode.DRAG_LAYER) {
                    // Trigger edit mode if tapped on an already selected layer (and not moved)
                    if (!hasMoved && wasSelectedInitially && selectedLayer != null) {
                        onLayerEditListener?.onLayerDoubleTap(selectedLayer!!)
                    }
                    showVerticalCenterLine = false
                    showHorizontalCenterLine = false
                    invalidate()
                }

                if (isTyperActive && currentTyperTool == TyperTool.HAND && currentMode == Mode.PAN_ZOOM) {
                     if (!hasMoved && detectedBubbles != null) {
                         val clickedBubble = detectedBubbles!!.find { it.rect.contains(cx, cy) }
                         if (clickedBubble != null) {
                             onBubbleClickListener?.invoke(clickedBubble)
                         }
                     }
                     currentMode = Mode.TYPER
                } else {
                     // If we are in Mode.NONE and haven't moved, it was a tap on empty space.
                     if (currentMode == Mode.NONE && !hasMoved) {
                         if (!preventDeselection) {
                             selectLayer(null)
                         }
                     }
                     currentMode = Mode.NONE
                }
            }
        }

        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (currentMode == Mode.PAN_ZOOM) {
                val scaleFactor = detector.scaleFactor
                val focusX = detector.focusX
                val focusY = detector.focusY
                viewMatrix.postScale(scaleFactor, scaleFactor, focusX, focusY)
                invalidate()
            }
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (currentMode == Mode.PAN_ZOOM) {
                viewMatrix.postTranslate(-distanceX, -distanceY)
                invalidate()
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
             val touchPoint = floatArrayOf(e.x, e.y)
             val inverse = Matrix()
             viewMatrix.invert(inverse)
             inverse.mapPoints(touchPoint)
             val cx = touchPoint[0]
             val cy = touchPoint[1]

             val hitLayer = layers.findLast { it.contains(cx, cy) }
             if (hitLayer != null) {
                 onLayerEditListener?.onLayerDoubleTap(hitLayer)
             } else {
                 centerCanvas()
             }
             return true
        }

    }
}

/* Helper classes for memory-efficient and high-precision tiled Magic Wand */
internal class IntQueue(initialCapacity: Int = 4096) {
    private var data = IntArray(initialCapacity)
    private var head = 0
    private var tail = 0
    var size = 0
        private set

    fun enqueue(value: Int) {
        if (size == data.size) {
            resize()
        }
        data[tail] = value
        tail = (tail + 1) % data.size
        size++
    }

    fun dequeue(): Int {
        if (size == 0) throw NoSuchElementException()
        val value = data[head]
        head = (head + 1) % data.size
        size--
        return value
    }

    fun isEmpty(): Boolean = size == 0

    private fun resize() {
        val newCapacity = data.size * 2
        val newData = IntArray(newCapacity)
        for (i in 0 until size) {
            newData[i] = data[(head + i) % data.size]
        }
        data = newData
        head = 0
        tail = size
    }
}

internal class TiledVisitedTracker(private val tileSize: Int) {
    private val tileBitSets = java.util.HashMap<Long, java.util.BitSet>()

    fun visit(x: Int, y: Int): Boolean {
        val tx = x / tileSize
        val ty = y / tileSize
        val key = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
        var bitSet = tileBitSets[key]
        if (bitSet == null) {
            bitSet = java.util.BitSet(tileSize * tileSize)
            tileBitSets[key] = bitSet
        }
        val lx = x % tileSize
        val ly = y % tileSize
        val idx = ly * tileSize + lx
        if (bitSet.get(idx)) {
            return false
        }
        bitSet.set(idx)
        return true
    }

    fun isVisited(x: Int, y: Int): Boolean {
        val tx = x / tileSize
        val ty = y / tileSize
        val key = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
        val bitSet = tileBitSets[key] ?: return false
        val lx = x % tileSize
        val ly = y % tileSize
        val idx = ly * tileSize + lx
        return bitSet.get(idx)
    }
}

internal class TiledPixelCache(tiles: List<AstralCanvasView.ImageTile>, private val tileSize: Int) {
    private val tileMap = java.util.HashMap<Long, AstralCanvasView.ImageTile>()
    private val pixelArrays = java.util.HashMap<Long, IntArray>()

    init {
        for (tile in tiles) {
            val tx = (tile.rect.left / tileSize).toInt()
            val ty = (tile.rect.top / tileSize).toInt()
            val key = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
            tileMap[key] = tile
        }
    }

    fun getPixel(x: Int, y: Int): Int {
        val tx = x / tileSize
        val ty = y / tileSize
        val key = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
        var pixels = pixelArrays[key]
        val tile = tileMap[key] ?: return 0

        val w = tile.bitmap.width
        val h = tile.bitmap.height

        if (pixels == null) {
            pixels = IntArray(w * h)
            tile.bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            pixelArrays[key] = pixels
        }

        val lx = x % tileSize
        val ly = y % tileSize
        if (lx >= w || ly >= h) return 0

        return pixels[ly * w + lx]
    }
}
