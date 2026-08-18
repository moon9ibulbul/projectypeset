package com.astral.typer.utils

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan

class PositionShiftSpan(var shiftX: Float, var shiftY: Float) : ReplacementSpan() {

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
            // Expand vertical bounds based on shiftY
            fm.top = Math.min(fm.top, originalFm.top + shiftY.toInt())
            fm.ascent = Math.min(fm.ascent, originalFm.ascent + shiftY.toInt())
            fm.descent = Math.max(fm.descent, originalFm.descent + shiftY.toInt())
            fm.bottom = Math.max(fm.bottom, originalFm.bottom + shiftY.toInt())
        }
        val width = paint.measureText(text, start, end)
        // Return original width without expanding layout bounds to prevent shifting adjacent letters
        return width.toInt()
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
        canvas.drawText(text!!, start, end, x + shiftX, y.toFloat() + shiftY, paint)
    }
}
