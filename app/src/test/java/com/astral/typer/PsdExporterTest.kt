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

    @Test
    fun testExtractFontName() {
        assertEquals("ArialMT", PsdExporter.extractFontName(null))
        assertEquals("ArialMT", PsdExporter.extractFontName(""))
        assertEquals("Roboto-Bold", PsdExporter.extractFontName("/storage/emulated/0/fonts/Roboto-Bold.ttf"))
        assertEquals("Pacifico", PsdExporter.extractFontName("std_cache:Pacifico.otf"))
        assertEquals("Montserrat", PsdExporter.extractFontName("Montserrat"))
    }

    @Test
    fun testBuildEngineDataAndTyShBlock() {
        val textMeta = PsdExporter.PsdTextMeta(
            text = "Hello World",
            fontSize = 32f,
            fontName = "Roboto-Bold",
            color = android.graphics.Color.RED,
            x = 100f,
            y = 150f,
            rotation = 0f,
            scaleX = 1f,
            scaleY = 1f,
            isBold = true,
            isItalic = false,
            justification = 0
        )

        val engineData = PsdExporter.buildEngineData(textMeta)
        assertTrue("EngineData should not be empty", engineData.isNotEmpty())
        val engineStr = String(engineData, Charsets.US_ASCII)
        assertTrue(engineStr.contains("/EngineDict"))
        assertTrue(engineStr.contains("/ResourceDict"))
        assertTrue(engineStr.contains("/FontSet"))
        assertTrue(engineStr.contains("/StyleSheetSet"))
        assertTrue(engineStr.contains("/ParagraphSheetSet"))

        val tyshBlock = PsdExporter.buildTyShBlock(textMeta)
        assertTrue("TySh block should not be empty", tyshBlock.isNotEmpty())

        // Check header: version 1 (Int16 = 0x0001)
        assertEquals(0.toByte(), tyshBlock[0])
        assertEquals(1.toByte(), tyshBlock[1])

        // Verify classId TxLr signature inside TySh block
        val tyshStr = String(tyshBlock, Charsets.US_ASCII)
        assertTrue(tyshStr.contains("TxLr"))
        assertTrue(tyshStr.contains("EngineData"))
        assertTrue(tyshStr.contains("warp"))
    }

    @Test
    fun testBuildTyShBlockWithWarp() {
        val textMeta = PsdExporter.PsdTextMeta(
            text = "Warped Text",
            fontSize = 40f,
            fontName = "ArialMT",
            color = android.graphics.Color.BLUE,
            x = 50f,
            y = 50f,
            rotation = 0f,
            scaleX = 1f,
            scaleY = 1f,
            isWarp = true,
            warpRows = 3,
            warpCols = 3
        )

        val tyshBlock = PsdExporter.buildTyShBlock(textMeta)
        val tyshStr = String(tyshBlock, Charsets.US_ASCII)
        assertTrue(tyshStr.contains("warpCustom"))
        assertTrue(tyshStr.contains("deformNumRows"))
        assertTrue(tyshStr.contains("deformNumCols"))
    }

    @Test
    fun testBuildLrfxBlock() {
        val shadowMeta = PsdExporter.PsdShadowMeta(
            blur = 15f,
            dx = 10f,
            dy = 10f,
            color = android.graphics.Color.BLACK
        )

        val lrfxBlock = PsdExporter.buildLrfxBlock(shadowMeta)
        assertTrue("lrFX block should not be empty", lrfxBlock.isNotEmpty())
        val lrfxStr = String(lrfxBlock, Charsets.US_ASCII)
        assertTrue(lrfxStr.contains("cmnS"))
        assertTrue(lrfxStr.contains("dsdw"))
    }
}
