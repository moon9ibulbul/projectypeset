package com.astral.typer.utils

object GradationHelper {
    fun getSafePortions(hasMid: Boolean, start: Float, mid: Float, end: Float): Triple<Float, Float, Float> {
        // If they are default position values (0.0f, 0.5f, 1.0f) or raw defaults
        if (start == 0.0f && mid == 0.5f && end == 1.0f) {
            return if (hasMid) {
                Triple(0.33f, 0.33f, 0.34f)
            } else {
                Triple(0.5f, 0.0f, 0.5f)
            }
        }
        // Otherwise, ensure they are valid and normalized
        if (hasMid) {
            val sum = start + mid + end
            if (sum <= 0.01f) {
                return Triple(0.33f, 0.33f, 0.34f)
            }
            return Triple(start / sum, mid / sum, end / sum)
        } else {
            val sum = start + end
            if (sum <= 0.01f) {
                return Triple(0.5f, 0.0f, 0.5f)
            }
            return Triple(start / sum, 0.0f, end / sum)
        }
    }

    /**
     * Applies gradient strength to a color.
     * Strength is 0.0 (Low) to 2.0 (High), with 1.0 being 'As Defined'.
     */
    fun applyStrength(color: Int, strength: Float, avgColor: Int): Int {
        if (strength == 1.0f) return color

        val a = android.graphics.Color.alpha(color)
        val r = android.graphics.Color.red(color)
        val g = android.graphics.Color.green(color)
        val b = android.graphics.Color.blue(color)

        val avgA = android.graphics.Color.alpha(avgColor)
        val avgR = android.graphics.Color.red(avgColor)
        val avgG = android.graphics.Color.green(avgColor)
        val avgB = android.graphics.Color.blue(avgColor)

        val newR = (avgR + (r - avgR) * strength).coerceIn(0f, 255f).toInt()
        val newG = (avgG + (g - avgG) * strength).coerceIn(0f, 255f).toInt()
        val newB = (avgB + (b - avgB) * strength).coerceIn(0f, 255f).toInt()
        // Keep original alpha

        return android.graphics.Color.argb(a, newR, newG, newB)
    }

    fun getAverageColor(hasMid: Boolean, start: Int, mid: Int, end: Int): Int {
        if (hasMid) {
            val a = (android.graphics.Color.alpha(start) + android.graphics.Color.alpha(mid) + android.graphics.Color.alpha(end)) / 3
            val r = (android.graphics.Color.red(start) + android.graphics.Color.red(mid) + android.graphics.Color.red(end)) / 3
            val g = (android.graphics.Color.green(start) + android.graphics.Color.green(mid) + android.graphics.Color.green(end)) / 3
            val b = (android.graphics.Color.blue(start) + android.graphics.Color.blue(mid) + android.graphics.Color.blue(end)) / 3
            return android.graphics.Color.argb(a, r, g, b)
        } else {
            val a = (android.graphics.Color.alpha(start) + android.graphics.Color.alpha(end)) / 2
            val r = (android.graphics.Color.red(start) + android.graphics.Color.red(end)) / 2
            val g = (android.graphics.Color.green(start) + android.graphics.Color.green(end)) / 2
            val b = (android.graphics.Color.blue(start) + android.graphics.Color.blue(end)) / 2
            return android.graphics.Color.argb(a, r, g, b)
        }
    }
}
