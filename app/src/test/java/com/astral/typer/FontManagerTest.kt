package com.astral.typer

import com.astral.typer.utils.FontManager
import com.astral.typer.utils.FontManager.FontItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FontManagerTest {

    @Test
    fun testExactMatchStandardFont() {
        val serif = FontItem("Serif", null, null, isCustom = false)
        val sans = FontItem("Sans Serif", null, null, isCustom = false)
        val fonts = listOf(serif, sans)

        val matched = FontManager.findMatchingFont(fonts, "Serif")
        assertEquals(serif, matched)
    }

    @Test
    fun testExactMatchCustomFontPath() {
        val customTtf = FontItem("A", "/data/user/0/com.astral.typer/files/fonts/A.ttf", null, isCustom = true)
        val fonts = listOf(customTtf)

        val matched = FontManager.findMatchingFont(fonts, "/data/user/0/com.astral.typer/files/fonts/A.ttf")
        assertEquals(customTtf, matched)
    }

    @Test
    fun testSmartFallbackDifferentExtension() {
        // Project uses A.ttf from sender device
        val targetPath = "/data/user/0/com.astral.typer/files/fonts/A.ttf"

        // Recipient device only has A.otf installed
        val customOtf = FontItem("A", "/data/user/0/com.astral.typer/files/fonts/A.otf", null, isCustom = true)
        val fonts = listOf(customOtf)

        val matched = FontManager.findMatchingFont(fonts, targetPath)
        assertNotNull("Should smartly resolve A.otf when A.ttf is searched", matched)
        assertEquals(customOtf, matched)
        assertTrue(FontManager.isMatchingFont(customOtf, targetPath, fonts))
    }

    @Test
    fun testSmartFallbackCaseInsensitive() {
        // Project uses my_custom_font.TTF
        val targetPath = "/sdcard/my_custom_font.TTF"

        // Recipient has MY_CUSTOM_FONT.otf
        val recipientFont = FontItem("MY_CUSTOM_FONT", "/data/user/0/com.astral.typer/files/fonts/MY_CUSTOM_FONT.otf", null, isCustom = true)
        val fonts = listOf(recipientFont)

        val matched = FontManager.findMatchingFont(fonts, targetPath)
        assertNotNull("Should match case-insensitively", matched)
        assertEquals(recipientFont, matched)
    }

    @Test
    fun testNoMatchForMissingFont() {
        val customOtf = FontItem("A", "/data/user/0/com.astral.typer/files/fonts/A.otf", null, isCustom = true)
        val fonts = listOf(customOtf)

        val matched = FontManager.findMatchingFont(fonts, "NonExistentFont.ttf")
        assertNull(matched)
    }
}
