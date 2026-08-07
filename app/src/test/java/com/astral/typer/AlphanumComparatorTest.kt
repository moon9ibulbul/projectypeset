package com.astral.typer

import com.astral.typer.utils.AlphanumComparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphanumComparatorTest {

    @Test
    fun testAlphanumSorting() {
        val input = listOf("img_10.png", "img_2.png", "img_1.png", "img_21.png", "img_2_1.png", "img_2_10.png")
        val expected = listOf("img_1.png", "img_2.png", "img_2_1.png", "img_2_10.png", "img_10.png", "img_21.png")

        val sorted = input.sortedWith(AlphanumComparator)
        assertEquals(expected, sorted)
        println("Natural order sorting verified: $sorted")
    }

    @Test
    fun testAlphanumComparison() {
        assertTrue(AlphanumComparator.compare("2", "10") < 0)
        assertTrue(AlphanumComparator.compare("10", "2") > 0)
        assertTrue(AlphanumComparator.compare("img_02", "img_2") > 0)
        assertTrue(AlphanumComparator.compare("Page_3", "Page_12") < 0)
        assertTrue(AlphanumComparator.compare("a", "b") < 0)
        assertTrue(AlphanumComparator.compare("abc", "abc") == 0)
        println("Direct Alphanum comparisons verified successfully!")
    }
}
