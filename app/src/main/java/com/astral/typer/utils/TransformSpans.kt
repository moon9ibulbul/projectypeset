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
