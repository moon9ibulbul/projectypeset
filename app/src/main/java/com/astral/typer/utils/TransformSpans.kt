package com.astral.typer.utils

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.style.ReplacementSpan
import kotlin.math.cos
import kotlin.math.sin

class BaselineShiftSpan(var shiftY: Float) : ReplacementSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        if (fm != null) {
            val originalFm = Paint.FontMetricsInt()
            paint.getFontMetricsInt(originalFm)
            fm.top = originalFm.top + shiftY.toInt()
            fm.ascent = originalFm.ascent + shiftY.toInt()
            fm.descent = originalFm.descent + shiftY.toInt()
            fm.bottom = originalFm.bottom + shiftY.toInt()
        }
        return paint.measureText(text, start, end).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        canvas.drawText(text!!, start, end, x, y.toFloat() + shiftY, paint)
    }
}

class CircleTextSpan(var radius: Float) : ReplacementSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        val originalFm = Paint.FontMetricsInt()
        paint.getFontMetricsInt(originalFm)
        val textHeight = originalFm.descent - originalFm.ascent

        if (fm != null) {
            fm.ascent = (-radius).toInt() + originalFm.ascent
            fm.top = (-radius).toInt() + originalFm.top
            fm.descent = radius.toInt() + originalFm.descent
            fm.bottom = radius.toInt() + originalFm.bottom
        }
        return (radius * 2 + textHeight).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val str = text?.substring(start, end) ?: return
        val path = Path()

        // Draw circle path starting from bottom and going counter-clockwise (so text is upright at top)
        val cx = x + radius
        val cy = y.toFloat()
        path.addCircle(cx, cy, radius, Path.Direction.CW)

        // We draw text along this circular path
        // Adjust hOffset to center the text at the top
        val textWidth = paint.measureText(str)
        val circumference = 2 * Math.PI * radius
        val hOffset = (circumference / 2 - textWidth / 2).toFloat()

        canvas.drawTextOnPath(str, path, hOffset, 0f, paint)
    }
}
