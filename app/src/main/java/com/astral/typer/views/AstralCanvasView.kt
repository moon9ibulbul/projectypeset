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

    // Canvas Configuration
    private var canvasWidth = 1080
    private var canvasHeight = 1080
    private var canvasColor = Color.WHITE
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

    // Layer Erase Settings
    var layerEraseSize = 50f
    var layerEraseOpacity = 255
    var layerEraseHardness = 0f

    // Temp path for layer erase
    private val currentLayerErasePath = Path()

    // Inpaint Tools
    enum class InpaintTool {
        BRUSH, ERASER, LASSO
    }

    var currentInpaintTool = InpaintTool.BRUSH
    private val inpaintOps = mutableListOf<Pair<Path, InpaintTool>>()
    private val redoOps = mutableListOf<Pair<Path, InpaintTool>>()
    private var currentInpaintPath = Path()

    // Cached Mask Bitmap REMOVED
    // private var cachedMaskBitmap: android.graphics.Bitmap? = null
    // private var isMaskDirty = true

    var brushSize = 50f
        set(value) {
            field = value
            inpaintPaint.strokeWidth = value
            eraserPaint.strokeWidth = value
            invalidate()
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
    private val lassoStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    /**
     * Generates the Inpaint mask on demand.
     * Note: This allocates a full bitmap. Use with care on large canvases.
     * Use getRegionAsBitmap for tiled access if possible.
     */
    fun getInpaintMask(): android.graphics.Bitmap {
        // Create a bitmap on the fly
        val bmp = try {
            android.graphics.Bitmap.createBitmap(canvasWidth, canvasHeight, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("AstralCanvasView", "OOM generating inpaint mask")
            android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        }

        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

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

        for ((path, tool) in inpaintOps) {
            when(tool) {
                InpaintTool.BRUSH -> canvas.drawPath(path, brushP)
                InpaintTool.ERASER -> canvas.drawPath(path, eraseP)
                InpaintTool.LASSO -> canvas.drawPath(path, lassoP)
            }
        }
        // Draw current path if any? (Usually getInpaintMask is called after lifting finger)
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
            android.util.Log.e("AstralCanvasView", "OOM in getBackgroundImage")
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
        invalidate()
    }

    fun getLayers(): MutableList<Layer> {
        return layers
    }

    fun setLayers(newLayers: List<Layer>) {
        layers.clear()
        layers.addAll(newLayers)
        selectedLayer = null
        invalidate()
    }

    fun getSelectedLayer(): Layer? {
        return selectedLayer
    }

    fun setPerspectiveMode(enabled: Boolean) {
        isPerspectiveMode = enabled
        if (!enabled) currentMode = Mode.NONE
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
        if (enabled) {
            currentMode = Mode.ERASE_LAYER
            if (selectedLayer !is TextLayer) {
                 // Warning logic
            }
        } else {
            if (currentMode == Mode.ERASE_LAYER) {
                currentMode = Mode.NONE
            }
        }
        invalidate()
    }

    fun undoLayerErase() {
        if (selectedLayer is TextLayer) {
            val layer = selectedLayer as TextLayer
            layer.undoLastErasePath(null)
            invalidate()
        }
    }

    // Handles Constants
    private val HANDLE_RADIUS = 30f

    // Geometry Helper
    private data class HandleGeometry(val radius: Float, val offset: Float, val scale: Float)

    private fun getHandleGeometry(layer: Layer): HandleGeometry {
        val avgScale = (abs(layer.scaleX) + abs(layer.scaleY)) / 2f
        val screenW = layer.getWidth() * abs(layer.scaleX)
        val screenH = layer.getHeight() * abs(layer.scaleY)
        val minScreenDim = kotlin.math.min(screenW, screenH)

        val targetVisualRadius = if (minScreenDim < 150f) {
            kotlin.math.max(10f, minScreenDim / 5f)
        } else {
            HANDLE_RADIUS
        }

        val localRadius = targetVisualRadius / avgScale
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
        CUT_DRAG_BL
    }

    private var currentMode = Mode.NONE
    private var warpPointIndex = -1

    var onColorPickedListener: ((Int) -> Unit)? = null

    fun setEyedropperMode(enabled: Boolean) {
        currentMode = if (enabled) Mode.EYEDROPPER else Mode.NONE
        invalidate()
    }

    private fun getPixelColor(x: Float, y: Float): Int {
        if (x < 0 || x >= canvasWidth || y < 0 || y >= canvasHeight) return Color.WHITE
        val bmp = renderToBitmap()
        val pixel = bmp.getPixel(x.toInt(), y.toInt())
        return pixel
    }

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var hasMoved = false
    private var wasSelectedInitially = false
    private var initialRotation = 0f
    private var initialScaleX = 1f
    private var initialScaleY = 1f
    private var initialBoxWidth = 0f

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

    fun selectLayer(layer: Layer?) {
        if (selectedLayer != layer) {
            selectedLayer = layer
            onLayerSelectedListener?.onLayerSelected(layer)
            isPerspectiveMode = false
            exitCutMode()
            (layer as? TextLayer)?.isPerspective = false
            invalidate()
        } else {
             onLayerSelectedListener?.onLayerSelected(layer)
        }
    }

    fun deleteSelectedLayer() {
        selectedLayer?.let {
            layers.remove(it)
            selectLayer(null)
        }
    }

    fun initCanvas(width: Int, height: Int, color: Int) {
        canvasWidth = width
        canvasHeight = height
        canvasColor = color

        backgroundRect.set(0f, 0f, width.toFloat(), height.toFloat())

        post {
             centerCanvas()
        }
    }

    fun setBackgroundImage(bitmap: android.graphics.Bitmap) {
        backgroundTiles.clear()

        val w = bitmap.width
        val h = bitmap.height

        // Tiling Logic
        for (y in 0 until h step TILE_SIZE) {
            for (x in 0 until w step TILE_SIZE) {
                val tileW = min(TILE_SIZE, w - x)
                val tileH = min(TILE_SIZE, h - y)

                // Create tile
                val tileBitmap = android.graphics.Bitmap.createBitmap(bitmap, x, y, tileW, tileH)
                val tileRect = RectF(x.toFloat(), y.toFloat(), (x + tileW).toFloat(), (y + tileH).toFloat())

                backgroundTiles.add(ImageTile(tileBitmap, tileRect))
            }
        }
        invalidate()
    }

    fun renderToBitmap(): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(canvasWidth, canvasHeight, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

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
        for (layer in layers) {
             layer.draw(canvas)
        }

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

        // Draw Inpaint Path (Vector optimized, no cache bitmap)
        if (isInpaintMode) {
            val saveCount = canvas.saveLayer(null, null)

            // Draw Paths directly
            val brushP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = brushSize
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            // Inpaint Overlay Tint
            brushP.colorFilter = android.graphics.PorterDuffColorFilter(Color.RED, android.graphics.PorterDuff.Mode.SRC_IN)
            brushP.alpha = 128

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
                colorFilter = android.graphics.PorterDuffColorFilter(Color.RED, android.graphics.PorterDuff.Mode.SRC_IN)
                alpha = 128
            }

            // Draw History
            for ((path, tool) in inpaintOps) {
                when(tool) {
                    InpaintTool.BRUSH -> canvas.drawPath(path, brushP)
                    InpaintTool.ERASER -> canvas.drawPath(path, eraseP)
                    InpaintTool.LASSO -> canvas.drawPath(path, lassoP)
                }
            }

            // Draw Current Path
            if (!currentInpaintPath.isEmpty) {
                 when(currentInpaintTool) {
                    InpaintTool.BRUSH -> canvas.drawPath(currentInpaintPath, inpaintPaint)
                    InpaintTool.ERASER -> canvas.drawPath(currentInpaintPath, eraserPaint)
                    InpaintTool.LASSO -> canvas.drawPath(currentInpaintPath, lassoStrokePaint)
                }
            }
            canvas.restoreToCount(saveCount)
        }

        // Draw Selection Overlay
        if (currentMode != Mode.EYEDROPPER && !isInpaintMode) {
             selectedLayer?.let { drawSelectionOverlay(canvas, it) }
        }

        canvas.restore()

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

             for (layer in layers) {
                 layer.draw(canvas)
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

    private fun drawScene(canvas: Canvas) {
        for (layer in layers) {
            layer.draw(canvas)
        }
    }

    private fun drawSelectionOverlay(canvas: Canvas, layer: Layer) {
        canvas.save()
        canvas.translate(layer.x, layer.y)
        canvas.rotate(layer.rotation)
        canvas.scale(layer.scaleX, layer.scaleY)

        if (cutPoints != null && layer is ImageLayer) {
             val pts = cutPoints!!
             paint.style = Paint.Style.STROKE
             paint.color = Color.MAGENTA
             paint.strokeWidth = 2f / ((abs(layer.scaleX) + abs(layer.scaleY))/2f)
             val path = Path()
             path.moveTo(pts[0], pts[1])
             path.lineTo(pts[2], pts[3])
             path.lineTo(pts[4], pts[5])
             path.lineTo(pts[6], pts[7])
             path.close()
             canvas.drawPath(path, paint)

             val handleRadius = 20f / ((abs(layer.scaleX) + abs(layer.scaleY))/2f)
             handlePaint.color = Color.MAGENTA

             canvas.drawCircle(pts[0], pts[1], handleRadius, handlePaint)
             canvas.drawCircle(pts[2], pts[3], handleRadius, handlePaint)
             canvas.drawCircle(pts[4], pts[5], handleRadius, handlePaint)
             canvas.drawCircle(pts[6], pts[7], handleRadius, handlePaint)

             canvas.restore()
             return
        }

        if (layer is TextLayer && layer.isWarp && isWarpToolActive) {
             val mesh = layer.warpMesh
             val rows = layer.warpRows
             val cols = layer.warpCols
             if (mesh != null) {
                 paint.style = Paint.Style.STROKE
                 paint.color = Color.CYAN
                 paint.strokeWidth = 2f

                 for (r in 0..rows) {
                     val startIdx = r * (cols + 1)
                     for (c in 0 until cols) {
                         val i1 = (startIdx + c) * 2
                         val i2 = (startIdx + c + 1) * 2
                         canvas.drawLine(mesh[i1], mesh[i1+1], mesh[i2], mesh[i2+1], paint)
                     }
                 }
                 for (c in 0..cols) {
                     for (r in 0 until rows) {
                          val idx1 = (r * (cols + 1) + c) * 2
                          val idx2 = ((r + 1) * (cols + 1) + c) * 2
                          canvas.drawLine(mesh[idx1], mesh[idx1+1], mesh[idx2], mesh[idx2+1], paint)
                     }
                 }

                 val handleRadius = 15f / ((layer.scaleX + layer.scaleY)/2f)
                 handlePaint.color = Color.YELLOW
                 for (i in 0 until (mesh.size / 2)) {
                     canvas.drawCircle(mesh[i*2], mesh[i*2+1], handleRadius, handlePaint)
                 }
             }
             canvas.restore()
             return
        }

        if (isPerspectiveMode && layer is TextLayer) {
            val pts = layer.perspectivePoints
            if (pts != null && pts.size >= 8) {
                paint.style = Paint.Style.STROKE
                paint.color = Color.CYAN
                paint.strokeWidth = 2f
                val path = Path()
                path.moveTo(pts[0], pts[1])
                path.lineTo(pts[2], pts[3])
                path.lineTo(pts[4], pts[5])
                path.lineTo(pts[6], pts[7])
                path.close()
                canvas.drawPath(path, paint)

                val handleRadius = 20f / ((layer.scaleX + layer.scaleY)/2f)
                handlePaint.color = Color.CYAN

                canvas.drawCircle(pts[0], pts[1], handleRadius, handlePaint)
                canvas.drawCircle(pts[2], pts[3], handleRadius, handlePaint)
                canvas.drawCircle(pts[4], pts[5], handleRadius, handlePaint)
                canvas.drawCircle(pts[6], pts[7], handleRadius, handlePaint)
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

        paint.style = Paint.Style.STROKE
        paint.color = Color.BLUE
        paint.strokeWidth = 3f / avgScale
        val box = RectF(-halfW - 10, -halfH - 10, halfW + 10, halfH + 10)
        canvas.drawRect(box, paint)

        if (currentMode == Mode.ERASE_LAYER) {
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

        drawIconHandle(-halfW - handleOffset, -halfH - handleOffset, pathDelete, Color.RED)
        drawIconHandle(halfW + handleOffset, -halfH - handleOffset, pathRotate, Color.GREEN)
        drawIconHandle(halfW + handleOffset, halfH + handleOffset, pathResize, Color.BLUE)
        drawIconHandle(-halfW - handleOffset, 0f, pathStretchH, Color.DKGRAY)
        drawIconHandle(0f, halfH + handleOffset, pathStretchV, Color.DKGRAY)

        if (layer is TextLayer) {
             drawIconHandle(halfW + handleOffset, 0f, pathBoxWidth, Color.MAGENTA)
        }

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

        canvas.restore()
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
                    currentInpaintPath.reset()
                    currentInpaintPath.moveTo(cx, cy)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        currentInpaintPath.lineTo(cx, cy)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!currentInpaintPath.isEmpty) {
                         if (currentInpaintTool == InpaintTool.LASSO) {
                             currentInpaintPath.close()
                         }
                         inpaintOps.add(Pair(Path(currentInpaintPath), currentInpaintTool))
                         redoOps.clear()
                         currentInpaintPath.reset()
                    }
                    invalidate()
                }
            }
            return true
        }

        if (currentMode == Mode.ERASE_LAYER && selectedLayer is TextLayer) {
             val layer = selectedLayer as TextLayer
             if (pointerCount >= 2 || (event.actionMasked != MotionEvent.ACTION_DOWN && currentMode == Mode.PAN_ZOOM)) {
                 currentMode = Mode.PAN_ZOOM
                 scaleDetector.onTouchEvent(event)
                 gestureDetector.onTouchEvent(event)
                 if (event.actionMasked == MotionEvent.ACTION_UP) currentMode = Mode.ERASE_LAYER
                 return true
             }

             val localPoint = floatArrayOf(cx, cy)
             val globalToLocal = Matrix()
             globalToLocal.postTranslate(-layer.x, -layer.y)
             globalToLocal.postRotate(-layer.rotation)
             globalToLocal.postScale(1/layer.scaleX, 1/layer.scaleY)
             globalToLocal.mapPoints(localPoint)
             val lx = localPoint[0]
             val ly = localPoint[1]

             val w = layer.getWidth().toInt().coerceAtLeast(1)
             val h = layer.getHeight().toInt().coerceAtLeast(1)
             val maskX = lx + w/2f
             val maskY = ly + h/2f

             when(event.actionMasked) {
                 MotionEvent.ACTION_DOWN -> {
                     currentLayerErasePath.reset()
                     currentLayerErasePath.moveTo(maskX, maskY)
                     if (layer.eraseMask == null) {
                         layer.eraseMask = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                     }
                 }
                 MotionEvent.ACTION_MOVE -> {
                     currentLayerErasePath.lineTo(maskX, maskY)
                     if (layer.eraseMask != null) {
                         val c = Canvas(layer.eraseMask!!)
                         val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                             color = Color.BLACK
                             style = Paint.Style.STROKE
                             strokeWidth = layerEraseSize
                             alpha = layerEraseOpacity
                             strokeCap = Paint.Cap.ROUND
                             strokeJoin = Paint.Join.ROUND
                             if (layerEraseHardness < 100) {
                                 val radius = layerEraseSize / 2f
                                 val blur = radius * (1f - (layerEraseHardness / 100f))
                                 if (blur > 0.5f) {
                                    maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                                 }
                             }
                         }
                         c.drawPath(currentLayerErasePath, p)
                         invalidate()
                     }
                 }
                 MotionEvent.ACTION_UP -> {
                     if (!currentLayerErasePath.isEmpty) {
                         layer.addErasePath(Path(currentLayerErasePath), layerEraseSize, layerEraseOpacity, layerEraseHardness)
                         currentLayerErasePath.reset()
                     }
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

                    if (layer is TextLayer && layer.isWarp && isWarpToolActive) {
                         val mesh = layer.warpMesh
                         if (mesh != null) {
                             val hitRadius = 40f / ((layer.scaleX + layer.scaleY)/2f)
                             var bestIdx = -1
                             var minD = Float.MAX_VALUE

                             for (i in 0 until (mesh.size / 2)) {
                                 val d = getDistance(lx, ly, mesh[i*2], mesh[i*2+1])
                                 if (d < hitRadius && d < minD) {
                                     minD = d
                                     bestIdx = i
                                 }
                             }

                             if (bestIdx != -1) {
                                 warpPointIndex = bestIdx
                                 currentMode = Mode.WARP_DRAG
                                 return true
                             }
                         }
                    }

                    if (isPerspectiveMode && layer is TextLayer) {
                         val pts = layer.perspectivePoints
                         if (pts != null) {
                             val hitRadius = 40f / ((layer.scaleX + layer.scaleY)/2f)
                             if (getDistance(lx, ly, pts[0], pts[1]) < hitRadius) { currentMode = Mode.PERSPECTIVE_DRAG_TL; return true }
                             if (getDistance(lx, ly, pts[2], pts[3]) < hitRadius) { currentMode = Mode.PERSPECTIVE_DRAG_TR; return true }
                             if (getDistance(lx, ly, pts[4], pts[5]) < hitRadius) { currentMode = Mode.PERSPECTIVE_DRAG_BR; return true }
                             if (getDistance(lx, ly, pts[6], pts[7]) < hitRadius) { currentMode = Mode.PERSPECTIVE_DRAG_BL; return true }
                         }
                    }

                    if (!isPerspectiveMode) {
                        val halfW = layer.getWidth() / 2f
                        val halfH = layer.getHeight() / 2f

                        val geometry = getHandleGeometry(layer)
                        val handleOffset = geometry.offset
                        val hitRadius = geometry.radius * 2.0f

                        val topY = -halfH - handleOffset * 2.5f
                        val iconSpacing = geometry.radius * 2.5f
                        val dupX = -iconSpacing / 1.5f
                        val copyX = iconSpacing / 1.5f

                        if (getDistance(lx, ly, dupX, topY) <= hitRadius) {
                            com.astral.typer.utils.UndoManager.saveState(layers)
                            val newLayer = layer.clone()
                            newLayer.x += 20
                            newLayer.y += 20
                            layers.add(newLayer)
                            selectLayer(newLayer)
                            return true
                        }

                        if (getDistance(lx, ly, copyX, topY) <= hitRadius) {
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
                        if (getDistance(lx, ly, -halfW - handleOffset, 0f) <= hitRadius) {
                            com.astral.typer.utils.UndoManager.saveState(layers)
                            currentMode = Mode.STRETCH_H
                            initialScaleX = layer.scaleX
                            centerX = layer.x
                            centerY = layer.y
                            startX = lx
                            return true
                        }
                        if (getDistance(lx, ly, 0f, halfH + handleOffset) <= hitRadius) {
                            com.astral.typer.utils.UndoManager.saveState(layers)
                            currentMode = Mode.STRETCH_V
                            initialScaleY = layer.scaleY
                            centerX = layer.x
                            centerY = layer.y
                            startY = ly
                            return true
                        }
                        if (layer is TextLayer && getDistance(lx, ly, halfW + handleOffset, 0f) <= hitRadius) {
                            com.astral.typer.utils.UndoManager.saveState(layers)
                            currentMode = Mode.BOX_WIDTH
                            layer.isWarp = false
                            layer.isPerspective = false
                            layer.warpMesh = null
                            layer.perspectivePoints = null

                            initialBoxWidth = layer.getWidth()
                            centerX = layer.x
                            centerY = layer.y
                            startDist = lx
                            return true
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
                        invalidate()
                    }
                } else {
                    currentMode = Mode.NONE
                    selectLayer(null)
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (currentMode == Mode.NONE || currentMode == Mode.PAN_ZOOM) return true

                if (!hasMoved && getDistance(cx, cy, startTouchX, startTouchY) > 5f) {
                    hasMoved = true
                }

                if (selectedLayer != null) {
                    val layer = selectedLayer!!

                    if (currentMode == Mode.WARP_DRAG && layer is TextLayer) {
                         val localPoint = floatArrayOf(cx, cy)
                         val globalToLocal = Matrix()
                         globalToLocal.postTranslate(-layer.x, -layer.y)
                         globalToLocal.postRotate(-layer.rotation)
                         globalToLocal.postScale(1/layer.scaleX, 1/layer.scaleY)
                         globalToLocal.mapPoints(localPoint)

                         val mesh = layer.warpMesh
                         if (mesh != null && warpPointIndex != -1) {
                             mesh[warpPointIndex*2] = localPoint[0]
                             mesh[warpPointIndex*2+1] = localPoint[1]
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

                    if (isPerspectiveMode && layer is TextLayer && layer.perspectivePoints != null) {
                         val localPoint = floatArrayOf(cx, cy)
                         val globalToLocal = Matrix()
                         globalToLocal.postTranslate(-layer.x, -layer.y)
                         globalToLocal.postRotate(-layer.rotation)
                         globalToLocal.postScale(1/layer.scaleX, 1/layer.scaleY)
                         globalToLocal.mapPoints(localPoint)

                         val lx = localPoint[0]
                         val ly = localPoint[1]

                         val pts = layer.perspectivePoints!!

                         when(currentMode) {
                             Mode.PERSPECTIVE_DRAG_TL -> { pts[0] = lx; pts[1] = ly }
                             Mode.PERSPECTIVE_DRAG_TR -> { pts[2] = lx; pts[3] = ly }
                             Mode.PERSPECTIVE_DRAG_BR -> { pts[4] = lx; pts[5] = ly }
                             Mode.PERSPECTIVE_DRAG_BL -> { pts[6] = lx; pts[7] = ly }
                             Mode.DRAG_LAYER -> {
                                 val dx = cx - lastTouchX
                                 val dy = cy - lastTouchY
                                 layer.x += dx
                                 layer.y += dy
                                 lastTouchX = cx
                                 lastTouchY = cy
                             }
                             else -> {}
                         }
                         invalidate()
                         return true
                    }

                    when (currentMode) {
                        Mode.DRAG_LAYER -> {
                            var dx = cx - lastTouchX
                            var dy = cy - lastTouchY

                            val nextX = layer.x + dx
                            val nextY = layer.y + dy

                            val snapThreshold = 20f
                            var snappedX = false
                            var snappedY = false

                            if (abs(nextX - canvasWidth / 2f) < snapThreshold) {
                                dx = (canvasWidth / 2f) - layer.x
                                showVerticalCenterLine = true
                                snappedX = true
                            } else {
                                showVerticalCenterLine = false
                            }

                            if (abs(nextY - canvasHeight / 2f) < snapThreshold) {
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
                                 val s = (proj / (layer.getHeight() / 2f)).toFloat()
                                 if (abs(s) >= 0.1f) {
                                     layer.scaleY = s
                                     invalidate()
                                     onLayerUpdateListener?.onLayerUpdate(layer)
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
                    showVerticalCenterLine = false
                    showHorizontalCenterLine = false
                    invalidate()
                }
                if (currentMode == Mode.DRAG_LAYER && !hasMoved && wasSelectedInitially && selectedLayer != null) {
                    onLayerEditListener?.onLayerDoubleTap(selectedLayer!!)
                }
                currentMode = Mode.NONE
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
             if (hitLayer == null) {
                  centerCanvas()
             }
             return true
        }

    }
}
