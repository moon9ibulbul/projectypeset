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
}
