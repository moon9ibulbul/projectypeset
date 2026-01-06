package com.astral.typer.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.astral.typer.models.Layer
import com.astral.typer.models.TextLayer

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
    private val debugPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // Layers
    private val layers = mutableListOf<Layer>()
    private var selectedLayer: Layer? = null

    fun getSelectedLayer(): Layer? {
        return selectedLayer
    }

    // Interaction State
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDraggingLayer = false
    private var isRotatingLayer = false
    private var isScalingLayer = false
    private var initialRotation = 0f
    private var initialScale = 1f
    private var initialFingerAngle = 0f
    private var initialFingerDist = 0f

    // Interaction Variables (Temp)
    private var centerX = 0f
    private var centerY = 0f
    private var startAngle = 0f
    private var startDist = 0f

    // Handles Constants
    private val HANDLE_RADIUS = 30f
    private val HANDLE_OFFSET = 40f

    // Camera / Viewport
    private val viewMatrix = Matrix()
    private val invertedMatrix = Matrix() // For mapping touch to canvas coords

    // Gestures
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    // State
    private var isInitialized = false

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
             canvas.drawBitmap(it, null, RectF(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat()), bgPaint)
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
             canvas.drawBitmap(it, null, backgroundRect, paint)
        }

        // Draw Border
        paint.color = Color.LTGRAY
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRect(backgroundRect, paint)

        // Draw Layers
        for (layer in layers) {
            layer.draw(canvas)
        }

        // Draw Selection Overlay
        selectedLayer?.let { drawSelectionOverlay(canvas, it) }

        canvas.restore()
    }

    private fun drawSelectionOverlay(canvas: Canvas, layer: Layer) {
        canvas.save()
        canvas.translate(layer.x, layer.y)
        canvas.rotate(layer.rotation)
        canvas.scale(layer.scale, layer.scale)

        val halfW = layer.getWidth() / 2f
        val halfH = layer.getHeight() / 2f

        // Box
        paint.style = Paint.Style.STROKE
        paint.color = Color.BLUE
        paint.strokeWidth = 3f
        val box = RectF(-halfW - 10, -halfH - 10, halfW + 10, halfH + 10)
        canvas.drawRect(box, paint)

        // Draw Handles
        // Rotate Handle (Top-Right)
        paint.style = Paint.Style.FILL
        paint.color = Color.GREEN
        canvas.drawCircle(halfW + 20, -halfH - 20, 20f, paint)

        // Resize Handle (Bottom-Right)
        paint.style = Paint.Style.FILL
        paint.color = Color.RED
        canvas.drawCircle(halfW + 20, halfH + 20, 20f, paint)

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
        val result = scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
             gestureDetector.onTouchEvent(event)
        }

        // Map touch to canvas coordinates
        val touchPoint = floatArrayOf(event.x, event.y)
        viewMatrix.invert(invertedMatrix)
        invertedMatrix.mapPoints(touchPoint)
        val cx = touchPoint[0]
        val cy = touchPoint[1]

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (selectedLayer != null) {
                    val layer = selectedLayer!!

                    // Transform touch to local layer space to check handles
                    val localPoint = floatArrayOf(cx, cy)
                    val globalToLocal = Matrix()
                    globalToLocal.postTranslate(-layer.x, -layer.y)
                    globalToLocal.postRotate(-layer.rotation)
                    globalToLocal.postScale(1/layer.scale, 1/layer.scale)
                    globalToLocal.mapPoints(localPoint)

                    val lx = localPoint[0]
                    val ly = localPoint[1]
                    val halfW = layer.getWidth() / 2f
                    val halfH = layer.getHeight() / 2f

                    // Check Rotate Handle (Top-Right)
                    // Location: (halfW + 20, -halfH - 20)
                    if (getDistance(lx, ly, halfW + 20, -halfH - 20) <= HANDLE_RADIUS) {
                        isRotatingLayer = true
                        initialRotation = layer.rotation
                        centerX = layer.x
                        centerY = layer.y
                        startAngle = getAngle(centerX, centerY, cx, cy)
                        return true
                    }

                    // Check Resize Handle (Bottom-Right)
                    // Location: (halfW + 20, halfH + 20)
                    if (getDistance(lx, ly, halfW + 20, halfH + 20) <= HANDLE_RADIUS) {
                        isScalingLayer = true
                        initialScale = layer.scale
                        centerX = layer.x
                        centerY = layer.y
                        startDist = getDistance(centerX, centerY, cx, cy)
                        return true
                    }
                }

                // Check for layer hit
                val hitLayer = layers.findLast { it.contains(cx, cy) }
                if (hitLayer != null) {
                    selectLayer(hitLayer)
                    isDraggingLayer = true
                    lastTouchX = cx
                    lastTouchY = cy
                    invalidate()
                } else {
                    selectLayer(null)
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (selectedLayer != null) {
                    if (isRotatingLayer) {
                        val currentAngle = getAngle(centerX, centerY, cx, cy)
                        val angleDiff = currentAngle - startAngle
                        selectedLayer!!.rotation = initialRotation + angleDiff
                        invalidate()
                        return true
                    }
                    if (isScalingLayer) {
                        val currentDist = getDistance(centerX, centerY, cx, cy)
                        if (startDist > 0) {
                             val scaleFactor = currentDist / startDist
                             selectedLayer!!.scale = initialScale * scaleFactor
                             invalidate()
                        }
                        return true
                    }
                }

                if (isDraggingLayer && selectedLayer != null) {
                    val dx = cx - lastTouchX
                    val dy = cy - lastTouchY
                    selectedLayer!!.x += dx
                    selectedLayer!!.y += dy
                    lastTouchX = cx
                    lastTouchY = cy
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                isDraggingLayer = false
                isRotatingLayer = false
                isScalingLayer = false
            }
        }

        return true
    }

    // --- Gesture Handling ---

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Only zoom camera if no layer is being manipulated (todo: distinguish mode)
            if (selectedLayer == null) {
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
            if (!isDraggingLayer) {
                viewMatrix.postTranslate(-distanceX, -distanceY)
                invalidate()
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (selectedLayer != null) {
                 onLayerEditListener?.onLayerDoubleTap(selectedLayer!!)
                 return true
            }
            centerCanvas()
            return true
        }
    }
}
