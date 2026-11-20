package com.astral.typer.models

import android.graphics.Color
import android.graphics.Path
import com.astral.typer.utils.ProjectManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErasePersistenceTest {

    @Test
    fun testTextLayerClonePreservesErasePoints() {
        val layer = TextLayer("Erase Test", Color.BLACK)
        val pts = listOf(ErasePoint(10f, 20f), ErasePoint(30f, 40f), ErasePoint(50f, 60f))
        layer.erasePaths.add(ErasePathData(Path(), 24f, 200, 80f, pts))

        val cloned = layer.clone() as TextLayer
        assertEquals(1, cloned.erasePaths.size)
        val clonedErase = cloned.erasePaths[0]
        assertEquals(24f, clonedErase.size, 0.001f)
        assertEquals(200, clonedErase.opacity)
        assertEquals(80f, clonedErase.hardness, 0.001f)
        assertEquals(3, clonedErase.points.size)
        assertEquals(10f, clonedErase.points[0].x, 0.001f)
        assertEquals(20f, clonedErase.points[0].y, 0.001f)
        assertEquals(50f, clonedErase.points[2].x, 0.001f)
        assertEquals(60f, clonedErase.points[2].y, 0.001f)
    }

    @Test
    fun testShapeLayerClonePreservesErasePoints() {
        val layer = ShapeLayer("<svg></svg>", Color.BLACK)
        val pts = listOf(ErasePoint(-15f, 25f), ErasePoint(5f, -35f))
        layer.erasePaths.add(ErasePathData(Path(), 18f, 255, 100f, pts))

        val cloned = layer.clone() as ShapeLayer
        assertEquals(1, cloned.erasePaths.size)
        val clonedErase = cloned.erasePaths[0]
        assertEquals(2, clonedErase.points.size)
        assertEquals(-15f, clonedErase.points[0].x, 0.001f)
        assertEquals(25f, clonedErase.points[0].y, 0.001f)
        assertEquals(5f, clonedErase.points[1].x, 0.001f)
        assertEquals(-35f, clonedErase.points[1].y, 0.001f)
    }

    @Test
    fun testBrushLayerClonePreservesErasePoints() {
        val layer = BrushLayer(100, 100)
        val pts = listOf(ErasePoint(1f, 2f), ErasePoint(3f, 4f))
        layer.erasePaths.add(ErasePathData(Path(), 12f, 128, 50f, pts))

        val cloned = layer.clone() as BrushLayer
        assertEquals(1, cloned.erasePaths.size)
        assertEquals(2, cloned.erasePaths[0].points.size)
        assertEquals(1f, cloned.erasePaths[0].points[0].x, 0.001f)
        assertEquals(4f, cloned.erasePaths[0].points[1].y, 0.001f)
    }

    @Test
    fun testDoubleResolutionScalesErasePoints() {
        val layer = TextLayer("Double Res", Color.BLACK)
        val pts = listOf(ErasePoint(12f, -8f), ErasePoint(24f, 16f))
        layer.erasePaths.add(ErasePathData(Path(), 15f, 255, 90f, pts))

        layer.doubleResolution()

        assertEquals(1, layer.erasePaths.size)
        val scaled = layer.erasePaths[0]
        assertEquals(30f, scaled.size, 0.001f)
        assertEquals(2, scaled.points.size)
        assertEquals(24f, scaled.points[0].x, 0.001f)
        assertEquals(-16f, scaled.points[0].y, 0.001f)
        assertEquals(48f, scaled.points[1].x, 0.001f)
        assertEquals(32f, scaled.points[1].y, 0.001f)
    }

    @Test
    fun testRestoreErasePathsViaCreateLayerFromModel() {
        val eraseModel = ProjectManager.ErasePathModel(
            points = listOf(
                ProjectManager.ErasePointModel(10f, 15f),
                ProjectManager.ErasePointModel(20f, 25f)
            ),
            size = 32f,
            opacity = 220,
            hardness = 75f
        )
        val layerModel = ProjectManager.LayerModel(
            type = "SHAPE",
            x = 100f,
            y = 200f,
            rotation = 0f,
            scaleX = 1f,
            scaleY = 1f,
            isVisible = true,
            isLocked = false,
            name = "Shape 1",
            opacity = 255,
            blendMode = "NORMAL",
            isOpacityGradient = false,
            opacityStart = 255,
            opacityEnd = 0,
            opacityAngle = 0f,
            shapeName = "<svg></svg>",
            color = Color.BLACK,
            eraseMaskPath = null,
            erasePaths = listOf(eraseModel)
        )

        val restored = ProjectManager.createLayerFromModel(layerModel, emptyMap()) as ShapeLayer
        assertEquals(1, restored.erasePaths.size)
        assertEquals(2, restored.erasePaths[0].points.size)
        assertEquals(10f, restored.erasePaths[0].points[0].x, 0.001f)
        assertEquals(25f, restored.erasePaths[0].points[1].y, 0.001f)
        assertTrue(restored.erasePaths[0]. size == 32f)
    }
}
