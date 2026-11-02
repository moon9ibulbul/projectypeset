package com.astral.typer.models

import android.graphics.Color
import com.astral.typer.utils.ProjectManager
import com.astral.typer.utils.ProjectManager.LayerModel
import com.astral.typer.utils.StyleManager.StyleModel
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TextLayerHighlightAndStrokeTest {

    @Test
    fun testHighlightPaddingJsonSerialization() {
        val json = """
            {
                "type": "TEXT",
                "highlightCornerRadius": 12.0,
                "highlightPaddingTop": 4.0,
                "highlightPaddingRight": 8.0,
                "highlightPaddingBottom": 6.0,
                "highlightPaddingLeft": 10.0
            }
        """.trimIndent()

        val gson = Gson()
        val model = gson.fromJson(json, LayerModel::class.java)

        assertNotNull(model)
        assertEquals(12f, model.highlightCornerRadius ?: 0f, 0.001f)
        assertEquals(4f, model.highlightPaddingTop ?: 0f, 0.001f)
        assertEquals(8f, model.highlightPaddingRight ?: 0f, 0.001f)
        assertEquals(6f, model.highlightPaddingBottom ?: 0f, 0.001f)
        assertEquals(10f, model.highlightPaddingLeft ?: 0f, 0.001f)
    }

    @Test
    fun testStyleModelHighlightPaddingSerialization() {
        val json = """
            {
                "name": "Custom Style",
                "color": -1,
                "fontSize": 40.0,
                "opacity": 255,
                "highlightCornerRadius": 15.0,
                "highlightPaddingTop": 5.0,
                "highlightPaddingRight": 10.0,
                "highlightPaddingBottom": 15.0,
                "highlightPaddingLeft": 20.0
            }
        """.trimIndent()

        val gson = Gson()
        val styleModel = gson.fromJson(json, StyleModel::class.java)

        assertNotNull(styleModel)
        assertEquals(15f, styleModel.highlightCornerRadius, 0.001f)
        assertEquals(5f, styleModel.highlightPaddingTop, 0.001f)
        assertEquals(10f, styleModel.highlightPaddingRight, 0.001f)
        assertEquals(15f, styleModel.highlightPaddingBottom, 0.001f)
        assertEquals(20f, styleModel.highlightPaddingLeft, 0.001f)
    }

    @Test
    fun testBrushLayerHighlightPaddingProperties() {
        val brushLayer = BrushLayer(100, 100).apply {
            highlightCornerRadius = 10f
            highlightPaddingTop = 2f
            highlightPaddingRight = 3f
            highlightPaddingBottom = 4f
            highlightPaddingLeft = 5f
        }

        assertEquals(10f, brushLayer.highlightCornerRadius, 0.001f)
        assertEquals(2f, brushLayer.highlightPaddingTop, 0.001f)
        assertEquals(3f, brushLayer.highlightPaddingRight, 0.001f)
        assertEquals(4f, brushLayer.highlightPaddingBottom, 0.001f)
        assertEquals(5f, brushLayer.highlightPaddingLeft, 0.001f)
    }

    @Test
    fun testShapeLayerHighlightPaddingProperties() {
        val shapeLayer = ShapeLayer("<svg></svg>").apply {
            highlightCornerRadius = 8f
            highlightPaddingTop = 1f
            highlightPaddingRight = 2f
            highlightPaddingBottom = 3f
            highlightPaddingLeft = 4f
        }

        assertEquals(8f, shapeLayer.highlightCornerRadius, 0.001f)
        assertEquals(1f, shapeLayer.highlightPaddingTop, 0.001f)
        assertEquals(2f, shapeLayer.highlightPaddingRight, 0.001f)
        assertEquals(3f, shapeLayer.highlightPaddingBottom, 0.001f)
        assertEquals(4f, shapeLayer.highlightPaddingLeft, 0.001f)
    }
}
