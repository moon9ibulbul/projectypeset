package com.astral.typer.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.astral.typer.models.TextLayer
import kotlin.math.hypot

class SfxCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode {
        VECTOR_EDIT,
        MOVE_ROTATE
    }

    var currentMode: Mode = Mode.VECTOR_EDIT
        set(value) {
            field = value
            invalidate()
        }

    val canvasWidth = 1080
    val canvasHeight = 1080

    val sfxLayer = TextLayer("BOOM!", Color.BLACK).apply {
        fontSize = 110f
        strokeWidth = 0f
        doubleStrokeWidth = 0f
        tripleStrokeWidth = 0f
        isWarp = true
        x = 0f
        y = 0f
    }

    var selectedCharIndex: Int = 0
        set(value) {
            val textStr = sfxLayer.text.toString()
            field = value.coerceIn(0, (textStr.length - 1).coerceAtLeast(0))
            ensureMeshForChar(field)
            invalidate()
        }

    private var selectedPointIndex: Int = -1

    // Canvas View Transformations
    private val viewMatrix = Matrix()
    private val invertedMatrix = Matrix()
    private var isMatrixInitialized = false

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isPanningCanvas = false

    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var hasLastFocus = false

    private var isDraggingMove = false
    private var isDraggingRotate = false
    private var lastLayerX = 0f
    private var lastLayerY = 0f
    private var lastAngle = 0.0

    // Canvas Paints
    private val canvasBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40000000")
        style = Paint.Style.FILL
    }

    // Rendering Paints
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val handlePointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val selectedHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD600")
        style = Paint.Style.FILL
    }

    private val moveBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val rotateKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD600")
        style = Paint.Style.FILL
    }

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val focusX = detector.focusX
            val focusY = detector.focusY

            viewMatrix.postScale(scaleFactor, scaleFactor, focusX, focusY)

            if (hasLastFocus) {
                val focusDx = focusX - lastFocusX
                val focusDy = focusY - lastFocusY
                viewMatrix.postTranslate(focusDx, focusDy)
            }

            lastFocusX = focusX
            lastFocusY = focusY
            hasLastFocus = true

            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            hasLastFocus = false
        }
    })

    init {
        initAllCharMeshes()
    }

    private fun initMatrixIfNeeded() {
        if (!isMatrixInitialized && width > 0 && height > 0) {
            val viewW = width.toFloat()
            val viewH = height.toFloat()
            val baseScale = minOf(viewW / canvasWidth, viewH / canvasHeight) * 0.85f
            val cx = viewW / 2f
            val cy = viewH / 2f

            viewMatrix.reset()
            viewMatrix.postScale(baseScale, baseScale)
            viewMatrix.postTranslate(cx, cy)
            isMatrixInitialized = true
        }
    }

    fun initAllCharMeshes() {
        val textStr = sfxLayer.text.toString()
        for (i in textStr.indices) {
            if (!textStr[i].isWhitespace()) {
                if (sfxLayer.letterWarpMeshes[i] == null) {
                    sfxLayer.initWarpMeshForTarget(i, 2, 2, forceReset = true)
                }
            }
        }
    }

    fun ensureMeshForChar(charIdx: Int) {
        val textStr = sfxLayer.text.toString()
        if (charIdx < 0 || charIdx >= textStr.length) return
        if (sfxLayer.letterWarpMeshes[charIdx] == null) {
            sfxLayer.initWarpMeshForTarget(charIdx, 2, 2, forceReset = true)
        }
    }

    fun subdivideSelectedCharMesh() {
        val textStr = sfxLayer.text.toString()
        if (selectedCharIndex < 0 || selectedCharIndex >= textStr.length) return
        val curRows = sfxLayer.letterWarpRows[selectedCharIndex] ?: 2
        val curCols = sfxLayer.letterWarpCols[selectedCharIndex] ?: 2
        val newRows = (curRows + 1).coerceAtMost(5)
        val newCols = (curCols + 1).coerceAtMost(5)

        sfxLayer.initWarpMeshForTarget(selectedCharIndex, newRows, newCols, forceReset = false)
        sfxLayer.morphedCharBmpCache.remove(selectedCharIndex)?.recycle()
        sfxLayer.morphedCharBmpHash.remove(selectedCharIndex)
        sfxLayer.morphedBmpCache?.recycle()
        sfxLayer.morphedBmpCache = null
        sfxLayer.morphedBmpHash = 0
        invalidate()
    }

    fun reduceSelectedCharMesh() {
        val textStr = sfxLayer.text.toString()
        if (selectedCharIndex < 0 || selectedCharIndex >= textStr.length) return
        val curRows = sfxLayer.letterWarpRows[selectedCharIndex] ?: 2
        val curCols = sfxLayer.letterWarpCols[selectedCharIndex] ?: 2
        val newRows = (curRows - 1).coerceAtLeast(1)
        val newCols = (curCols - 1).coerceAtLeast(1)

        sfxLayer.initWarpMeshForTarget(selectedCharIndex, newRows, newCols, forceReset = false)
        sfxLayer.morphedCharBmpCache.remove(selectedCharIndex)?.recycle()
        sfxLayer.morphedCharBmpHash.remove(selectedCharIndex)
        sfxLayer.morphedBmpCache?.recycle()
        sfxLayer.morphedBmpCache = null
        sfxLayer.morphedBmpHash = 0
        invalidate()
    }

    fun resetSelectedCharMesh() {
        val textStr = sfxLayer.text.toString()
        if (selectedCharIndex < 0 || selectedCharIndex >= textStr.length) return
        sfxLayer.initWarpMeshForTarget(selectedCharIndex, 2, 2, forceReset = true)
        sfxLayer.morphedCharBmpCache.remove(selectedCharIndex)?.recycle()
        sfxLayer.morphedCharBmpHash.remove(selectedCharIndex)
        sfxLayer.morphedBmpCache?.recycle()
        sfxLayer.morphedBmpCache = null
        sfxLayer.morphedBmpHash = 0
        invalidate()
    }

    fun resetAllMeshes() {
        sfxLayer.letterWarpMeshes.clear()
        sfxLayer.letterWarpRows.clear()
        sfxLayer.letterWarpCols.clear()
        sfxLayer.recycleMorphedCaches()
        val textStr = sfxLayer.text.toString()
        for (i in textStr.indices) {
            if (!textStr[i].isWhitespace()) {
                sfxLayer.initWarpMeshForTarget(i, 2, 2, forceReset = true)
            }
        }
        invalidate()
    }

    private fun getCalculatedTotalScale(): Float {
        val values = FloatArray(9)
        viewMatrix.getValues(values)
        val scaleX = values[Matrix.MSCALE_X]
        val skewY = values[Matrix.MSKEW_Y]
        return kotlin.math.sqrt(scaleX * scaleX + skewY * skewY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        initMatrixIfNeeded()

        val totalScale = getCalculatedTotalScale()

        canvas.save()
        canvas.concat(viewMatrix)

        // Draw Canvas Board (1080x1080) with Shadow centered at (0, 0)
        canvas.drawRect(-canvasWidth / 2f + 8f, -canvasHeight / 2f + 8f, canvasWidth / 2f + 8f, canvasHeight / 2f + 8f, shadowPaint)
        canvas.drawRect(-canvasWidth / 2f, -canvasHeight / 2f, canvasWidth / 2f, canvasHeight / 2f, canvasBgPaint)

        // Render SFX Layer
        sfxLayer.draw(canvas, skipEffects = false, viewScale = totalScale)

        // Draw Handles in edit modes
        if (currentMode == Mode.VECTOR_EDIT) {
            drawVectorHandles(canvas)
        } else if (currentMode == Mode.MOVE_ROTATE) {
            drawMoveRotateHandles(canvas)
        }

        canvas.restore()
    }

    fun getCharMeshBounds(charIdx: Int): RectF? {
        val mesh = sfxLayer.letterWarpMeshes[charIdx] ?: return null
        if (mesh.isEmpty()) return null
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until mesh.size / 2) {
            val px = mesh[i * 2]
            val py = mesh[i * 2 + 1]
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
        }
        if (minX > maxX || minY > maxY) return null
        return RectF(minX, minY, maxX, maxY)
    }

    private fun drawMoveRotateHandles(canvas: Canvas) {
        val bounds = getCharMeshBounds(selectedCharIndex) ?: sfxLayer.getWarpTargetBounds(selectedCharIndex)
        if (bounds.isEmpty) return

        val effectiveScale = getCalculatedTotalScale()
        val padding = 12f / effectiveScale.coerceAtLeast(0.5f)
        val rect = RectF(bounds.left - padding, bounds.top - padding, bounds.right + padding, bounds.bottom + padding)

        // Draw bounding box
        canvas.drawRect(rect, moveBoxPaint)

        // Draw top rotation stalk and knob
        val topCenterX = rect.centerX()
        val topY = rect.top
        val handleLength = 36f / effectiveScale.coerceAtLeast(0.5f)
        val knobY = topY - handleLength
        val knobRadius = 14f / effectiveScale.coerceAtLeast(0.5f)

        canvas.drawLine(topCenterX, topY, topCenterX, knobY, moveBoxPaint)
        canvas.drawCircle(topCenterX, knobY, knobRadius + 4f / effectiveScale.coerceAtLeast(0.5f), handleBorderPaint)
        canvas.drawCircle(topCenterX, knobY, knobRadius, rotateKnobPaint)
    }

    private fun drawVectorHandles(canvas: Canvas) {
        val mesh = sfxLayer.letterWarpMeshes[selectedCharIndex] ?: return
        val rows = sfxLayer.letterWarpRows[selectedCharIndex] ?: 2
        val cols = sfxLayer.letterWarpCols[selectedCharIndex] ?: 2

        // 1. Draw Mesh Lines connecting grid points
        for (r in 0..rows) {
            for (c in 0..cols) {
                val idx = (r * (cols + 1) + c) * 2
                val px = mesh[idx]
                val py = mesh[idx + 1]

                // Horizontal line to right neighbor
                if (c < cols) {
                    val rightIdx = (r * (cols + 1) + (c + 1)) * 2
                    canvas.drawLine(px, py, mesh[rightIdx], mesh[rightIdx + 1], linePaint)
                }

                // Vertical line to bottom neighbor
                if (r < rows) {
                    val bottomIdx = ((r + 1) * (cols + 1) + c) * 2
                    canvas.drawLine(px, py, mesh[bottomIdx], mesh[bottomIdx + 1], linePaint)
                }
            }
        }

        // 2. Draw Handle Dots
        val pointCount = (rows + 1) * (cols + 1)
        val effectiveScale = getCalculatedTotalScale()
        val handleRadius = 16f / effectiveScale.coerceAtLeast(0.5f)
        val handleBorderRadius = 20f / effectiveScale.coerceAtLeast(0.5f)

        for (i in 0 until pointCount) {
            val px = mesh[i * 2]
            val py = mesh[i * 2 + 1]

            canvas.drawCircle(px, py, handleBorderRadius, handleBorderPaint)

            val pointPaint = if (i == selectedPointIndex) selectedHandlePaint else handlePointPaint
            canvas.drawCircle(px, py, handleRadius, pointPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        initMatrixIfNeeded()

        scaleGestureDetector.onTouchEvent(event)

        val pointerCount = event.pointerCount
        val touchX = event.x
        val touchY = event.y

        if (pointerCount >= 2) {
            selectedPointIndex = -1
            isDraggingMove = false
            isDraggingRotate = false
            isPanningCanvas = true
            lastTouchX = touchX
            lastTouchY = touchY
            return true
        }

        viewMatrix.invert(invertedMatrix)
        val layerPoint = floatArrayOf(touchX, touchY)
        invertedMatrix.mapPoints(layerPoint)
        val layerX = layerPoint[0]
        val layerY = layerPoint[1]

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = touchX
                lastTouchY = touchY
                hasLastFocus = false
                sfxLayer.selectedWarpIndex = selectedCharIndex

                if (currentMode == Mode.VECTOR_EDIT) {
                    val hitIndex = findControlPointAt(layerX, layerY)
                    if (hitIndex != -1) {
                        selectedPointIndex = hitIndex
                        isPanningCanvas = false
                    } else {
                        selectedPointIndex = -1
                        isPanningCanvas = true
                    }
                } else if (currentMode == Mode.MOVE_ROTATE) {
                    val bounds = getCharMeshBounds(selectedCharIndex) ?: sfxLayer.getWarpTargetBounds(selectedCharIndex)
                    val effectiveScale = getCalculatedTotalScale()
                    val padding = 12f / effectiveScale.coerceAtLeast(0.5f)
                    val rect = RectF(bounds.left - padding, bounds.top - padding, bounds.right + padding, bounds.bottom + padding)
                    val topCenterX = rect.centerX()
                    val knobY = rect.top - (36f / effectiveScale.coerceAtLeast(0.5f))
                    val hitDist = hypot((layerX - topCenterX).toDouble(), (layerY - knobY).toDouble()).toFloat()
                    val maxHitDist = (40f / effectiveScale).coerceAtLeast(20f)

                    if (hitDist <= maxHitDist) {
                        isDraggingRotate = true
                        isPanningCanvas = false
                        val centerX = bounds.centerX()
                        val centerY = bounds.centerY()
                        lastAngle = Math.atan2((layerY - centerY).toDouble(), (layerX - centerX).toDouble())
                    } else {
                        val expandedRect = RectF(rect).apply { inset(-20f, -20f) }
                        if (expandedRect.contains(layerX, layerY)) {
                            isDraggingMove = true
                            isPanningCanvas = false
                            lastLayerX = layerX
                            lastLayerY = layerY
                        } else {
                            isPanningCanvas = true
                        }
                    }
                }
                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastTouchX = touchX
                lastTouchY = touchY
                hasLastFocus = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = touchX - lastTouchX
                val dy = touchY - lastTouchY

                if (currentMode == Mode.VECTOR_EDIT && selectedPointIndex != -1) {
                    val mesh = sfxLayer.letterWarpMeshes[selectedCharIndex]
                    if (mesh != null) {
                        mesh[selectedPointIndex * 2] = layerX
                        mesh[selectedPointIndex * 2 + 1] = layerY
                        sfxLayer.morphedCharBmpCache.remove(selectedCharIndex)?.recycle()
                        sfxLayer.morphedCharBmpHash.remove(selectedCharIndex)
                        sfxLayer.morphedBmpCache?.recycle()
                        sfxLayer.morphedBmpCache = null
                        sfxLayer.morphedBmpHash = 0
                        invalidate()
                    }
                } else if (currentMode == Mode.MOVE_ROTATE) {
                    if (isDraggingMove) {
                        val mDx = layerX - lastLayerX
                        val mDy = layerY - lastLayerY
                        ensureMeshForChar(selectedCharIndex)
                        val mesh = sfxLayer.letterWarpMeshes[selectedCharIndex]
                        if (mesh != null) {
                            for (i in 0 until mesh.size / 2) {
                                mesh[i * 2] += mDx
                                mesh[i * 2 + 1] += mDy
                            }
                            sfxLayer.morphedCharBmpCache.remove(selectedCharIndex)?.recycle()
                            sfxLayer.morphedCharBmpHash.remove(selectedCharIndex)
                            sfxLayer.morphedBmpCache?.recycle()
                            sfxLayer.morphedBmpCache = null
                            sfxLayer.morphedBmpHash = 0
                            invalidate()
                        }
                        lastLayerX = layerX
                        lastLayerY = layerY
                    } else if (isDraggingRotate) {
                        val bounds = getCharMeshBounds(selectedCharIndex) ?: sfxLayer.getWarpTargetBounds(selectedCharIndex)
                        val centerX = bounds.centerX()
                        val centerY = bounds.centerY()
                        val currentAngle = Math.atan2((layerY - centerY).toDouble(), (layerX - centerX).toDouble())
                        val dAngle = currentAngle - lastAngle

                        ensureMeshForChar(selectedCharIndex)
                        val mesh = sfxLayer.letterWarpMeshes[selectedCharIndex]
                        if (mesh != null) {
                            val cos = Math.cos(dAngle).toFloat()
                            val sin = Math.sin(dAngle).toFloat()
                            for (i in 0 until mesh.size / 2) {
                                val px = mesh[i * 2] - centerX
                                val py = mesh[i * 2 + 1] - centerY
                                val rx = px * cos - py * sin
                                val ry = px * sin + py * cos
                                mesh[i * 2] = rx + centerX
                                mesh[i * 2 + 1] = ry + centerY
                            }
                            sfxLayer.morphedCharBmpCache.remove(selectedCharIndex)?.recycle()
                            sfxLayer.morphedCharBmpHash.remove(selectedCharIndex)
                            sfxLayer.morphedBmpCache?.recycle()
                            sfxLayer.morphedBmpCache = null
                            sfxLayer.morphedBmpHash = 0
                            invalidate()
                        }
                        lastAngle = currentAngle
                    } else if (isPanningCanvas) {
                        viewMatrix.postTranslate(dx, dy)
                        invalidate()
                    }
                } else if (isPanningCanvas) {
                    viewMatrix.postTranslate(dx, dy)
                    invalidate()
                }

                lastTouchX = touchX
                lastTouchY = touchY
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selectedPointIndex = -1
                isDraggingMove = false
                isDraggingRotate = false
                isPanningCanvas = false
                hasLastFocus = false
                sfxLayer.selectedWarpIndex = -1
                invalidate()
            }
        }

        return true
    }

    private fun findControlPointAt(lx: Float, ly: Float): Int {
        val mesh = sfxLayer.letterWarpMeshes[selectedCharIndex] ?: return -1
        val rows = sfxLayer.letterWarpRows[selectedCharIndex] ?: 2
        val cols = sfxLayer.letterWarpCols[selectedCharIndex] ?: 2
        val count = (rows + 1) * (cols + 1)

        val effectiveScale = getCalculatedTotalScale()
        val maxHitDist = (40f / effectiveScale).coerceAtLeast(20f)

        var closestIndex = -1
        var minDistance = Float.MAX_VALUE

        for (i in 0 until count) {
            val px = mesh[i * 2]
            val py = mesh[i * 2 + 1]
            val dist = hypot((lx - px).toDouble(), (ly - py).toDouble()).toFloat()
            if (dist <= maxHitDist && dist < minDistance) {
                minDistance = dist
                closestIndex = i
            }
        }

        return closestIndex
    }
}
