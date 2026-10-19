package com.astral.typer

import com.astral.typer.utils.PsdExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PsdExporterTest {

    @Test
    fun testPsdBlendModeKeyMapping() {
        val method = PsdExporter::class.java.getDeclaredMethod("mapBlendModeKey", String::class.java)
        method.isAccessible = true

        assertEquals("norm", method.invoke(PsdExporter, "NORMAL"))
        assertEquals("mul ", method.invoke(PsdExporter, "MULTIPLY"))
        assertEquals("scrn", method.invoke(PsdExporter, "SCREEN"))
        assertEquals("over", method.invoke(PsdExporter, "OVERLAY"))
        assertEquals("dark", method.invoke(PsdExporter, "DARKEN"))
        assertEquals("lite", method.invoke(PsdExporter, "LIGHTEN"))
        assertEquals("add ", method.invoke(PsdExporter, "ADD"))
        assertEquals("norm", method.invoke(PsdExporter, "UNKNOWN"))
        assertEquals("norm", method.invoke(PsdExporter, null as String?))
    }

    @Test
    fun testPsdLayerMetaStruct() {
        val meta = PsdExporter.PsdLayerMeta(
            name = "Text Layer 1",
            opacity = 200,
            isVisible = true,
            blendMode = "MULTIPLY",
            isClipped = true,
            renderAction = {}
        )

        assertEquals("Text Layer 1", meta.name)
        assertEquals(200, meta.opacity)
        assertTrue(meta.isVisible)
        assertEquals("MULTIPLY", meta.blendMode)
        assertTrue(meta.isClipped)
    }
}
