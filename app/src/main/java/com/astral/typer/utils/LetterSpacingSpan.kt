package com.astral.typer.utils

import android.text.TextPaint
import android.text.style.MetricAffectingSpan

class LetterSpacingSpan(val spacing: Float) : MetricAffectingSpan() {
    override fun updateDrawState(ds: TextPaint) {
        ds.letterSpacing = spacing
    }

    override fun updateMeasureState(paint: TextPaint) {
        paint.letterSpacing = spacing
    }
}
