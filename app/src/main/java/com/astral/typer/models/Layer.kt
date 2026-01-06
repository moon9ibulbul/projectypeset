package com.astral.typer.models

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF

abstract class Layer {
    var x: Float = 0f
    var y: Float = 0f
    var rotation: Float = 0f
    var scaleX: Float = 1f
    var scaleY: Float = 1f
    var isSelected: Boolean = false

    // Backwards compatibility for uniform scale getter/setter (optional)
    var scale: Float
        get() = (scaleX + scaleY) / 2f
        set(value) {
            scaleX = value
            scaleY = value
        }

    // Bounds in local coordinates (before rotation/scale)
    abstract fun getWidth(): Float
    abstract fun getHeight(): Float

    // Draw the content of the layer
    abstract fun draw(canvas: Canvas)

    // Check if a point (canvas coordinates) hits this layer
    fun contains(px: Float, py: Float): Boolean {
        // Transform point to local layer coordinates
        val tempMatrix = Matrix()
        tempMatrix.setTranslate(x, y)
        tempMatrix.preRotate(rotation)
        tempMatrix.preScale(scaleX, scaleY)

        val inverted = Matrix()
        if (tempMatrix.invert(inverted)) {
            val points = floatArrayOf(px, py)
            inverted.mapPoints(points)
            val localX = points[0]
            val localY = points[1]

            // Assume center origin for simplicity in rotation?
            // Let's standardise: x,y is the Center of the layer.
            val halfW = getWidth() / 2f
            val halfH = getHeight() / 2f

            return localX >= -halfW && localX <= halfW && localY >= -halfH && localY <= halfH
        }
        return false
    }
}
