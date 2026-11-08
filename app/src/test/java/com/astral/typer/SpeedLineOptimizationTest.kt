package com.astral.typer

import android.graphics.Color
import com.astral.typer.models.ShapeLayer
import com.astral.typer.models.TextEffectType
import com.astral.typer.models.TextLayer
import com.astral.typer.utils.SpeedLineHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedLineOptimizationTest {

    @Test
    fun testSpeedLinePaddingDoesNotInflateLayerBounds() {
        val textLayer = TextLayer("Test Speed Line")
        textLayer.fontSize = 40f
        textLayer.currentEffect = TextEffectType.SPEED_LINE
        textLayer.speedLineWidth = 3000f
        textLayer.speedLineHeight = 3000f
        textLayer.speedLineCount = 100
        textLayer.speedLineThickness = 10f
        textLayer.speedLineLength = 200f

        // Padding should not expand for SPEED_LINE because speed lines are clipped by SRC_ATOP within layer bounds
        val pad = textLayer.calculatePadding()
        // TextLayer base effect pad is 20f (strokeWidth=0, etc.)
        assertEquals(20f, pad, 0.001f)

        val shapeLayer = ShapeLayer("<svg viewBox='0 0 100 100'><circle cx='50' cy='50' r='40'/></svg>")
        shapeLayer.currentEffect = TextEffectType.SPEED_LINE
        shapeLayer.speedLineWidth = 3000f
        shapeLayer.speedLineHeight = 3000f
        shapeLayer.speedLineCount = 100

        val shapePad = shapeLayer.calculatePadding()
        assertEquals(20f, shapePad, 0.001f)
    }

    @Test
    fun testSpeedLinePathCachingAndSingleDrawPath() {
        val textLayer = TextLayer("Speed Line Cache Test")
        textLayer.speedLineType = "RADIAL"
        textLayer.speedLineWidth = 500f
        textLayer.speedLineHeight = 500f
        textLayer.speedLineCount = 20
        textLayer.speedLineAdditional = 3

        val initialHash = SpeedLineHelper.calculateSpeedLineHash(
            w = 200f,
            h = 100f,
            type = textLayer.speedLineType,
            lineWidth = textLayer.speedLineWidth,
            lineHeight = textLayer.speedLineHeight,
            lineCount = textLayer.speedLineCount,
            thickness = textLayer.speedLineThickness,
            length = textLayer.speedLineLength,
            additional = textLayer.speedLineAdditional,
            angle = textLayer.speedLineAngle,
            seed = textLayer.effectSeed
        )
        assertTrue(initialHash != 0)

        val cachedPath = SpeedLineHelper.generateSpeedLinePath(
            w = 200f,
            h = 100f,
            type = textLayer.speedLineType,
            lineWidth = textLayer.speedLineWidth,
            lineHeight = textLayer.speedLineHeight,
            lineCount = textLayer.speedLineCount,
            thickness = textLayer.speedLineThickness,
            length = textLayer.speedLineLength,
            additional = textLayer.speedLineAdditional,
            angle = textLayer.speedLineAngle,
            seed = textLayer.effectSeed
        )
        assertNotNull(cachedPath)

        // Same parameters should yield exact same hash
        val secondHash = SpeedLineHelper.calculateSpeedLineHash(
            w = 200f,
            h = 100f,
            type = textLayer.speedLineType,
            lineWidth = textLayer.speedLineWidth,
            lineHeight = textLayer.speedLineHeight,
            lineCount = textLayer.speedLineCount,
            thickness = textLayer.speedLineThickness,
            length = textLayer.speedLineLength,
            additional = textLayer.speedLineAdditional,
            angle = textLayer.speedLineAngle,
            seed = textLayer.effectSeed
        )
        assertEquals(initialHash, secondHash)

        // Changing parameter should change hash and invalidate cache
        textLayer.speedLineCount = 35
        val changedHash = SpeedLineHelper.calculateSpeedLineHash(
            w = 200f,
            h = 100f,
            type = textLayer.speedLineType,
            lineWidth = textLayer.speedLineWidth,
            lineHeight = textLayer.speedLineHeight,
            lineCount = textLayer.speedLineCount,
            thickness = textLayer.speedLineThickness,
            length = textLayer.speedLineLength,
            additional = textLayer.speedLineAdditional,
            angle = textLayer.speedLineAngle,
            seed = textLayer.effectSeed
        )
        assertTrue(initialHash != changedHash)
    }

    @Test
    fun testSpeedLineLinearPathGeneration() {
        val path = SpeedLineHelper.generateSpeedLinePath(
            w = 400f,
            h = 400f,
            type = "LINEAR",
            lineWidth = 300f,
            lineHeight = 300f,
            lineCount = 15,
            thickness = 4f,
            length = 50f,
            additional = 2,
            angle = 45f,
            seed = 12345L,
            viewScale = 1.0f
        )
        assertNotNull(path)
    }

    @Test
    fun testSpeedLinePropertiesPreservedOnClone() {
        val textLayer = TextLayer("Clone Test")
        textLayer.speedLineType = "LINEAR"
        textLayer.speedLineWidth = 600f
        textLayer.speedLineHeight = 400f
        textLayer.speedLineCount = 42
        textLayer.speedLineThickness = 3.5f
        textLayer.speedLineLength = 80f
        textLayer.speedLineAdditional = 5
        textLayer.speedLineColor = Color.BLUE
        textLayer.speedLineAngle = 30f

        val cloned = textLayer.clone() as TextLayer
        assertEquals("LINEAR", cloned.speedLineType)
        assertEquals(600f, cloned.speedLineWidth, 0.001f)
        assertEquals(400f, cloned.speedLineHeight, 0.001f)
        assertEquals(42, cloned.speedLineCount)
        assertEquals(3.5f, cloned.speedLineThickness, 0.001f)
        assertEquals(80f, cloned.speedLineLength, 0.001f)
        assertEquals(5, cloned.speedLineAdditional)
        assertEquals(Color.BLUE, cloned.speedLineColor)
        assertEquals(30f, cloned.speedLineAngle, 0.001f)
    }
}
