package com.astral.typer

import com.astral.typer.utils.StyleManager
import com.astral.typer.utils.StyleManager.StyleModel
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StyleManagerTest {

    @Test
    fun testMinimalJsonDeserialization() {
        val minimalJson = """
            {
                "color": -1,
                "fontSize": 40.0,
                "fontPath": "standard/Arial.ttf",
                "opacity": 255
            }
        """.trimIndent()

        val gson = Gson()
        val styleModel = gson.fromJson(minimalJson, StyleModel::class.java)

        assertNotNull(styleModel)
        println("styleModel.name: " + styleModel.name)
        println("styleModel.blendMode: " + styleModel.blendMode)
        println("styleModel.currentEffect: " + styleModel.currentEffect)
        println("styleModel.secondaryEffect: " + styleModel.secondaryEffect)

        // Test if elvis operator works on null platform/non-nullable fields
        val resolvedBlendMode = styleModel.blendMode ?: "NORMAL"
        assertEquals("NORMAL", resolvedBlendMode)
        println("Resolved blendMode successfully!")

        val resolvedCurrentEffect = styleModel.currentEffect ?: "NONE"
        assertEquals("NONE", resolvedCurrentEffect)
        println("Resolved currentEffect successfully!")

        // Test fallback defaults for middle color properties
        assertEquals(false, styleModel.hasMiddleColor)
        println("Default hasMiddleColor resolved to false successfully!")
    }

    @Test
    fun testMiddleColorDeserialization() {
        val middleColorJson = """
            {
                "color": -1,
                "fontSize": 40.0,
                "fontPath": "standard/Arial.ttf",
                "opacity": 255,
                "isGradient": true,
                "gradientStart": -65536,
                "gradientEnd": -16776961,
                "gradientAngle": 45,
                "hasMiddleColor": true,
                "gradientMiddleColor": -16711936
            }
        """.trimIndent()

        val gson = Gson()
        val styleModel = gson.fromJson(middleColorJson, StyleModel::class.java)

        assertNotNull(styleModel)
        assertEquals(true, styleModel.hasMiddleColor)
        assertEquals(-16711936, styleModel.gradientMiddleColor)
        println("Deserialized middle color properties successfully!")
    }
}
