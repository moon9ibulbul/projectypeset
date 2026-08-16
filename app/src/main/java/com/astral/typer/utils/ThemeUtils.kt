package com.astral.typer.utils

import android.content.Context
import android.util.TypedValue

object ThemeUtils {
    fun getColorFromAttr(context: Context, attrId: Int): Int {
        val typedValue = TypedValue()
        val theme = context.theme
        theme.resolveAttribute(attrId, typedValue, true)
        return typedValue.data
    }
}
