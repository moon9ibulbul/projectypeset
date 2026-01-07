package com.astral.typer.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
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

    fun getSelectedLayer(): Layer? {
        return selectedLayer
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
        EYEDROPPER
    }

    private var currentMode = Mode.NONE

    var onColorPickedListener: ((Int) -> Unit)? = null

    fun setEyedropperMode(enabled: Boolean) {
        currentMode = if (enabled) Mode.EYEDROPPER else Mode.NONE
        invalidate()
    }

    private fun getPixelColor(x: Float, y: Float): Int {
        if (x < 0 || x >= canvasWidth || y < 0 || y >= canvasHeight) return Color.WHITE
        // This is expensive, but simple for now.
        // Ideally we cache the bitmap or render a small patch.
        // Given constraints, I'll render the whole bitmap.
        // Optimization: render only 1x1 pixel?
        // But layers might be complex.
        // Let's rely on renderToBitmap which is already implemented but heavy.
        val bmp = renderToBitmap()
        val pixel = bmp.getPixel(x.toInt(), y.toInt())
        // bmp.recycle() // renderToBitmap returns a new bitmap, so recycle it?
        // renderToBitmap createBitmap. Yes.
        return pixel
    }

    // Interaction State
    private var lastTouchX = 0f
    private var lastTouchY = 0f
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

        // Draw Selection Overlay
        if (currentMode != Mode.EYEDROPPER) {
             selectedLayer?.let { drawSelectionOverlay(canvas, it) }
        }

        canvas.restore()

        // Draw Eyedropper UI (Overlay on top of everything, unaffected by zoom/pan of canvas?)
        // The Canvas is currently in Screen Space (after restore).

        if (currentMode == Mode.EYEDROPPER) {
             // 1. Crosshair (In Screen Space, follows finger directly)
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
             // Canvas is already in Screen Space (identity), so we don't need setMatrix(null) unless we messed it up.
             // But we should be safe.

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

             // Transform to center the eyedropper point in the box and scale up
             val zoomLevel = 4f
             // We want `eyedropperX, eyedropperY` to map to `boxRect.centerX(), boxRect.centerY()`
             // And scale applied.
             // Final Coord = (WorldCoord * Scale * Zoom) + Translate

             // Matrix logic:
             // 1. Translate world so eyedropper is at 0,0: T(-ex, -ey)
             // 2. Scale up: S(zoom)
             // 3. Translate to box center: T(bx, by)
             // 4. Apply ViewMatrix (since we are drawing the scene which assumes viewMatrix? No, drawScene draws raw layers)
             // Wait, drawScene draws layers at their world coords.
             // So we just need to transform World -> Box.

             canvas.translate(boxRect.centerX(), boxRect.centerY())
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

    private fun getScale(): Float {
        val values = FloatArray(9)
        viewMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun drawSelectionOverlay(canvas: Canvas, layer: Layer) {
        canvas.save()
        canvas.translate(layer.x, layer.y)
        canvas.rotate(layer.rotation)
        canvas.scale(layer.scaleX, layer.scaleY)

        val halfW = layer.getWidth() / 2f
        val halfH = layer.getHeight() / 2f

        // Box
        paint.style = Paint.Style.STROKE
        paint.color = Color.BLUE
        paint.strokeWidth = 3f / ((layer.scaleX + layer.scaleY)/2f) // Keep stroke constant width visually
        val box = RectF(-halfW - 10, -halfH - 10, halfW + 10, halfH + 10)
        canvas.drawRect(box, paint)

        // --- Draw Handles ---
        // Helper to draw handle circle
        fun drawHandle(x: Float, y: Float, color: Int) {
            handlePaint.color = color
            // Draw handle
            // We need to invert the scale for handles so they stay consistent size
            val size = HANDLE_RADIUS / ((layer.scaleX + layer.scaleY)/2f)
            canvas.drawCircle(x, y, size, handlePaint)
            canvas.drawCircle(x, y, size, strokePaint)
        }

        // 1. Delete Handle (Top-Left) -> X
        drawHandle(-halfW - HANDLE_OFFSET, -halfH - HANDLE_OFFSET, Color.RED)
        // Draw X Icon
        val xSize = 15f / ((layer.scaleX + layer.scaleY)/2f)
        val cx = -halfW - HANDLE_OFFSET
        val cy = -halfH - HANDLE_OFFSET
        canvas.drawLine(cx - xSize, cy - xSize, cx + xSize, cy + xSize, iconPaint)
        canvas.drawLine(cx + xSize, cy - xSize, cx - xSize, cy + xSize, iconPaint)

        // 2. Rotate Handle (Top-Right) -> Circle Arrow (Simplified)
        drawHandle(halfW + HANDLE_OFFSET, -halfH - HANDLE_OFFSET, Color.GREEN)
        // Icon (small circle arc?)
        canvas.drawCircle(halfW + HANDLE_OFFSET, -halfH - HANDLE_OFFSET, 10f / ((layer.scaleX + layer.scaleY)/2f), iconPaint)

        // 3. Resize Handle (Bottom-Right) -> Diagonal Arrow
        drawHandle(halfW + HANDLE_OFFSET, halfH + HANDLE_OFFSET, Color.BLUE)

        // 4. Stretch Horizontal (Left-Middle) -> Horizontal Arrow
        drawHandle(-halfW - HANDLE_OFFSET, 0f, Color.DKGRAY)
        // Icon <->
        val sx = -halfW - HANDLE_OFFSET
        val sSize = 15f / ((layer.scaleX + layer.scaleY)/2f)
        canvas.drawLine(sx - sSize, 0f, sx + sSize, 0f, iconPaint)

        // 5. Stretch Vertical (Bottom-Middle) -> Vertical Arrow
        drawHandle(0f, halfH + HANDLE_OFFSET, Color.DKGRAY)
        // Icon ^
        //      v
        val sy = halfH + HANDLE_OFFSET
        canvas.drawLine(0f, sy - sSize, 0f, sy + sSize, iconPaint)

        // 6. Box Width (Right-Middle) -> Rect icon (Resize box)
        if (layer is TextLayer) {
             drawHandle(halfW + HANDLE_OFFSET, 0f, Color.MAGENTA)
             val bx = halfW + HANDLE_OFFSET
             canvas.drawRect(bx - 10, -10f, bx + 10, 10f, iconPaint)
        }

        canvas.restore()
    }

    // Helper to get distance between two points
    private fun getDistance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    // Helper to get angle
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

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentMode = Mode.NONE

                // 1. Check Handles (if layer selected)
                if (selectedLayer != null) {
                    val layer = selectedLayer!!
                    val layerCenter = floatArrayOf(layer.x, layer.y)

                    // Transform touch to local layer space
                    val localPoint = floatArrayOf(cx, cy)
                    val globalToLocal = Matrix()
                    globalToLocal.postTranslate(-layer.x, -layer.y)
                    globalToLocal.postRotate(-layer.rotation)
                    globalToLocal.postScale(1/layer.scaleX, 1/layer.scaleY)
                    globalToLocal.mapPoints(localPoint)

                    val lx = localPoint[0]
                    val ly = localPoint[1]
                    val halfW = layer.getWidth() / 2f
                    val halfH = layer.getHeight() / 2f

                    // Adjust radius check based on scale to make it easier to hit
                    val hitRadius = HANDLE_RADIUS * 1.5f

                    // Delete Handle (Top-Left)
                    if (getDistance(lx, ly, -halfW - HANDLE_OFFSET, -halfH - HANDLE_OFFSET) <= hitRadius) {
                        deleteSelectedLayer()
                        return true
                    }

                    // Rotate Handle (Top-Right)
                    if (getDistance(lx, ly, halfW + HANDLE_OFFSET, -halfH - HANDLE_OFFSET) <= hitRadius) {
                        currentMode = Mode.ROTATE_LAYER
                        initialRotation = layer.rotation
                        centerX = layer.x
                        centerY = layer.y
                        startAngle = getAngle(centerX, centerY, cx, cy)
                        return true
                    }

                    // Resize Handle (Bottom-Right)
                    if (getDistance(lx, ly, halfW + HANDLE_OFFSET, halfH + HANDLE_OFFSET) <= hitRadius) {
                        currentMode = Mode.RESIZE_LAYER
                        initialScaleX = layer.scaleX
                        initialScaleY = layer.scaleY
                        centerX = layer.x
                        centerY = layer.y
                        startDist = getDistance(centerX, centerY, cx, cy)
                        return true
                    }

                    // Stretch Horizontal (Left-Middle)
                    if (getDistance(lx, ly, -halfW - HANDLE_OFFSET, 0f) <= hitRadius) {
                        currentMode = Mode.STRETCH_H
                        initialScaleX = layer.scaleX
                        centerX = layer.x
                        centerY = layer.y
                        startX = lx // Relative to center
                        return true
                    }

                    // Stretch Vertical (Bottom-Middle)
                    if (getDistance(lx, ly, 0f, halfH + HANDLE_OFFSET) <= hitRadius) {
                        currentMode = Mode.STRETCH_V
                        initialScaleY = layer.scaleY
                        centerX = layer.x
                        centerY = layer.y
                        startY = ly
                        return true
                    }

                    // Box Width (Right-Middle)
                    if (layer is TextLayer && getDistance(lx, ly, halfW + HANDLE_OFFSET, 0f) <= hitRadius) {
                        currentMode = Mode.BOX_WIDTH
                        initialBoxWidth = layer.getWidth()
                        centerX = layer.x
                        centerY = layer.y
                        startDist = lx // use lx as distance from center
                        return true
                    }
                }

                // 2. Check for layer hit (Selection / Move)
                val hitLayer = layers.findLast { it.contains(cx, cy) }
                if (hitLayer != null) {
                    selectLayer(hitLayer)
                    currentMode = Mode.DRAG_LAYER
                    lastTouchX = cx
                    lastTouchY = cy
                    invalidate()
                } else {
                    // Click on empty space -> Deselect? Or just do nothing?
                    // User requested "Jangan biarkan kanvas bisa digeser dengan satu jari"
                    // So if we touch background with 1 finger, we do nothing (or deselect).
                    // selectLayer(null) // Removed to persist selection/menu
                    currentMode = Mode.NONE
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (currentMode == Mode.NONE || currentMode == Mode.PAN_ZOOM) return true

                if (selectedLayer != null) {
                    val layer = selectedLayer!!

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
                            // Complex math to project current touch point onto the local X axis
                            // Simplified: Just use distance change from center logic, but directional?

                            // Re-calculate local X
                            val localPoint = floatArrayOf(cx, cy)
                            val globalToLocal = Matrix()
                            globalToLocal.postTranslate(-layer.x, -layer.y)
                            globalToLocal.postRotate(-layer.rotation)
                            globalToLocal.postScale(1/initialScaleX, 1/initialScaleY) // Use initial scale to see relative movement?
                            // No, simpler: get distance from center.

                            // Let's use distance from center to finger.
                            // If finger is to the left of center, distance increases as we go left.
                            // Handle is at Left Middle (-Width/2).

                            val dist = getDistance(centerX, centerY, cx, cy)
                            // We need to know if we are pulling away or pushing in.
                            // Original handle distance (approx) was halfW * scaleX.

                            // Let's project touch onto the layer's X-axis vector
                            val rad = Math.toRadians(layer.rotation.toDouble())
                            val cos = Math.cos(rad)
                            val sin = Math.sin(rad)
                            // Vector from center to touch
                            val dx = cx - centerX
                            val dy = cy - centerY

                            // Project onto local X axis (rotated)
                            // Local X axis vector is (cos, sin)
                            // But handle is on the LEFT, so vector is (-cos, -sin)
                            val proj = -(dx * cos + dy * sin)

                            // Original projection (approx half width * scale)
                            val originalW = layer.getWidth() / 2f

                            if (originalW > 0) {
                                // New scale is proportional to how far out we are relative to unscaled width
                                // But wait, Layer.getWidth() returns width based on text content.
                                // We are changing ScaleX.

                                // initialScaleX * (currentProj / startProj)
                                // But getting startProj is tricky if we didn't save it.

                                // Let's try simpler:
                                // ScaleX = Proj / (Width_Unscaled/2)
                                val newScale = proj / (layer.getWidth() / 2f / layer.scaleX) // layer.getWidth() uses current scale? No, Layer.getWidth() logic in TextLayer ensures layout which might depend on boxWidth but not scale.
                                // TextLayer.getWidth() returns layout width (unscaled).

                                if (proj > 10) { // Minimum threshold
                                     layer.scaleX = (proj / (layer.getWidth() / 2f)).toFloat().coerceAtLeast(0.1f)
                                     invalidate()
                                }
                            }
                        }
                        Mode.STRETCH_V -> {
                             // Handle at Bottom Middle (+Height/2)
                             // Vector is (sin, -cos) for Y? No.
                             // Local Y axis is (-sin, cos) relative to screen X,Y?
                             // Canvas Y is down.
                             // Rotation is clockwise.
                             // 0 deg: Y axis is (0, 1).

                             val rad = Math.toRadians(layer.rotation.toDouble())
                             val cos = Math.cos(rad)
                             val sin = Math.sin(rad)

                             val dx = cx - centerX
                             val dy = cy - centerY

                             // Project onto local Y axis
                             // Local Y vector (rotated) is (-sin, cos)
                             val proj = -dx * sin + dy * cos

                             if (proj > 10) {
                                 layer.scaleY = (proj / (layer.getHeight() / 2f)).toFloat().coerceAtLeast(0.1f)
                                 invalidate()
                             }
                        }
                        Mode.BOX_WIDTH -> {
                            if (layer is TextLayer) {
                                // Right Middle Handle (+Width/2)
                                // Project onto X axis
                                val rad = Math.toRadians(layer.rotation.toDouble())
                                val cos = Math.cos(rad)
                                val sin = Math.sin(rad)
                                val dx = cx - centerX
                                val dy = cy - centerY
                                val proj = dx * cos + dy * sin

                                // Proj is distance from center in local scaled space.
                                // We want to change the underlying boxWidth (unscaled).
                                // proj = (boxWidth / 2) * scaleX
                                // boxWidth = (proj / scaleX) * 2

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
            // Keep double tap for reset/center logic on empty space
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

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val touchPoint = floatArrayOf(e.x, e.y)
            val inverse = Matrix()
            viewMatrix.invert(inverse)
            inverse.mapPoints(touchPoint)
            val cx = touchPoint[0]
            val cy = touchPoint[1]

            val hitLayer = layers.findLast { it.contains(cx, cy) }
            if (hitLayer != null && hitLayer == selectedLayer) {
                // Trigger edit mode on single tap of selected layer
                onLayerEditListener?.onLayerDoubleTap(hitLayer)
                return true
            }
            return false
        }
    }
}
