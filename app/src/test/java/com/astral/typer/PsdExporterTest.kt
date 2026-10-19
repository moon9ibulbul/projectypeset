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
    fun testPsdLayerInfoStruct() {
        // Test PSD Layer Info class fields
        val info = PsdExporter.PsdLayerInfo(
            name = "Text Layer 1",
            bitmap = null as android.graphics.Bitmap?,
            opacity = 200,
            isVisible = true,
            blendMode = "MULTIPLY",
            isClipped = true
        )

        assertEquals("Text Layer 1", info.name)
        assertEquals(200, info.opacity)
        assertTrue(info.isVisible)
        assertEquals("MULTIPLY", info.blendMode)
        assertTrue(info.isClipped)
    }
}
