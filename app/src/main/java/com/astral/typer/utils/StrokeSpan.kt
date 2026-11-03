package com.astral.typer.utils

import android.graphics.Paint
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance

class StrokeSpan(
    var strokeColor: Int,
    var strokeWidth: Float
) : CharacterStyle(), UpdateAppearance {

    override fun updateDrawState(tp: TextPaint?) {
        if (tp != null && tp.style == Paint.Style.STROKE) {
            val ovCol = overrideColor
            if (ovCol != null) {
                tp.color = ovCol
            } else {
                tp.color = strokeColor
                tp.shader = null
            }
            tp.strokeWidth = strokeWidth + extraWidth
        }
    }

    companion object {
        var overrideColor: Int? = null
        var extraWidth: Float = 0f
    }
}
