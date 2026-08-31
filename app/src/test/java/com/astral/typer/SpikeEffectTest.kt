package com.astral.typer

import com.astral.typer.models.TextEffectType
import com.astral.typer.utils.ProjectManager.LayerModel
import com.astral.typer.utils.StyleManager.StyleModel
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SpikeEffectTest {

    @Test
    fun testTextEffectTypeSpikeEnum() {
        val spikeEnum = TextEffectType.valueOf("SPIKE")
        assertEquals(TextEffectType.SPIKE, spikeEnum)
    }

    @Test
    fun testStyleModelSpikeSerialization() {
        val gson = Gson()
        val json = """
            {
                "color": -1,
                "fontSize": 40.0,
                "fontPath": "standard/Arial.ttf",
                "opacity": 255,
                "currentEffect": "SPIKE",
                "spikeIntensity": 0.85,
                "spikeMaxLength": 40.0,
                "spikeSeed": 5555
            }
        """.trimIndent()

        val styleModel = gson.fromJson(json, StyleModel::class.java)
        assertNotNull(styleModel)
        assertEquals("SPIKE", styleModel.currentEffect)
        assertEquals(0.85f, styleModel.spikeIntensity ?: 0f, 0.001f)
        assertEquals(40.0f, styleModel.spikeMaxLength ?: 0f, 0.001f)
        assertEquals(5555L, styleModel.spikeSeed ?: 0L)

        val reserialized = gson.toJson(styleModel)
        val deserialized = gson.fromJson(reserialized, StyleModel::class.java)
        assertEquals("SPIKE", deserialized.currentEffect)
        assertEquals(0.85f, deserialized.spikeIntensity ?: 0f, 0.001f)
        assertEquals(40.0f, deserialized.spikeMaxLength ?: 0f, 0.001f)
        assertEquals(5555L, deserialized.spikeSeed ?: 0L)
    }

    @Test
    fun testLayerModelSpikeSerialization() {
        val gson = Gson()
        val json = """
            {
                "type": "TEXT",
                "text": "HORROR SFX",
                "currentEffect": "SPIKE",
                "spikeIntensity": 0.75,
                "spikeMaxLength": 50.0,
                "spikeSeed": 98765
            }
        """.trimIndent()

        val layerModel = gson.fromJson(json, LayerModel::class.java)
        assertNotNull(layerModel)
        assertEquals("SPIKE", layerModel.currentEffect)
        assertEquals(0.75f, layerModel.spikeIntensity ?: 0f, 0.001f)
        assertEquals(50.0f, layerModel.spikeMaxLength ?: 0f, 0.001f)
        assertEquals(98765L, layerModel.spikeSeed ?: 0L)

        val reserialized = gson.toJson(layerModel)
        val deserialized = gson.fromJson(reserialized, LayerModel::class.java)
        assertEquals("SPIKE", deserialized.currentEffect)
        assertEquals(0.75f, deserialized.spikeIntensity ?: 0f, 0.001f)
        assertEquals(50.0f, deserialized.spikeMaxLength ?: 0f, 0.001f)
        assertEquals(98765L, deserialized.spikeSeed ?: 0L)
    }
}
