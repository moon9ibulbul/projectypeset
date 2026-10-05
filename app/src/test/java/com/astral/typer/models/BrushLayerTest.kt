package com.astral.typer.models

import com.astral.typer.utils.GradationHelper
import org.junit.Assert.assertEquals
import org.junit.Test

import android.graphics.Color
import android.graphics.RectF
import com.astral.typer.utils.ProjectManager.LayerModel
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

class BrushLayerTest {

    @Test
    fun testBrushLayerCloneGradientAndStylingProperties() {
        val layer = BrushLayer(1080, 1080).apply {
            brushColor = Color.RED
            brushSize = 30f
            brushOpacity = 200

            // Gradient properties
            isGradient = true
            gradientStartColor = Color.RED
            gradientEndColor = Color.BLUE
            gradientAngle = 90
            hasMiddleColor = true
            gradientMiddleColor = Color.GREEN
            gradientStartPos = 0.1f
            gradientMiddlePos = 0.5f
            gradientEndPos = 0.9f
            gradientStrength = 0.8f
            isGradientText = true
            isGradientStroke1 = true
            isGradientStroke2 = false
            isGradientStroke3 = false
            isGradientShadow = true

            // Stroke properties
            strokeColor = Color.BLACK
            strokeWidth = 10f
            doubleStrokeColor = Color.YELLOW
            doubleStrokeWidth = 5f

            // Shadow properties
            shadowColor = Color.GRAY
            shadowRadius = 8f
            shadowDx = 4f
            shadowDy = 4f
            isDropShadowIncludeStroke = true
        }

        val cloned = layer.clone() as BrushLayer

        assertEquals(layer.canvasWidth, cloned.canvasWidth)
        assertEquals(layer.canvasHeight, cloned.canvasHeight)
        assertEquals(layer.brushColor, cloned.brushColor)
        assertEquals(layer.brushSize, cloned.brushSize, 0.001f)
        assertEquals(layer.brushOpacity, cloned.brushOpacity)

        // Verify gradient properties
        assertEquals(true, cloned.isGradient)
        assertEquals(Color.RED, cloned.gradientStartColor)
        assertEquals(Color.BLUE, cloned.gradientEndColor)
        assertEquals(90, cloned.gradientAngle)
        assertEquals(true, cloned.hasMiddleColor)
        assertEquals(Color.GREEN, cloned.gradientMiddleColor)
        assertEquals(0.1f, cloned.gradientStartPos, 0.001f)
        assertEquals(0.5f, cloned.gradientMiddlePos, 0.001f)
        assertEquals(0.9f, cloned.gradientEndPos, 0.001f)
        assertEquals(0.8f, cloned.gradientStrength, 0.001f)
        assertEquals(true, cloned.isGradientText)
        assertEquals(true, cloned.isGradientStroke1)
        assertEquals(false, cloned.isGradientStroke2)
        assertEquals(false, cloned.isGradientStroke3)
        assertEquals(true, cloned.isGradientShadow)

        // Verify stroke properties
        assertEquals(Color.BLACK, cloned.strokeColor)
        assertEquals(10f, cloned.strokeWidth, 0.001f)
        assertEquals(Color.YELLOW, cloned.doubleStrokeColor)
        assertEquals(5f, cloned.doubleStrokeWidth, 0.001f)

        // Verify shadow properties
        assertEquals(Color.GRAY, cloned.shadowColor)
        assertEquals(8f, cloned.shadowRadius, 0.001f)
        assertEquals(4f, cloned.shadowDx, 0.001f)
        assertEquals(4f, cloned.shadowDy, 0.001f)
        assertEquals(true, cloned.isDropShadowIncludeStroke)
    }

    @Test
    fun testProjectManagerBrushLayerModelSerialization() {
        val model = LayerModel(
            type = "BRUSH",
            x = 540f, y = 540f, rotation = 0f, scaleX = 1f, scaleY = 1f,
            isVisible = true, isLocked = false, isClipped = false, name = "Brush Layer 1",
            opacity = 255, blendMode = "NORMAL",
            isOpacityGradient = false, opacityStart = 255, opacityEnd = 0, opacityAngle = 0,
            brushPath = "images/brush_0.png",
            brushName = "pencil",
            brushColor = Color.RED,
            brushSize = 25f,
            brushHardness = 0.5f,
            brushOpacity = 255,
            isGradient = true,
            gradientStartColor = Color.RED,
            gradientEndColor = Color.BLUE,
            gradientAngle = 45,
            hasMiddleColor = true,
            gradientMiddleColor = Color.YELLOW,
            gradientStartPos = 0.0f,
            gradientMiddlePos = 0.5f,
            gradientEndPos = 1.0f,
            gradientStrength = 1.0f,
            isGradientText = true,
            isGradientStroke1 = true,
            isGradientStroke2 = false,
            isGradientStroke3 = false,
            isGradientShadow = false,
            strokeColor = Color.BLACK,
            strokeWidth = 6f,
            shadowColor = Color.DKGRAY,
            shadowRadius = 10f
        )

        val gson = Gson()
        val json = gson.toJson(model)
        val deserialized = gson.fromJson(json, LayerModel::class.java)

        assertNotNull(deserialized)
        assertEquals("BRUSH", deserialized.type)
        assertEquals("pencil", deserialized.brushName)
        assertEquals(Color.RED, deserialized.brushColor)

        // Verify gradient fields
        assertEquals(true, deserialized.isGradient)
        assertEquals(Color.RED, deserialized.gradientStartColor)
        assertEquals(Color.BLUE, deserialized.gradientEndColor)
        assertEquals(45, deserialized.gradientAngle)
        assertEquals(true, deserialized.hasMiddleColor)
        assertEquals(Color.YELLOW, deserialized.gradientMiddleColor)
        assertEquals(true, deserialized.isGradientText)
        assertEquals(true, deserialized.isGradientStroke1)
        assertEquals(false, deserialized.isGradientStroke2)
        assertEquals(false, deserialized.isGradientShadow)

        // Verify stroke and shadow fields
        assertEquals(Color.BLACK, deserialized.strokeColor)
        assertEquals(6f, deserialized.strokeWidth ?: 0f, 0.001f)
        assertEquals(Color.DKGRAY, deserialized.shadowColor)
        assertEquals(10f, deserialized.shadowRadius ?: 0f, 0.001f)
    }

    @Test
    fun testBrushLayerDefaultContentBounds() {
        val layer = BrushLayer(1080, 1080)
        val bounds = layer.getContentBounds()

        // Verify getContentBounds returns a non-null RectF instance for empty layer
        assertNotNull(bounds)
    }

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
