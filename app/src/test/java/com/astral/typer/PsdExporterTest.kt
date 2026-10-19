package com.astral.typer

import com.astral.typer.utils.PsdExporter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PsdExporterTest {

    @Test
    fun testPackBitsEncodeRowRepeatedZeros() {
        // 100 zero bytes
        val input = ByteArray(100) { 0 }
        val encoded = PsdExporter.packBitsEncodeRow(input, 0, 100)

        // Should be encoded as [-99, 0] (2 bytes total instead of 100 bytes)
        assertEquals(2, encoded.size)
        assertEquals((-99).toByte(), encoded[0])
        assertEquals(0.toByte(), encoded[1])
    }

    @Test
    fun testPackBitsEncodeRowLiterals() {
        // Literal sequence: 1, 2, 3, 4, 5
        val input = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = PsdExporter.packBitsEncodeRow(input, 0, 5)

        // Header byte = 4 (length - 1), followed by 1, 2, 3, 4, 5
        assertEquals(6, encoded.size)
        assertEquals(4.toByte(), encoded[0])
        assertArrayEquals(byteArrayOf(4, 1, 2, 3, 4, 5), encoded)
    }

    @Test
    fun testPackBitsEncodeRowMaxRunLength() {
        // 200 repeated bytes 'A' (0x41)
        val input = ByteArray(200) { 'A'.code.toByte() }
        val encoded = PsdExporter.packBitsEncodeRow(input, 0, 200)

        // Max run length in PackBits is 128 (header byte -127 = 0x81).
        // First run = 128 bytes -> [-127, 'A'] (2 bytes)
        // Second run = 72 bytes -> [1 - 72 = -71, 'A'] (2 bytes)
        assertEquals(4, encoded.size)
        assertEquals((-127).toByte(), encoded[0])
        assertEquals('A'.code.toByte(), encoded[1])
        assertEquals((-71).toByte(), encoded[2])
        assertEquals('A'.code.toByte(), encoded[3])
    }

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
