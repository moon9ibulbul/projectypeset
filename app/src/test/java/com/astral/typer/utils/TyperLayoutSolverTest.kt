package com.astral.typer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun testGenerateReshapeVariantsThreeOptions() {
        // Teks bahasa indonesia dengan beberapa kata
        val text = "Aku ingin kita selalu bersama melakukan hal hebat di dunia ini"
        val variants = TyperLayoutSolver.generateReshapeVariantsPure(
            rawText = text,
            currentWidth = 280f,
            currentHeight = 320f,
            isOval = true,
            baseFontSize = 24f
        )

        assertNotNull(variants)
        // Harus menyediakan 3 variasi layout
        assertEquals("Harus menghasilkan tepat 3 opsi variasi layout", 3, variants.size)

        // Verifikasi bahwa seluruh kata asli masuk ke setiap variasi (tidak ada teks terpotong / hilang)
        val originalWords = text.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        for (v in variants) {
            val variantWords = v.formattedText.replace("-", "").split("\\s+".toRegex()).filter { it.isNotEmpty() }
            // Seluruh kata dalam teks asli harus terdapat dalam variasi
            for (w in originalWords) {
                val cleanOriginal = w.replace("[.,!?:;\"'()]+".toRegex(), "")
                val found = variantWords.any { it.contains(cleanOriginal) }
                assertTrue("Kata '$cleanOriginal' harus masuk ke dalam variasi '${v.variantName}'", found)
            }
        }

        // Pastikan tidak ada duplikat formattedText
        val distinctFormatted = variants.map { it.formattedText }.distinct()
        assertEquals("Semua variasi harus memiliki formatting yang unik", variants.size, distinctFormatted.size)

        // Verifikasi sebisa mungkin tidak ada pemenggalan kata bersuku kata awalan seperti "me-lakukan"
        for (v in variants) {
            assertFalse("Harus sebisa mungkin tidak memenggal awalan 'me-lakukan'", v.formattedText.contains("me-\nlakukan") || v.formattedText.contains("me- lakukan"))
        }
    }

    @Test
    fun testGenerateReshapeVariantsShortText() {
        val text = "Selamat pagi"
        val variants = TyperLayoutSolver.generateReshapeVariantsPure(
            rawText = text,
            currentWidth = 200f,
            currentHeight = 200f,
            isOval = false,
            baseFontSize = 24f
        )

        assertNotNull(variants)
        assertTrue("Harus menghasilkan setidaknya 1 variasi untuk teks pendek", variants.isNotEmpty())
        for (v in variants) {
            assertTrue("Teks harus mengandung 'Selamat' dan 'pagi'", v.formattedText.contains("Selamat") && v.formattedText.contains("pagi"))
        }
    }

    @Test
    fun testGenerateReshapeVariantsReduplicationWordHandling() {
        assertTrue("Harus mengenali 'Tergopoh-gopoh' sebagai kata ulang", TyperLayoutSolver.isReduplicationWord("Tergopoh-gopoh"))
        assertTrue("Harus mengenali 'berlari-lari' sebagai kata ulang", TyperLayoutSolver.isReduplicationWord("berlari-lari"))
        assertTrue("Harus mengenali 'sama-sama' sebagai kata ulang", TyperLayoutSolver.isReduplicationWord("sama-sama"))
        assertFalse("Tidak boleh menganggap kata biasa dengan strip seperti 'semi-final' sebagai kata ulang reduplikasi", TyperLayoutSolver.isReduplicationWord("semi-final"))

        val split = TyperLayoutSolver.splitReduplication("Tergopoh-gopoh")
        assertNotNull(split)
        assertEquals("Tergopoh-", split?.first)
        assertEquals("gopoh", split?.second)

        val text = "Dia datang Tergopoh-gopoh"
        val variants = TyperLayoutSolver.generateReshapeVariantsPure(
            rawText = text,
            currentWidth = 180f,
            currentHeight = 250f,
            isOval = true,
            baseFontSize = 20f
        )
        assertNotNull(variants)
        assertTrue(variants.isNotEmpty())
        val hasIntact = variants.any { it.formattedText.contains("Tergopoh-gopoh") }
        assertTrue("Harus mengutamakan opsi utuh 'Tergopoh-gopoh'", hasIntact)
    }
}
