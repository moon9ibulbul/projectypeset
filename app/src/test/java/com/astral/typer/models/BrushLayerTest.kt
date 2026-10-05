package com.astral.typer.models

import com.astral.typer.utils.GradationHelper
import org.junit.Assert.assertEquals
import org.junit.Test

class BrushLayerTest {

    @Test
    fun testGradationHelperSafePortionsDefault() {
        val portionsNoMid = GradationHelper.getSafePortions(false, 0f, 0.5f, 1f)
        assertEquals(0.5f, portionsNoMid.first, 0.001f)
        assertEquals(0.0f, portionsNoMid.second, 0.001f)
        assertEquals(0.5f, portionsNoMid.third, 0.001f)

        val portionsWithMid = GradationHelper.getSafePortions(true, 0f, 0.5f, 1f)
        assertEquals(0.33f, portionsWithMid.first, 0.001f)
        assertEquals(0.33f, portionsWithMid.second, 0.001f)
        assertEquals(0.34f, portionsWithMid.third, 0.001f)
    }

    @Test
    fun testGradationHelperSafePortionsCustom() {
        val portionsNoMid = GradationHelper.getSafePortions(false, 0.2f, 0.5f, 0.8f)
        assertEquals(0.2f, portionsNoMid.first, 0.001f)
        assertEquals(0.0f, portionsNoMid.second, 0.001f)
        assertEquals(0.8f, portionsNoMid.third, 0.001f)
    }
}
