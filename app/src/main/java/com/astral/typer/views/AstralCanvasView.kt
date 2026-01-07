package com.astral.typer.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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

class AstralCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Canvas Configuration
    private var canvasWidth = 1080
    private var canvasHeight = 1080
    private var canvasColor = Color.WHITE
    private var canvasBitmap: android.graphics.Bitmap? = null

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
    private var isEraseLayerMode = false

    // Inpaint Tools
    enum class InpaintTool {
        BRUSH, ERASER, LASSO
    }

    var currentInpaintTool = InpaintTool.BRUSH
    private val inpaintOps = mutableListOf<Pair<Path, InpaintTool>>()
    private val redoOps = mutableListOf<Pair<Path, InpaintTool>>()
    private var currentInpaintPath = Path()

    // Layer Erase Tools
    private val layerErasePath = Path()
    var layerEraseBrushSize = 50f
    var layerEraseOpacity = 255
    var layerEraseHardness = 100f // 0 to 100

    private val layerErasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK // Color doesn't matter for DST_OUT, alpha does (Wait, drawing INTO eraseBitmap)
        // eraseBitmap is used with DST_OUT.
        // So pixels in eraseBitmap should be opaque where we want to erase.
        // So we draw with normal SRC/SRC_OVER into eraseBitmap.
    }

    // Cached Mask Bitmap
    private var cachedMaskBitmap: android.graphics.Bitmap? = null
    private var isMaskDirty = true

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

    fun getInpaintMask(): android.graphics.Bitmap {
        return getCachedInpaintMask()
    }

    private fun getCachedInpaintMask(): android.graphics.Bitmap {
        if (cachedMaskBitmap == null || cachedMaskBitmap?.width != canvasWidth || cachedMaskBitmap?.height != canvasHeight) {
            cachedMaskBitmap = android.graphics.Bitmap.createBitmap(canvasWidth, canvasHeight, android.graphics.Bitmap.Config.ARGB_8888)
            isMaskDirty = true
        }

        if (isMaskDirty) {
            val canvas = Canvas(cachedMaskBitmap!!)
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR) // Clear

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
            isMaskDirty = false
        }
        return cachedMaskBitmap!!
    }

    fun clearInpaintMask() {
        inpaintOps.clear()
        redoOps.clear()
        currentInpaintPath.reset()
        isMaskDirty = true
        invalidate()
    }

    fun undoInpaintMask(): Boolean {
        if (inpaintOps.isNotEmpty()) {
            val last = inpaintOps.removeAt(inpaintOps.size - 1)
            redoOps.add(last)
            isMaskDirty = true
            invalidate()
            return true
        }
        return false
    }

    fun redoInpaintMask(): Boolean {
        if (redoOps.isNotEmpty()) {
            val last = redoOps.removeAt(redoOps.size - 1)
            inpaintOps.add(last)
            isMaskDirty = true
            invalidate()
            return true
        }
        return false
    }

    fun setInpaintMode(enabled: Boolean) {
        isInpaintMode = enabled
        if (enabled) {
            selectLayer(null)
            currentMode = Mode.INPAINT
        } else {
            currentMode = Mode.NONE
        }
        invalidate()
    }

    fun setEraseLayerMode(enabled: Boolean) {
        isEraseLayerMode = enabled
        if (enabled) {
            currentMode = Mode.ERASE_LAYER
            // Ensure layer is prepared
            val layer = selectedLayer as? TextLayer
            if (layer != null) {
                 val w = layer.getWidth().toInt()
                 val h = layer.getHeight().toInt()
                 // Make it slightly larger to avoid clipping brush edges
                 layer.ensureEraseBitmap(max(w, 10), max(h, 10))
            }
        } else {
            currentMode = Mode.NONE
        }
        invalidate()
    }

    fun getViewportCenter(): FloatArray {
        val inverse = Matrix()
        viewMatrix.invert(inverse)
        val center = floatArrayOf(width / 2f, height / 2f)
        inverse.mapPoints(center)
        return center
    }

    fun getBackgroundImage(): android.graphics.Bitmap? {
        return canvasBitmap
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

    // Handles Constants
    private val HANDLE_RADIUS = 30f
    private val HANDLE_OFFSET = 40f

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
        ERASE_LAYER
    }

    private var currentMode = Mode.NONE
    private var warpPointIndex = -1

    var onColorPickedListener: ((Int) -> Unit)? = null

    interface OnLayerUpdateListener {
        fun onLayerUpdate(layer: Layer)
    }
    var onLayerUpdateListener: OnLayerUpdateListener? = null

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

    // Interaction State
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

    // Interaction Reference Points (for calculation)
    private var centerX = 0f
    private var centerY = 0f
    private var startAngle = 0f
    private var startDist = 0f
    private var startX = 0f // Local X
    private var startY = 0f // Local Y

    // Eyedropper state
    private var eyedropperX = 0f
    private var eyedropperY = 0f
    private var eyedropperScreenX = 0f
    private var eyedropperScreenY = 0f

    // Camera / Viewport
    private val viewMatrix = Matrix()
    private val invertedMatrix = Matrix()

    // Gestures
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    // Multi-touch tracking
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var secondaryPointerId = MotionEvent.INVALID_POINTER_ID

    // Listener
    interface OnLayerSelectedListener {
        fun onLayerSelected(layer: Layer?)
    }

    interface OnLayerEditListener {
        fun onLayerDoubleTap(layer: Layer)
    }

    var onLayerSelectedListener: OnLayerSelectedListener? = null
    var onLayerEditListener: OnLayerEditListener? = null

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
            (layer as? TextLayer)?.isPerspective = false
            invalidate()
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
        post { centerCanvas() }
    }

    fun setBackgroundImage(bitmap: android.graphics.Bitmap) {
        canvasBitmap = bitmap
        invalidate()
    }

    fun renderToBitmap(): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(canvasWidth, canvasHeight, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint()
        bgPaint.color = canvasColor
        bgPaint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat(), bgPaint)

        canvasBitmap?.let {
             val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
             canvas.drawBitmap(it, null, RectF(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat()), bitmapPaint)
        }

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

        paint.color = canvasColor
        paint.style = Paint.Style.FILL
        canvas.drawRect(backgroundRect, paint)

        canvasBitmap?.let {
             val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
             canvas.drawBitmap(it, null, backgroundRect, bitmapPaint)
        }

        paint.color = Color.LTGRAY
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRect(backgroundRect, paint)

        drawScene(canvas)

        if (isInpaintMode) {
            val saveCount = canvas.saveLayer(null, null)
            val mask = getCachedInpaintMask()
            val p = Paint()
            p.colorFilter = android.graphics.PorterDuffColorFilter(Color.RED, android.graphics.PorterDuff.Mode.SRC_IN)
            p.alpha = 128
            canvas.drawBitmap(mask, 0f, 0f, p)
            if (!currentInpaintPath.isEmpty) {
                 when(currentInpaintTool) {
                    InpaintTool.BRUSH -> canvas.drawPath(currentInpaintPath, inpaintPaint)
                    InpaintTool.ERASER -> canvas.drawPath(currentInpaintPath, eraserPaint)
                    InpaintTool.LASSO -> canvas.drawPath(currentInpaintPath, lassoStrokePaint)
                }
            }
            canvas.restoreToCount(saveCount)
        }

        if (currentMode != Mode.EYEDROPPER && !isInpaintMode && !isEraseLayerMode) {
             selectedLayer?.let { drawSelectionOverlay(canvas, it) }
        }

        // Visualize Erase Mode? (Maybe circle for brush)
        if (isEraseLayerMode && selectedLayer != null) {
            // Draw visual cues if needed (e.g. outline of layer bounds)
            val layer = selectedLayer!!
            canvas.save()
            canvas.translate(layer.x, layer.y)
            canvas.rotate(layer.rotation)
            canvas.scale(layer.scaleX, layer.scaleY)
            paint.style = Paint.Style.STROKE
            paint.color = Color.RED
            paint.strokeWidth = 2f / ((layer.scaleX + layer.scaleY)/2f)
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
            val halfW = layer.getWidth()/2f
            val halfH = layer.getHeight()/2f
            canvas.drawRect(-halfW, -halfH, halfW, halfH, paint)
            paint.pathEffect = null
            canvas.restore()
        }

        canvas.restore()

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
             canvasBitmap?.let {
                 val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                 canvas.drawBitmap(it, null, RectF(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat()), bitmapPaint)
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

        if (layer is TextLayer && layer.isWarp) {
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

        paint.style = Paint.Style.STROKE
        paint.color = Color.BLUE
        paint.strokeWidth = 3f / ((layer.scaleX + layer.scaleY)/2f)
        val box = RectF(-halfW - 10, -halfH - 10, halfW + 10, halfH + 10)
        canvas.drawRect(box, paint)

        fun drawHandle(x: Float, y: Float, color: Int) {
            handlePaint.color = color
            val size = HANDLE_RADIUS / ((layer.scaleX + layer.scaleY)/2f)
            canvas.drawCircle(x, y, size, handlePaint)
            canvas.drawCircle(x, y, size, strokePaint)
        }

        drawHandle(-halfW - HANDLE_OFFSET, -halfH - HANDLE_OFFSET, Color.RED)
        val xSize = 15f / ((layer.scaleX + layer.scaleY)/2f)
        val cx = -halfW - HANDLE_OFFSET
        val cy = -halfH - HANDLE_OFFSET
        canvas.drawLine(cx - xSize, cy - xSize, cx + xSize, cy + xSize, iconPaint)
        canvas.drawLine(cx + xSize, cy - xSize, cx - xSize, cy + xSize, iconPaint)

        drawHandle(halfW + HANDLE_OFFSET, -halfH - HANDLE_OFFSET, Color.GREEN)
        canvas.drawCircle(halfW + HANDLE_OFFSET, -halfH - HANDLE_OFFSET, 10f / ((layer.scaleX + layer.scaleY)/2f), iconPaint)

        drawHandle(halfW + HANDLE_OFFSET, halfH + HANDLE_OFFSET, Color.BLUE)

        drawHandle(-halfW - HANDLE_OFFSET, 0f, Color.DKGRAY)
        val sx = -halfW - HANDLE_OFFSET
        val sSize = 15f / ((layer.scaleX + layer.scaleY)/2f)
        canvas.drawLine(sx - sSize, 0f, sx + sSize, 0f, iconPaint)

        drawHandle(0f, halfH + HANDLE_OFFSET, Color.DKGRAY)
        val sy = halfH + HANDLE_OFFSET
        canvas.drawLine(0f, sy - sSize, 0f, sy + sSize, iconPaint)

        if (layer is TextLayer) {
             drawHandle(halfW + HANDLE_OFFSET, 0f, Color.MAGENTA)
             val bx = halfW + HANDLE_OFFSET
             canvas.drawRect(bx - 10, -10f, bx + 10, 10f, iconPaint)
        }

        val topY = -halfH - HANDLE_OFFSET * 2.5f
        val iconSize = 30f / ((layer.scaleX + layer.scaleY)/2f)

        val dupX = -20f
        handlePaint.color = Color.DKGRAY
        canvas.drawCircle(dupX - iconSize, topY, iconSize, handlePaint)
        canvas.drawCircle(dupX - iconSize, topY, iconSize, strokePaint)
        val dSize = iconSize * 0.5f
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRect(dupX - iconSize - dSize/2, topY - dSize/2, dupX - iconSize + dSize/2, topY + dSize/2, paint)
        canvas.drawRect(dupX - iconSize - dSize/2 + 5, topY - dSize/2 - 5, dupX - iconSize + dSize/2 + 5, topY + dSize/2 - 5, paint)

        val copyX = 20f
        canvas.drawCircle(copyX + iconSize, topY, iconSize, handlePaint)
        canvas.drawCircle(copyX + iconSize, topY, iconSize, strokePaint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(copyX + iconSize, topY, dSize, paint)

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
                         isMaskDirty = true
                    }
                    invalidate()
                }
            }
            return true
        }

        // Handle ERASE_LAYER mode
        if (isEraseLayerMode && selectedLayer is TextLayer) {
            if (pointerCount >= 2 || currentMode == Mode.PAN_ZOOM) {
                 currentMode = Mode.PAN_ZOOM
                 scaleDetector.onTouchEvent(event)
                 gestureDetector.onTouchEvent(event)
                 if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    currentMode = Mode.ERASE_LAYER
                 }
                 return true
            }

            val layer = selectedLayer as TextLayer
            // Map global cx,cy to local erase bitmap coordinates
            val localPoint = floatArrayOf(cx, cy)
            val globalToLocal = Matrix()
            globalToLocal.postTranslate(-layer.x, -layer.y)
            globalToLocal.postRotate(-layer.rotation)
            globalToLocal.postScale(1/layer.scaleX, 1/layer.scaleY)
            globalToLocal.mapPoints(localPoint)

            // localPoint is now relative to layer center (0,0)
            // eraseBitmap has (0,0) at top-left, which is (-w/2, -h/2) in local space
            // So we need to shift by w/2, h/2
            val lx = localPoint[0] + layer.getWidth()/2f
            val ly = localPoint[1] + layer.getHeight()/2f

            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    layerErasePath.reset()
                    layerErasePath.moveTo(lx, ly)
                    // Draw point
                    layer.eraseCanvas?.drawPoint(lx, ly, layerErasePaint.apply {
                        strokeWidth = layerEraseBrushSize
                        alpha = layerEraseOpacity
                        if (layerEraseHardness < 100) {
                            // Simple softness via blur?
                            val radius = (layerEraseBrushSize / 2) * ((100 - layerEraseHardness) / 100f)
                            if (radius > 0) maskFilter = android.graphics.BlurMaskFilter(radius, android.graphics.BlurMaskFilter.Blur.NORMAL)
                            else maskFilter = null
                        } else {
                            maskFilter = null
                        }
                    })
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    layerErasePath.lineTo(lx, ly)
                    layer.eraseCanvas?.drawPath(layerErasePath, layerErasePaint.apply {
                        strokeWidth = layerEraseBrushSize
                        alpha = layerEraseOpacity
                    })
                    // Draw segment and reset path to avoid accumulation
                    layerErasePath.reset()
                    layerErasePath.moveTo(lx, ly)
                }
                MotionEvent.ACTION_UP -> {
                    layerErasePath.reset()
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

                    if (layer is TextLayer && layer.isWarp) {
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
                        val hitRadius = HANDLE_RADIUS * 1.5f

                        val topY = -halfH - HANDLE_OFFSET * 2.5f
                        val iconSize = 30f / ((layer.scaleX + layer.scaleY)/2f)
                        val dupX = -20f - iconSize
                        val copyX = 20f + iconSize

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
                                com.astral.typer.utils.StyleManager.saveStyle(layer)
                                android.widget.Toast.makeText(context, "Style Copied to Menu", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            return true
                        }

                        if (getDistance(lx, ly, -halfW - HANDLE_OFFSET, -halfH - HANDLE_OFFSET) <= hitRadius) {
                            com.astral.typer.utils.UndoManager.saveState(layers)
                            deleteSelectedLayer()
                            return true
                        }
                        if (getDistance(lx, ly, halfW + HANDLE_OFFSET, -halfH - HANDLE_OFFSET) <= hitRadius) {
                            currentMode = Mode.ROTATE_LAYER
                            initialRotation = layer.rotation
                            centerX = layer.x
                            centerY = layer.y
                            startAngle = getAngle(centerX, centerY, cx, cy)
                            return true
                        }
                        if (getDistance(lx, ly, halfW + HANDLE_OFFSET, halfH + HANDLE_OFFSET) <= hitRadius) {
                            currentMode = Mode.RESIZE_LAYER
                            initialScaleX = layer.scaleX
                            initialScaleY = layer.scaleY
                            centerX = layer.x
                            centerY = layer.y
                            startDist = getDistance(centerX, centerY, cx, cy)
                            return true
                        }
                        if (getDistance(lx, ly, -halfW - HANDLE_OFFSET, 0f) <= hitRadius) {
                            currentMode = Mode.STRETCH_H
                            initialScaleX = layer.scaleX
                            centerX = layer.x
                            centerY = layer.y
                            startX = lx
                            return true
                        }
                        if (getDistance(lx, ly, 0f, halfH + HANDLE_OFFSET) <= hitRadius) {
                            currentMode = Mode.STRETCH_V
                            initialScaleY = layer.scaleY
                            centerX = layer.x
                            centerY = layer.y
                            startY = ly
                            return true
                        }
                        if (layer is TextLayer && getDistance(lx, ly, halfW + HANDLE_OFFSET, 0f) <= hitRadius) {
                            currentMode = Mode.BOX_WIDTH
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
                    currentMode = Mode.DRAG_LAYER
                    lastTouchX = cx
                    lastTouchY = cy
                    invalidate()
                } else {
                    currentMode = Mode.NONE
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
                            val dx = cx - lastTouchX
                            val dy = cy - lastTouchY
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
                            if (proj > 10) {
                                 layer.scaleX = (proj / (layer.getWidth() / 2f)).toFloat().coerceAtLeast(0.1f)
                                 invalidate()
                                 onLayerUpdateListener?.onLayerUpdate(layer)
                            }
                        }
                        Mode.STRETCH_V -> {
                             val rad = Math.toRadians(layer.rotation.toDouble())
                             val cos = Math.cos(rad)
                             val sin = Math.sin(rad)
                             val dx = cx - centerX
                             val dy = cy - centerY
                             val proj = -dx * sin + dy * cos
                             if (proj > 10) {
                                 layer.scaleY = (proj / (layer.getHeight() / 2f)).toFloat().coerceAtLeast(0.1f)
                                 invalidate()
                                 onLayerUpdateListener?.onLayerUpdate(layer)
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
                                if (proj > 20) {
                                    layer.boxWidth = ((proj / layer.scaleX) * 2f).toFloat()
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

    private fun renderMaskToBitmap(): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(canvasWidth, canvasHeight, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val brushP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 50f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val eraseP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
            style = Paint.Style.STROKE
            strokeWidth = 50f
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
        return bitmap
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
