package com.astral.typer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TyperLayoutSolverTest {

    @Test
    fun testIndonesianHyphenationBasic() {
        val syl1 = IndonesianHyphenator.hyphenateWord("makan")
        assertEquals(listOf("ma", "kan"), syl1)

        val syl2 = IndonesianHyphenator.hyphenateWord("belajar")
        assertEquals(listOf("be", "la", "jar"), syl2)

        val syl3 = IndonesianHyphenator.hyphenateWord("pintu")
        assertEquals(listOf("pin", "tu"), syl3)

        val syl4 = IndonesianHyphenator.hyphenateWord("pantai")
        assertEquals(listOf("pan", "tai"), syl4)
    }

    @Test
    fun testIndonesianHyphenationDigraphs() {
        val syl1 = IndonesianHyphenator.hyphenateWord("sangat")
        assertEquals(listOf("sa", "ngat"), syl1)

        val syl2 = IndonesianHyphenator.hyphenateWord("kenyang")
        assertEquals(listOf("ke", "nyang"), syl2)

        val syl3 = IndonesianHyphenator.hyphenateWord("syarat")
        assertEquals(listOf("sya", "rat"), syl3)
    }

    @Test
    fun testHyphenationOpportunities() {
        val opps = IndonesianHyphenator.getHyphenationOpportunities("menyelesaikan")
        assertTrue("Harus memiliki titik potong hyphenation", opps.isNotEmpty())
        assertTrue("Titik potong harus diakhiri tanda hubung -", opps.any { it.first.endsWith("-") })
    }

    @Test
    fun testSolveLayoutFallbackAndScaling() {
        val bubbleWidth = 300f
        val bubbleHeight = 400f

        val text = "Aku tidak menyangka kita akan bertemu lagi di tempat seperti ini!"
        val result = TyperLayoutSolver.solveLayoutPure(
            rawText = text,
            bubbleWidth = bubbleWidth,
            bubbleHeight = bubbleHeight,
            isOval = true,
            baseFontSize = 28f
        )

        assertNotNull(result)
        assertTrue("Formatted text tidak boleh kosong", result.formattedText.isNotEmpty())
        assertTrue("Skala harus berada di rentang 0.20 .. 1.0", result.scale in 0.20f..1.0f)
        assertTrue("Box width harus muat di dalam bubble width", result.boxWidth <= bubbleWidth)
        assertTrue("Line count harus lebih dari 1 untuk teks panjang", result.lineCount > 1)
    }

    @Test
    fun testSolveLayoutShortText() {
        val text = "Halo!"
        val result = TyperLayoutSolver.solveLayoutPure(
            rawText = text,
            bubbleWidth = 200f,
            bubbleHeight = 200f,
            isOval = false,
            baseFontSize = 24f
        )

        assertEquals("Halo!", result.formattedText)
        assertEquals(1.0f, result.scale, 0.01f)
        assertEquals(1, result.lineCount)
    }

    @Test
    fun testSolveLayoutDiamondShapeKetikaAkuBertemuDenganmu() {
        // User's specific scenario: "Ketika aku bertemu denganmu"
        // Expected shape: stepped / diamond (berundak): "Ketika\naku bertemu\ndenganmu"
        // NOT 1 word per line: "Ketika\naku\nbertemu\ndenganmu"
        val text = "Ketika aku bertemu denganmu"
        val bubbleWidth = 300f
        val bubbleHeight = 350f

        val result = TyperLayoutSolver.solveLayoutPure(
            rawText = text,
            bubbleWidth = bubbleWidth,
            bubbleHeight = bubbleHeight,
            isOval = true,
            baseFontSize = 28f
        )

        val lines = result.formattedText.split("\n")
        // Verify it does NOT enter after each word (which would be 4 lines)
        assertNotEquals("Harus tidak enter pada setiap kata (1 kata per baris)", 4, lines.size)
        // Verify it creates the 3-line diamond pattern: "Ketika\naku bertemu\ndenganmu"
        assertEquals("Harus memiliki 3 baris membentuk pola berundak/diamond", 3, lines.size)
        assertEquals("Ketika", lines[0].trim())
        assertEquals("aku bertemu", lines[1].trim())
        assertEquals("denganmu", lines[2].trim())
        assertTrue("Box width harus muat di dalam bubble width", result.boxWidth <= bubbleWidth)
    }

    @Test
    fun testSolveLayoutWhatsUpInSpeechBubble() {
        // Screenshot scenario: "What's Up?"
        val text = "What's Up?"
        val bubbleWidth = 220f
        val bubbleHeight = 200f

        val result = TyperLayoutSolver.solveLayoutPure(
            rawText = text,
            bubbleWidth = bubbleWidth,
            bubbleHeight = bubbleHeight,
            isOval = true,
            baseFontSize = 28f
        )

        val lines = result.formattedText.split("\n")
        // Must never break words character-by-character into single letters like 'W\nhat\n\'s\nUp\n?'
        assertTrue("Line count should be 1 or 2", lines.size in 1..2)
        for (line in lines) {
            assertTrue("Line should not be a single letter", line.trim().length > 1)
        }
    }
}
