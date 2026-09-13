package com.astral.typer.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
        PAN_ZOOM,
        VECTOR_EDIT
    }

    var currentMode: Mode = Mode.VECTOR_EDIT
        set(value) {
            field = value
            invalidate()
        }

    val sfxLayer = TextLayer("BOOM!", Color.parseColor("#FF1744")).apply {
        fontSize = 100f
        strokeColor = Color.parseColor("#111111")
        strokeWidth = 14f
        doubleStrokeColor = Color.parseColor("#FFD600")
        doubleStrokeWidth = 28f
        tripleStrokeColor = Color.parseColor("#FFFFFF")
        tripleStrokeWidth = 38f
        isWarp = true
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
    private var panX = 0f
    private var panY = 0f
    private var scaleFactor = 1.0f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDraggingPan = false

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

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (currentMode == Mode.PAN_ZOOM) {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(0.2f, 5.0f)
                invalidate()
                return true
            }
            return false
        }
    })

    init {
        ensureMeshForChar(0)
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
        sfxLayer.morphedCharBmpCache.remove(selectedCharIndex)
        invalidate()
    }

    fun resetSelectedCharMesh() {
        val textStr = sfxLayer.text.toString()
        if (selectedCharIndex < 0 || selectedCharIndex >= textStr.length) return
        sfxLayer.initWarpMeshForTarget(selectedCharIndex, 2, 2, forceReset = true)
        sfxLayer.morphedCharBmpCache.remove(selectedCharIndex)
        invalidate()
    }

    fun resetAllMeshes() {
        sfxLayer.letterWarpMeshes.clear()
        sfxLayer.letterWarpRows.clear()
        sfxLayer.letterWarpCols.clear()
        sfxLayer.morphedCharBmpCache.clear()
        val textStr = sfxLayer.text.toString()
        for (i in textStr.indices) {
            if (!textStr[i].isWhitespace()) {
                sfxLayer.initWarpMeshForTarget(i, 2, 2, forceReset = true)
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f

        canvas.save()
        canvas.translate(cx + panX, cy + panY)
        canvas.scale(scaleFactor, scaleFactor)

        // Render SFX Layer
        sfxLayer.draw(canvas)

        // Draw Vector Control Points in VECTOR_EDIT Mode
        if (currentMode == Mode.VECTOR_EDIT) {
            drawVectorHandles(canvas)
        }

        canvas.restore()
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
        val count = (rows + 1) * (cols + 1)
        val handleRadius = 16f / scaleFactor.coerceAtLeast(0.5f)
        val handleBorderRadius = 20f / scaleFactor.coerceAtLeast(0.5f)

        for (i in 0 until count) {
            val px = mesh[i * 2]
            val py = mesh[i * 2 + 1]

            canvas.drawCircle(px, py, handleBorderRadius, handleBorderPaint)

            val pointPaint = if (i == selectedPointIndex) selectedHandlePaint else handlePointPaint
            canvas.drawCircle(px, py, handleRadius, pointPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)

        val touchX = event.x
        val touchY = event.y

        val cx = width / 2f
        val cy = height / 2f

        // Convert View touch (touchX, touchY) into Canvas Layer Space
        val layerX = (touchX - cx - panX) / scaleFactor
        val layerY = (touchY - cy - panY) / scaleFactor

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = touchX
                lastTouchY = touchY

                if (currentMode == Mode.VECTOR_EDIT) {
                    val hitIndex = findControlPointAt(layerX, layerY)
                    if (hitIndex != -1) {
                        selectedPointIndex = hitIndex
                        invalidate()
                        return true
                    } else {
                        // Check if touching another letter to change active selection
                        val charIndexHit = findCharIndexAt(layerX, layerY)
                        if (charIndexHit != -1 && charIndexHit != selectedCharIndex) {
                            selectedCharIndex = charIndexHit
                            invalidate()
                            return true
                        }
                    }
                } else if (currentMode == Mode.PAN_ZOOM) {
                    isDraggingPan = true
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = touchX - lastTouchX
                val dy = touchY - lastTouchY

                if (currentMode == Mode.VECTOR_EDIT && selectedPointIndex != -1) {
                    val mesh = sfxLayer.letterWarpMeshes[selectedCharIndex]
                    if (mesh != null) {
                        mesh[selectedPointIndex * 2] = layerX
                        mesh[selectedPointIndex * 2 + 1] = layerY
                        sfxLayer.morphedCharBmpCache.remove(selectedCharIndex)
                        invalidate()
                    }
                } else if (currentMode == Mode.PAN_ZOOM && isDraggingPan) {
                    panX += dx
                    panY += dy
                    invalidate()
                }

                lastTouchX = touchX
                lastTouchY = touchY
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selectedPointIndex = -1
                isDraggingPan = false
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

        val maxHitDist = (40f / scaleFactor).coerceAtLeast(20f)

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

    private fun findCharIndexAt(lx: Float, ly: Float): Int {
        val textStr = sfxLayer.text.toString()
        for (i in textStr.indices) {
            if (textStr[i].isWhitespace()) continue
            val bounds = sfxLayer.getWarpTargetBounds(i)
            bounds.inset(-20f, -20f)
            if (bounds.contains(lx, ly)) {
                return i
            }
        }
        return -1
    }
}
