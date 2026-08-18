import re

with open('app/src/main/java/com/astral/typer/models/TextLayer.kt', 'r') as f:
    content = f.read()

# Problem 1: `isDrawingStrokePass` shouldn't force alpha to 1f or 0f using color matrix, it should preserve it.
content = content.replace("0f, 0f, 0f, 100f, -250f", "0f, 0f, 0f, 1f, 0f")

# Problem 2: Highlight enveloping stroke
# Removing BackgroundColorSpan from `ensureLayout` temporary SpannableStringBuilder:
ensure_layout_str = """
        // Apply Transforms to a temporary SpannableStringBuilder
        val tempText = SpannableStringBuilder(text)
"""
new_ensure_layout_str = """
        // Apply Transforms to a temporary SpannableStringBuilder
        val tempText = SpannableStringBuilder(text)
        // Strip out BackgroundColorSpans because we manually render them in drawMain
        val bgSpans = tempText.getSpans(0, tempText.length, android.text.style.BackgroundColorSpan::class.java)
        for (span in bgSpans) {
            tempText.removeSpan(span)
        }
"""
content = content.replace(ensure_layout_str, new_ensure_layout_str)

draw_main_start = """
        val drawMain = { targetCanvas: Canvas ->
            val originalShader = paint.shader"""
new_draw_main_start = """
        val drawMain = { targetCanvas: Canvas ->
            // Manually draw BackgroundColorSpan highlights (if not stroke/shadow pass)
            if (!isDrawingClippingMask && !isDrawingStrokePass && !isDrawingShadowPass) {
                val bgSpans = text.getSpans(0, text.length, android.text.style.BackgroundColorSpan::class.java)
                if (bgSpans.isNotEmpty()) {
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
                    for (span in bgSpans) {
                        bgPaint.color = span.backgroundColor
                        val start = text.getSpanStart(span)
                        val end = text.getSpanEnd(span)
                        val activeLayout = cachedLayout ?: layout
                        if (activeLayout != null) {
                            for (line in 0 until activeLayout.lineCount) {
                                val lineStart = activeLayout.getLineStart(line)
                                val lineEnd = activeLayout.getLineEnd(line)
                                if (start < lineEnd && end > lineStart) {
                                    val drawStart = Math.max(start, lineStart)
                                    val drawEnd = Math.min(end, lineEnd)
                                    val xStart = activeLayout.getPrimaryHorizontal(drawStart)
                                    val xEnd = activeLayout.getPrimaryHorizontal(drawEnd)
                                    val yTop = activeLayout.getLineTop(line).toFloat()
                                    val yBottom = activeLayout.getLineBottom(line).toFloat()
                                    targetCanvas.drawRect(Math.min(xStart, xEnd), yTop, Math.max(xStart, xEnd), yBottom, bgPaint)
                                }
                            }
                        }
                    }
                }
            }

            val originalShader = paint.shader"""

content = content.replace(draw_main_start, new_draw_main_start)

# Also fix the punch hole alpha logic that might fail if `silhouetteColor` is null
punch_hole_str = """
                val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    isFilterBitmap = true
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
                    val maxAlpha = if (isGradient) 255f else android.graphics.Color.alpha(color).toFloat().coerceAtLeast(1f)"""

new_punch_hole_str = """
                val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    isFilterBitmap = true
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
                    val maxAlpha = if (silhouetteColor != null) {
                        android.graphics.Color.alpha(silhouetteColor!!).toFloat().coerceAtLeast(1f)
                    } else if (isGradient) {
                        255f
                    } else {
                        android.graphics.Color.alpha(color).toFloat().coerceAtLeast(1f)
                    }"""

content = content.replace(punch_hole_str, new_punch_hole_str)

# Also strip char
strip_char = """
                    val copiedSpan = when (span) {
                        is android.text.style.ForegroundColorSpan -> android.text.style.ForegroundColorSpan(span.foregroundColor)
                        is android.text.style.BackgroundColorSpan -> android.text.style.BackgroundColorSpan(span.backgroundColor)
                        is android.text.style.StyleSpan -> android.text.style.StyleSpan(span.style)
"""
new_strip_char = """
                    val copiedSpan = when (span) {
                        is android.text.style.ForegroundColorSpan -> android.text.style.ForegroundColorSpan(span.foregroundColor)
                        // Ignore BackgroundColorSpan so it doesn't cause bounding box wrapping in individual chars during warped strokes
                        is android.text.style.StyleSpan -> android.text.style.StyleSpan(span.style)
"""

content = content.replace(strip_char, new_strip_char)

with open('app/src/main/java/com/astral/typer/models/TextLayer.kt', 'w') as f:
    f.write(content)
