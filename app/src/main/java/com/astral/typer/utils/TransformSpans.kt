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

        // Ensure bounds cover the entire circle + text height
        if (fm != null) {
            fm.ascent = (-radius - textHeight).toInt()
            fm.top = (-radius - textHeight).toInt()
            fm.descent = (radius + textHeight).toInt()
            fm.bottom = (radius + textHeight).toInt()
        }
        return (radius * 2 + textHeight * 2).toInt()
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

        val originalFm = Paint.FontMetricsInt()
        paint.getFontMetricsInt(originalFm)
        val textHeight = originalFm.descent - originalFm.ascent

        // Draw circle path starting from bottom and going counter-clockwise (so text is upright at top)
        val cx = x + radius + textHeight
        // Calculate vertical center relative to y baseline to maintain symmetry
        val cy = top + (bottom - top) / 2f

        path.addCircle(cx, cy, radius, Path.Direction.CW)

        val originalTextScaleX = paint.textScaleX
        var textWidth = paint.measureText(str)
        val circumference = 2 * Math.PI * radius

        // If the text is wider than the circumference, squish it horizontally to fit perfectly
        if (textWidth > circumference) {
            paint.textScaleX = originalTextScaleX * (circumference.toFloat() / textWidth)
            textWidth = paint.measureText(str)
        }

        // We draw text along this circular path
        // Adjust hOffset to center the text at the top
        // The text naturally starts drawing at the start of the path (which is the rightmost point for addCircle in Android).
        // For Path.Direction.CW, it goes clockwise.
        // We want the text centered at the top (which is -90 degrees from the start).
        // 1/4 of the circumference brings us to the bottom, 3/4 brings us to the top.
        val hOffset = (circumference * 0.75 - textWidth / 2).toFloat()

        canvas.drawTextOnPath(str, path, hOffset, 0f, paint)

        // Restore paint state
        paint.textScaleX = originalTextScaleX
    }
}
