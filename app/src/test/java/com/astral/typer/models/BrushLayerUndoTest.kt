package com.astral.typer.models

import android.graphics.Canvas
import com.astral.typer.utils.UndoManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushLayerUndoTest {

    private class TestLayer(var layerName: String) : Layer() {
        override fun getWidth(): Float = 100f
        override fun getHeight(): Float = 100f
        override fun contains(px: Float, py: Float): Boolean = false
        override fun draw(canvas: Canvas) {}
        override fun clone(): Layer {
            val copy = TestLayer(layerName)
            copy.x = x
            copy.y = y
            copy.rotation = rotation
            copy.scaleX = scaleX
            copy.scaleY = scaleY
            copy.isSelected = isSelected
            copy.isVisible = isVisible
            copy.isLocked = isLocked
            copy.isClipped = isClipped
            copy.name = name
            copy.opacity = opacity
            copy.blendMode = blendMode
            return copy
        }
    }

    @Test
    fun testUndoRedoPreservesSelectedLayerState() {
        UndoManager.clearMemory()

        val layer1 = TestLayer("Layer 1").apply { isSelected = false }
        val layer2 = TestLayer("Layer 2").apply {
            name = "Active Layer"
            isSelected = true
            x = 10f
        }

        val layersList = listOf(layer1, layer2)

        // Save State (State 1: layer2 selected, x = 10)
        UndoManager.saveState(layersList)

        // Modify state (State 2: change x position to 50)
        layer2.x = 50f
        val modifiedList = listOf(layer1, layer2)

        // Perform undo
        val undoneList = UndoManager.undo(modifiedList)
        assertNotNull(undoneList)
        val selectedUndone = undoneList!!.find { it.isSelected }
        assertNotNull(selectedUndone)
        assertTrue(selectedUndone is TestLayer)
        assertEquals("Active Layer", selectedUndone!!.name)
        assertEquals(10f, selectedUndone.x, 0.001f)

        // Perform redo
        val redoneList = UndoManager.redo(undoneList)
        assertNotNull(redoneList)
        val selectedRedone = redoneList!!.find { it.isSelected }
        assertNotNull(selectedRedone)
        assertTrue(selectedRedone is TestLayer)
        assertEquals("Active Layer", selectedRedone!!.name)
        assertEquals(50f, selectedRedone.x, 0.001f)
    }

    @Test
    fun testDiscardLastStateRemovesTopState() {
        UndoManager.clearMemory()

        val layer1 = TestLayer("Layer 1")
        val layersList = listOf(layer1)

        UndoManager.saveState(layersList)
        assertTrue(UndoManager.canUndo())

        UndoManager.discardLastState()
        assertFalse(UndoManager.canUndo())
    }
}
