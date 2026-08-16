cat << 'INNER_EOF' > app/src/main/java/com/astral/typer/ThemeUtilsTest.kt
package com.astral.typer.utils

import android.content.Context
import android.util.TypedValue
import android.graphics.Color

object ThemeUtils {
    fun getColorFromAttr(context: Context, attrId: Int): Int {
        val typedValue = TypedValue()
        val theme = context.theme
        theme.resolveAttribute(attrId, typedValue, true)
        return typedValue.data
    }
}
INNER_EOF
