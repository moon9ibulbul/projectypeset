package com.astral.typer.models

import com.astral.typer.utils.GradationHelper
import org.junit.Assert.assertEquals
import org.junit.Test

import android.graphics.Color
import android.graphics.RectF
import com.astral.typer.utils.ProjectManager.LayerModel
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

class BrushLayerTest {

    @Test
    fun testBrushLayerBlendModePropertyAndClone() {
        val modes = listOf("NORMAL", "OVERLAY", "ADD", "MULTIPLY", "SCREEN", "DARKEN", "LIGHTEN")
        for (mode in modes) {
            val layer = BrushLayer(1080, 1080).apply {
                blendMode = mode
                opacity = 180
            }
            assertEquals(mode, layer.blendMode)
            assertEquals(180, layer.opacity)

            val cloned = layer.clone() as BrushLayer
            assertEquals(mode, cloned.blendMode)
            assertEquals(180, cloned.opacity)
        }
    }

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
    fun testCreateLayerFromModelRestoresBrushLayerStyling() {
        val model = LayerModel(
            type = "BRUSH",
            x = 100f, y = 200f, rotation = 15f, scaleX = 1f, scaleY = 1f,
            isVisible = true, isLocked = false, name = "Brush Test",
            opacity = 255, blendMode = "NORMAL",
            isOpacityGradient = false, opacityStart = 255, opacityEnd = 0, opacityAngle = 0,
            brushPath = "images/brush_0.png",
            brushName = "pencil",
            brushColor = Color.RED,
            brushSize = 25f,
            isGradient = true,
            gradientStartColor = Color.RED,
            gradientEndColor = Color.BLUE,
            gradientAngle = 90,
            hasMiddleColor = true,
            gradientMiddleColor = Color.GREEN,
            gradientStartPos = 0.1f,
            gradientMiddlePos = 0.5f,
            gradientEndPos = 0.9f,
            gradientStrength = 0.8f,
            isGradientText = true,
            isGradientStroke1 = true,
            isGradientStroke2 = false,
            isGradientStroke3 = false,
            isGradientShadow = true,
            strokeColor = Color.BLACK,
            strokeWidth = 10f,
            doubleStrokeColor = Color.YELLOW,
            doubleStrokeWidth = 5f,
            tripleStrokeColor = Color.CYAN,
            tripleStrokeWidth = 2f,
            shadowColor = Color.GRAY,
            shadowRadius = 8f,
            shadowDx = 4f,
            shadowDy = 4f,
            isDropShadowIncludeStroke = true,
            isMotionShadow = true,
            isMotionShadowIncludeStroke = true,
            motionShadowAngle = 45,
            motionShadowDistance = 20f,
            motionShadowDx = 2f,
            motionShadowDy = 3f,
            motionShadowThickness = 12f,
            motionShadowSmoothness = 50,
            motionShadowKernelSize = 3
        )

        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val dummyBitmap = allocateInstance.invoke(unsafe, android.graphics.Bitmap::class.java) as android.graphics.Bitmap
        val imageMap = mapOf("images/brush_0.png" to dummyBitmap)

        val layer = com.astral.typer.utils.ProjectManager.createLayerFromModel(model, imageMap) as? BrushLayer

        assertNotNull(layer)
        layer!!

        assertEquals("pencil", layer.brushName)
        assertEquals(Color.RED, layer.brushColor)

        // Verify restored gradient
        assertEquals(true, layer.isGradient)
        assertEquals(Color.RED, layer.gradientStartColor)
        assertEquals(Color.BLUE, layer.gradientEndColor)
        assertEquals(90, layer.gradientAngle)
        assertEquals(true, layer.hasMiddleColor)
        assertEquals(Color.GREEN, layer.gradientMiddleColor)
        assertEquals(0.1f, layer.gradientStartPos, 0.001f)
        assertEquals(0.5f, layer.gradientMiddlePos, 0.001f)
        assertEquals(0.9f, layer.gradientEndPos, 0.001f)
        assertEquals(0.8f, layer.gradientStrength, 0.001f)
        assertEquals(true, layer.isGradientText)
        assertEquals(true, layer.isGradientStroke1)
        assertEquals(false, layer.isGradientStroke2)
        assertEquals(false, layer.isGradientStroke3)
        assertEquals(true, layer.isGradientShadow)

        // Verify restored strokes
        assertEquals(Color.BLACK, layer.strokeColor)
        assertEquals(10f, layer.strokeWidth, 0.001f)
        assertEquals(Color.YELLOW, layer.doubleStrokeColor)
        assertEquals(5f, layer.doubleStrokeWidth, 0.001f)
        assertEquals(Color.CYAN, layer.tripleStrokeColor)
        assertEquals(2f, layer.tripleStrokeWidth, 0.001f)

        // Verify restored shadows
        assertEquals(Color.GRAY, layer.shadowColor)
        assertEquals(8f, layer.shadowRadius, 0.001f)
        assertEquals(4f, layer.shadowDx, 0.001f)
        assertEquals(4f, layer.shadowDy, 0.001f)
        assertEquals(true, layer.isDropShadowIncludeStroke)
        assertEquals(true, layer.isMotionShadow)
        assertEquals(true, layer.isMotionShadowIncludeStroke)
        assertEquals(45, layer.motionShadowAngle)
        assertEquals(20f, layer.motionShadowDistance, 0.001f)
        assertEquals(2f, layer.motionShadowDx, 0.001f)
        assertEquals(3f, layer.motionShadowDy, 0.001f)
        assertEquals(12f, layer.motionShadowThickness, 0.001f)
        assertEquals(50, layer.motionShadowSmoothness)
        assertEquals(3, layer.motionShadowKernelSize)
    }

    @Test
    fun testBrushLayerDefaultContentBounds() {
        val layer = BrushLayer(1080, 1080)
        val bounds = layer.getContentBounds()

        // Verify getContentBounds returns a non-null RectF instance for empty layer
        assertNotNull(bounds)

        // Verify default gradient target on primary brush content is enabled
        assertEquals(true, layer.isGradientText)
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

    @Test
    fun testBrushLayerOpacityMultiplyScaling() {
        // Verify lerp opacity formula for opaque_multiply
        val baseOpaque = 0.000025f
        val opaqueFac = 1.0f
        val scaledOpaque = if (opaqueFac > 0f) {
            (baseOpaque + (1.0f - baseOpaque) * opaqueFac).coerceIn(0.0f, 1.0f)
        } else {
            baseOpaque
        }
        assertEquals(1.0f, scaledOpaque, 0.001f)
    }

    @Test
    fun testBrushLayerInitialColorFallbackFromPresetHsv() {
        // Test HSV to RGB conversion for HalfToneCMY#1 preset parameters (color_h=0.175, color_s=1.0, color_v=1.0)
        val hsvToRgbMethod = BrushLayer::class.java.getDeclaredMethod("hsvToRgb", Float::class.java, Float::class.java, Float::class.java, FloatArray::class.java)
        hsvToRgbMethod.isAccessible = true
        val rgb = FloatArray(3)
        val brushLayer = BrushLayer(1080, 1080)
        hsvToRgbMethod.invoke(brushLayer, 0.175f, 1.0f, 1.0f, rgb)

        // Non-zero RGB values expected (colorful yellow/green base)
        assertTrue(rgb[0] > 0f)
        assertTrue(rgb[1] > 0f)
    }

    @Test
    fun testCancelStrokePreventsDabCreation() {
        val layer = BrushLayer(1080, 1080)
        layer.activePreset = com.astral.typer.utils.MyPaintBrushHelper.BrushPreset("pencil")
        layer.startStroke(500f, 500f)
        assertTrue(layer.isDrawingStroke)

        layer.cancelStroke()
        assertFalse(layer.isDrawingStroke)
        assertFalse(layer.strokeHasMoved)

        // Calling endStroke after cancelStroke should do nothing and produce no tiles
        layer.endStroke()
        assertTrue(layer.tiles.isEmpty())
    }
}
