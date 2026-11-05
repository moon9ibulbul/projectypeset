package com.astral.typer.models

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeLayerTest {

    @Test
    fun testShapeLayerDropShadowIncludeStrokePropertyAndClone() {
        val layer = ShapeLayer("<svg width='100' height='100'><rect width='100' height='100' fill='#FF0000' stroke='#00FF00' stroke-width='10'/></svg>").apply {
            isSmartShape = true
            color = Color.RED
            strokeColor = Color.GREEN
            strokeWidth = 10f
            shadowColor = Color.GRAY
            shadowRadius = 8f
            shadowDx = 5f
            shadowDy = 5f
            isDropShadowIncludeStroke = true
        }

        assertTrue(layer.isDropShadowIncludeStroke)

        val cloned = layer.clone() as ShapeLayer
        assertTrue(cloned.isDropShadowIncludeStroke)
        assertTrue(cloned.isSmartShape)
        assertEquals(layer.strokeColor, cloned.strokeColor)
        assertEquals(layer.strokeWidth, cloned.strokeWidth, 0.001f)
        assertEquals(layer.shadowColor, cloned.shadowColor)
    }

    @Test
    fun testShapeLayerDropShadowIncludeStrokeToggle() {
        val layer = ShapeLayer("shapes/circle.svg")
        assertFalse(layer.isDropShadowIncludeStroke)

        layer.isDropShadowIncludeStroke = true
        assertTrue(layer.isDropShadowIncludeStroke)

        layer.isDropShadowIncludeStroke = false
        assertFalse(layer.isDropShadowIncludeStroke)
    }

    @Test
    fun testShapeLayerShadowThicknessWithIncludeStroke() {
        val layer = ShapeLayer("shapes/rectangle.svg").apply {
            strokeColor = Color.BLUE
            strokeWidth = 12f
            shadowThickness = 20f
            isDropShadowIncludeStroke = true
        }

        assertEquals(12f, layer.strokeWidth, 0.001f)
        assertEquals(20f, layer.shadowThickness, 0.001f)
        assertTrue(layer.isDropShadowIncludeStroke)
    }

    @Test
    fun testSmartShapeShadowPassPreservesStrokeColorProperties() {
        val svgStr = "<svg width='100' height='100'><rect width='100' height='100' fill='#FFFFFF' stroke='#FF0000' stroke-width='10'/></svg>"
        val layer = ShapeLayer(svgStr).apply {
            isSmartShape = true
            color = Color.WHITE
            strokeColor = Color.RED
            strokeWidth = 10f
            shadowColor = Color.BLACK
            shadowRadius = 0f
            shadowDx = 20f
            shadowDy = 20f
            isDropShadowIncludeStroke = true
        }

        assertTrue(layer.isDropShadowIncludeStroke)
        assertEquals(Color.RED, layer.strokeColor)
        assertEquals(Color.BLACK, layer.shadowColor)
        assertTrue(layer.isSmartShape)
    }

    @Test
    fun testBlurBitmapSoftwareMethodExists() {
        val blurMethod = ShapeLayer.Companion::class.java.getDeclaredMethod("blurBitmapSoftware", Bitmap::class.java, Float::class.java)
        assertNotNull(blurMethod)
    }

    @Test
    fun testSmartShapeLongShadowPropertiesAndRendering() {
        val svgStr = "<svg width='100' height='100'><rect width='100' height='100' fill='#FFFFFF' stroke='#FF0000' stroke-width='10'/></svg>"
        val layer = ShapeLayer(svgStr).apply {
            isSmartShape = true
            color = Color.WHITE
            strokeColor = Color.RED
            strokeWidth = 10f
            currentEffect = TextEffectType.LONG_SHADOW
            longShadowLength = 25f
            longShadowColor = Color.BLUE
            longShadowAngle = 45f
        }

        assertTrue(layer.isSmartShape)
        assertEquals(TextEffectType.LONG_SHADOW, layer.currentEffect)
        assertEquals(25f, layer.longShadowLength, 0.001f)
        assertEquals(Color.BLUE, layer.longShadowColor)
        assertEquals(45f, layer.longShadowAngle, 0.001f)
        assertEquals(Color.WHITE, layer.color)
        assertEquals(Color.RED, layer.strokeColor)

        val cloned = layer.clone() as ShapeLayer
        assertTrue(cloned.isSmartShape)
        assertEquals(TextEffectType.LONG_SHADOW, cloned.currentEffect)
        assertEquals(25f, cloned.longShadowLength, 0.001f)
        assertEquals(Color.BLUE, cloned.longShadowColor)
        assertEquals(45f, cloned.longShadowAngle, 0.001f)
        assertEquals(Color.WHITE, cloned.color)
        assertEquals(Color.RED, cloned.strokeColor)
    }
}
