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

    // Inpaint Tools
    private val inpaintPath = Path()
    private val inpaintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 50f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 128
    }

    var onMaskDrawn: ((android.graphics.Bitmap) -> Unit)? = null

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

    fun getBackgroundImage(): android.graphics.Bitmap? {
        return canvasBitmap
    }

    fun getLayers(): MutableList<Layer> {
        return layers
    }

    fun setLayers(newLayers: List<Layer>) {
        layers.clear()
        layers.addAll(newLayers)
        selectedLayer = null // Reset selection or try to maintain?
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
        INPAINT
    }

    private var currentMode = Mode.NONE

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
    private val invertedMatrix = Matrix() // For mapping touch to canvas coords

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
        val layer = TextLayer(text).apply {
            x = canvasWidth / 2f
            y = canvasHeight / 2f
            color = Color.BLACK
        }
        layers.add(layer)
        selectLayer(layer)
    }

    fun selectLayer(layer: Layer?) {
        if (selectedLayer != layer) {
            selectedLayer = layer
            onLayerSelectedListener?.onLayerSelected(layer)
            // Reset mode on selection change
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

        // Initial Center
        post {
             centerCanvas()
        }
    }

    fun setBackgroundImage(bitmap: android.graphics.Bitmap) {
        canvasBitmap = bitmap
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

        // Draw Background Image
        canvasBitmap?.let {
             val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
             canvas.drawBitmap(it, null, RectF(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat()), bitmapPaint)
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

        // Fit center with some padding
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

        // Save current state
        canvas.save()

        // Apply Camera Matrix (Zoom/Pan)
        canvas.concat(viewMatrix)

        // Draw Canvas Background (The "Paper")
        paint.color = canvasColor
        paint.style = Paint.Style.FILL
        canvas.drawRect(backgroundRect, paint)

        // Draw Background Image if exists
        canvasBitmap?.let {
             val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
             canvas.drawBitmap(it, null, backgroundRect, bitmapPaint)
        }

        // Draw Border
        paint.color = Color.LTGRAY
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRect(backgroundRect, paint)

        // Draw Layers
        drawScene(canvas)

        // Draw Inpaint Path (in World Space)
        if (isInpaintMode && !inpaintPath.isEmpty) {
            canvas.drawPath(inpaintPath, inpaintPaint)
        }

        // Draw Selection Overlay
        if (currentMode != Mode.EYEDROPPER && !isInpaintMode) {
             selectedLayer?.let { drawSelectionOverlay(canvas, it) }
        }

        canvas.restore()

        // Draw Eyedropper UI (Overlay on top of everything)
        if (currentMode == Mode.EYEDROPPER) {
             // 1. Crosshair (In Screen Space)
             paint.style = Paint.Style.STROKE
             paint.color = Color.BLACK
             paint.strokeWidth = 2f
             val size = 30f
             canvas.drawLine(eyedropperScreenX - size, eyedropperScreenY, eyedropperScreenX + size, eyedropperScreenY, paint)
             canvas.drawLine(eyedropperScreenX, eyedropperScreenY - size, eyedropperScreenX, eyedropperScreenY + size, paint)
             paint.color = Color.WHITE
             paint.strokeWidth = 1f
             canvas.drawLine(eyedropperScreenX - size, eyedropperScreenY - 1f, eyedropperScreenX + size, eyedropperScreenY - 1f, paint) // pseudo shadow

             // 2. Preview Box (In Screen Space)
             canvas.save()
             val boxSize = 200f
             val boxMargin = 30f
             val boxRect = RectF(width - boxSize - boxMargin, boxMargin, width - boxMargin, boxMargin + boxSize)

             // Background for box
             paint.style = Paint.Style.FILL
             paint.color = Color.BLACK
             canvas.drawRect(boxRect, paint)

             // Draw zoomed content
             canvas.save()
             canvas.clipRect(boxRect)
             canvas.translate(boxRect.centerX(), boxRect.centerY())
             val zoomLevel = 4f
             canvas.scale(zoomLevel, zoomLevel)
             canvas.translate(-eyedropperX, -eyedropperY)

             // Draw White Background first (Canvas Color)
             paint.color = canvasColor
             paint.style = Paint.Style.FILL
             canvas.drawRect(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat(), paint)
             // Image
             canvasBitmap?.let {
                 val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                 canvas.drawBitmap(it, null, RectF(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat()), bitmapPaint)
             }
             // Layers
             for (layer in layers) {
                 layer.draw(canvas)
             }

             canvas.restore() // End Clip/Transform

             // Draw Border for Box
             paint.style = Paint.Style.STROKE
             paint.color = Color.WHITE
             paint.strokeWidth = 4f
             canvas.drawRect(boxRect, paint)

             // Center Crosshair in Box
             paint.color = Color.RED
             paint.strokeWidth = 2f
             canvas.drawLine(boxRect.centerX() - 10, boxRect.centerY(), boxRect.centerX() + 10, boxRect.centerY(), paint)
             canvas.drawLine(boxRect.centerX(), boxRect.centerY() - 10, boxRect.centerX(), boxRect.centerY() + 10, paint)

             canvas.restore()
        }
    }

    private fun drawScene(canvas: Canvas) {
        // Draw Layers
        for (layer in layers) {
            layer.draw(canvas)
        }
    }

    private fun drawSelectionOverlay(canvas: Canvas, layer: Layer) {
        canvas.save()
        canvas.translate(layer.x, layer.y)
        canvas.rotate(layer.rotation)
        canvas.scale(layer.scaleX, layer.scaleY)

        // If Perspective Mode, we hide normal box and show 4 corners
        if (isPerspectiveMode && layer is TextLayer) {
            // "semua ikon control hilang... sudut jadi titik"
            val pts = layer.perspectivePoints
            if (pts != null && pts.size >= 8) {
                // Draw connecting lines (the deformed box)
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

                // Draw 4 handles
                val handleRadius = 20f / ((layer.scaleX + layer.scaleY)/2f)
                handlePaint.color = Color.CYAN

                canvas.drawCircle(pts[0], pts[1], handleRadius, handlePaint) // TL
                canvas.drawCircle(pts[2], pts[3], handleRadius, handlePaint) // TR
                canvas.drawCircle(pts[4], pts[5], handleRadius, handlePaint) // BR
                canvas.drawCircle(pts[6], pts[7], handleRadius, handlePaint) // BL
            }

            canvas.restore()
            return
        }

        val halfW = layer.getWidth() / 2f
        val halfH = layer.getHeight() / 2f

        // Box
        paint.style = Paint.Style.STROKE
        paint.color = Color.BLUE
        paint.strokeWidth = 3f / ((layer.scaleX + layer.scaleY)/2f) // Keep stroke constant width visually
        val box = RectF(-halfW - 10, -halfH - 10, halfW + 10, halfH + 10)
        canvas.drawRect(box, paint)

        // --- Draw Handles ---
        fun drawHandle(x: Float, y: Float, color: Int) {
            handlePaint.color = color
            val size = HANDLE_RADIUS / ((layer.scaleX + layer.scaleY)/2f)
            canvas.drawCircle(x, y, size, handlePaint)
            canvas.drawCircle(x, y, size, strokePaint)
        }

        // 1. Delete Handle (Top-Left) -> X
        drawHandle(-halfW - HANDLE_OFFSET, -halfH - HANDLE_OFFSET, Color.RED)
        val xSize = 15f / ((layer.scaleX + layer.scaleY)/2f)
        val cx = -halfW - HANDLE_OFFSET
        val cy = -halfH - HANDLE_OFFSET
        canvas.drawLine(cx - xSize, cy - xSize, cx + xSize, cy + xSize, iconPaint)
        canvas.drawLine(cx + xSize, cy - xSize, cx - xSize, cy + xSize, iconPaint)

        // 2. Rotate Handle (Top-Right) -> Circle Arrow
        drawHandle(halfW + HANDLE_OFFSET, -halfH - HANDLE_OFFSET, Color.GREEN)
        canvas.drawCircle(halfW + HANDLE_OFFSET, -halfH - HANDLE_OFFSET, 10f / ((layer.scaleX + layer.scaleY)/2f), iconPaint)

        // 3. Resize Handle (Bottom-Right) -> Diagonal Arrow
        drawHandle(halfW + HANDLE_OFFSET, halfH + HANDLE_OFFSET, Color.BLUE)

        // 4. Stretch Horizontal (Left-Middle) -> Horizontal Arrow
        drawHandle(-halfW - HANDLE_OFFSET, 0f, Color.DKGRAY)
        val sx = -halfW - HANDLE_OFFSET
        val sSize = 15f / ((layer.scaleX + layer.scaleY)/2f)
        canvas.drawLine(sx - sSize, 0f, sx + sSize, 0f, iconPaint)

        // 5. Stretch Vertical (Bottom-Middle) -> Vertical Arrow
        drawHandle(0f, halfH + HANDLE_OFFSET, Color.DKGRAY)
        val sy = halfH + HANDLE_OFFSET
        canvas.drawLine(0f, sy - sSize, 0f, sy + sSize, iconPaint)

        // 6. Box Width (Right-Middle) -> Rect icon (Resize box)
        if (layer is TextLayer) {
             drawHandle(halfW + HANDLE_OFFSET, 0f, Color.MAGENTA)
             val bx = halfW + HANDLE_OFFSET
             canvas.drawRect(bx - 10, -10f, bx + 10, 10f, iconPaint)
        }

        // --- Top Action Icons (Duplicate & Copy Style) ---
        val topY = -halfH - HANDLE_OFFSET * 2.5f
        val iconSize = 30f / ((layer.scaleX + layer.scaleY)/2f)

        // Duplicate Icon
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

        // Copy Style Icon
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
        // Multi-touch tracking
        val pointerCount = event.pointerCount

        // If 2 fingers are down, we force PAN_ZOOM mode
        if (pointerCount >= 2) {
            if (currentMode != Mode.EYEDROPPER) {
                currentMode = Mode.PAN_ZOOM
            }
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event) // Allow scroll/pan
            return true
        }

        // Map touch to canvas coordinates
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
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    inpaintPath.reset()
                    inpaintPath.moveTo(cx, cy)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    inpaintPath.lineTo(cx, cy)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    // Capture mask and trigger inpaint
                    val mask = renderMaskToBitmap()
                    onMaskDrawn?.invoke(mask)
                    inpaintPath.reset()
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

                // 1. Check Handles (if layer selected)
                if (selectedLayer != null) {
                    val layer = selectedLayer!!

                    // Transform touch to local layer space
                    val localPoint = floatArrayOf(cx, cy)
                    val globalToLocal = Matrix()
                    globalToLocal.postTranslate(-layer.x, -layer.y)
                    globalToLocal.postRotate(-layer.rotation)
                    globalToLocal.postScale(1/layer.scaleX, 1/layer.scaleY)
                    globalToLocal.mapPoints(localPoint)

                    val lx = localPoint[0]
                    val ly = localPoint[1]

                    // Perspective Mode Handling
                    if (isPerspectiveMode && layer is TextLayer) {
                         val pts = layer.perspectivePoints
                         if (pts != null) {
                             val hitRadius = 40f / ((layer.scaleX + layer.scaleY)/2f)
                             // Points: 0,1 TL; 2,3 TR; 4,5 BR; 6,7 BL
                             if (getDistance(lx, ly, pts[0], pts[1]) < hitRadius) { currentMode = Mode.PERSPECTIVE_DRAG_TL; return true }
                             if (getDistance(lx, ly, pts[2], pts[3]) < hitRadius) { currentMode = Mode.PERSPECTIVE_DRAG_TR; return true }
                             if (getDistance(lx, ly, pts[4], pts[5]) < hitRadius) { currentMode = Mode.PERSPECTIVE_DRAG_BR; return true }
                             if (getDistance(lx, ly, pts[6], pts[7]) < hitRadius) { currentMode = Mode.PERSPECTIVE_DRAG_BL; return true }
                         }
                         // If miss, do not allow drag layer in perspective mode to avoid accidents?
                         // Prompt says "sudut ... yang bisa ditarik". Doesn't say we can't move layer.
                         // But usually perspective mode is focused on warping.
                         // Let's assume hitting body does nothing or moves layer.
                    }

                    if (!isPerspectiveMode) {
                        val halfW = layer.getWidth() / 2f
                        val halfH = layer.getHeight() / 2f
                        val hitRadius = HANDLE_RADIUS * 1.5f

                        // Top Actions
                        val topY = -halfH - HANDLE_OFFSET * 2.5f
                        val iconSize = 30f / ((layer.scaleX + layer.scaleY)/2f)
                        val dupX = -20f - iconSize
                        val copyX = 20f + iconSize

                        if (getDistance(lx, ly, dupX, topY) <= hitRadius) {
                            // DUPLICATE
                            com.astral.typer.utils.UndoManager.saveState(layers)
                            val newLayer = layer.clone()
                            newLayer.x += 20
                            newLayer.y += 20
                            layers.add(newLayer)
                            selectLayer(newLayer)
                            return true
                        }

                        if (getDistance(lx, ly, copyX, topY) <= hitRadius) {
                            // COPY STYLE
                            if (layer is TextLayer) {
                                com.astral.typer.utils.StyleManager.copyStyle(layer)
                                com.astral.typer.utils.StyleManager.saveStyle(layer)
                                android.widget.Toast.makeText(context, "Style Copied to Menu", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            return true
                        }

                        // Standard Handles
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

                // 2. Check for layer hit
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

                    // Perspective Drag Logic
                    if (isPerspectiveMode && layer is TextLayer && layer.perspectivePoints != null) {
                         // We need to map global touch back to local space to update the points
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
                        }
                        Mode.ROTATE_LAYER -> {
                            val currentAngle = getAngle(centerX, centerY, cx, cy)
                            val angleDiff = currentAngle - startAngle
                            layer.rotation = initialRotation + angleDiff
                            invalidate()
                        }
                        Mode.RESIZE_LAYER -> {
                            val currentDist = getDistance(centerX, centerY, cx, cy)
                            if (startDist > 0) {
                                val scaleFactor = currentDist / startDist
                                layer.scaleX = initialScaleX * scaleFactor
                                layer.scaleY = initialScaleY * scaleFactor
                                invalidate()
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

    // --- Gesture Handling ---

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

        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE // White mask
            style = Paint.Style.STROKE
            strokeWidth = 50f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(inpaintPath, maskPaint)
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
