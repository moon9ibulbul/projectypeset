package com.astral.typer.models

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

class ImageLayer(
    var bitmap: Bitmap,
    var imagePath: String? = null // Relative path in the project zip (e.g. "images/layer_1.png")
) : Layer() {

    init {
        name = "Image Layer"
    }

    override fun getWidth(): Float {
        return bitmap.width.toFloat()
    }

    override fun getHeight(): Float {
        return bitmap.height.toFloat()
    }

    override fun draw(canvas: Canvas) {
        if (!isVisible) return

        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rotation)
        canvas.scale(scaleX, scaleY)

        // Draw centered
        val left = -getWidth() / 2f
        val top = -getHeight() / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.alpha = 255 // Add opacity support later if needed to Layer base

        // RectF for drawing
        val dest = RectF(left, top, left + getWidth(), top + getHeight())
        canvas.drawBitmap(bitmap, null, dest, paint)

        canvas.restore()
    }

    override fun clone(): Layer {
        // Bitmap is shared for memory efficiency in clones, or deep copy?
        // Usually deep copy for undo/redo means deep copy properties.
        // Bitmap itself is immutable-ish here, so we can reference same bitmap.
        // But if we allow modifying pixels (eraser), we need copy.
        // For now, simple transform clone.
        val newLayer = ImageLayer(bitmap, imagePath)
        newLayer.x = x
        newLayer.y = y
        newLayer.rotation = rotation
        newLayer.scaleX = scaleX
        newLayer.scaleY = scaleY
        newLayer.isVisible = isVisible
        newLayer.isLocked = isLocked
        newLayer.name = name
        return newLayer
    }
}
