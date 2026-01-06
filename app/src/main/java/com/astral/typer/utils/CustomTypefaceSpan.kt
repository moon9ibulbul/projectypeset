package com.astral.typer.utils

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan

class CustomTypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(ds: TextPaint) {
        applyCustomTypeface(ds, typeface)
    }

    override fun updateMeasureState(paint: TextPaint) {
        applyCustomTypeface(paint, typeface)
    }

    private fun applyCustomTypeface(paint: Paint, tf: Typeface) {
        val oldStyle = paint.typeface?.style ?: 0
        val fake = oldStyle and tf.style.inv()

        if (fake and Typeface.BOLD != 0) {
            paint.isFakeBoldText = true
        }

        if (fake and Typeface.ITALIC != 0) {
            paint.textSkewX = -0.25f
        }

        paint.typeface = tf
    }
}
