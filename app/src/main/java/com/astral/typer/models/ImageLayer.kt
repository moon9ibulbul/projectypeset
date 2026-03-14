package com.astral.typer.models

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader

class ImageLayer(
    var bitmap: Bitmap,
    var imagePath: String? = null
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

        val w = getWidth()
        val h = getHeight()
        val left = -w / 2f
        val top = -h / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.alpha = if (isOpacityGradient) 255 else opacity

        val mode = when(blendMode) {
             "OVERLAY" -> PorterDuff.Mode.OVERLAY
             "ADD" -> PorterDuff.Mode.ADD
             "MULTIPLY" -> PorterDuff.Mode.MULTIPLY
             "SCREEN" -> PorterDuff.Mode.SCREEN
             "DARKEN" -> PorterDuff.Mode.DARKEN
             "LIGHTEN" -> PorterDuff.Mode.LIGHTEN
             else -> PorterDuff.Mode.SRC_OVER
        }
        if (blendMode != "NORMAL") {
            paint.xfermode = PorterDuffXfermode(mode)
        }

        val saveCount = canvas.saveLayer(null, paint)

        val dest = RectF(left, top, left + w, top + h)
        canvas.drawBitmap(bitmap, null, dest, null)

        if (isOpacityGradient) {
            val maskPaint = Paint()
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            val startColor = (opacityStart shl 24) or 0x000000
            val endColor = (opacityEnd shl 24) or 0x000000
            maskPaint.shader = createGradient(w, h, opacityAngle, startColor, endColor)
            val size = Math.max(w, h) * 2
            canvas.drawRect(-size, -size, size, size, maskPaint)
        }

        canvas.restoreToCount(saveCount)
        canvas.restore()
    }

    private fun createGradient(w: Float, h: Float, angle: Int, startColor: Int, endColor: Int): Shader {
        val cx = w / 2f
        val cy = h / 2f
        val angleRad = Math.toRadians(angle.toDouble())
        val cos = Math.cos(angleRad).toFloat()
        val sin = Math.sin(angleRad).toFloat()

        val corners = listOf(
            Pair(-cx, -cy), Pair(cx, -cy), Pair(-cx, cy), Pair(cx, cy)
        )

        var minP = Float.MAX_VALUE
        var maxP = -Float.MAX_VALUE

        for ((px, py) in corners) {
            val p = px * cos + py * sin
            if (p < minP) minP = p
            if (p > maxP) maxP = p
        }

        val halfLen = (maxP - minP) / 2f
        val x0 = cx - halfLen * cos
        val y0 = cy - halfLen * sin
        val x1 = cx + halfLen * cos
        val y1 = cy + halfLen * sin

        return LinearGradient(x0, y0, x1, y1, startColor, endColor, Shader.TileMode.CLAMP)
    }

    override fun clone(): Layer {
        val newLayer = ImageLayer(bitmap, imagePath)
        newLayer.x = x
        newLayer.y = y
        newLayer.rotation = rotation
        newLayer.scaleX = scaleX
        newLayer.scaleY = scaleY
        newLayer.isVisible = isVisible
        newLayer.isLocked = isLocked
        newLayer.name = name

        newLayer.opacity = opacity
        newLayer.blendMode = blendMode
        newLayer.isOpacityGradient = isOpacityGradient
        newLayer.opacityStart = opacityStart
        newLayer.opacityEnd = opacityEnd
        newLayer.opacityAngle = opacityAngle

        return newLayer
    }
}
