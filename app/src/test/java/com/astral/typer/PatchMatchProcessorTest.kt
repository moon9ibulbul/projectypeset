package com.astral.typer

import com.astral.typer.utils.PatchMatchProcessor
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchMatchProcessorTest {

    @Test
    fun testIsModelAvailable() {
        val processor = PatchMatchProcessor()
        assertTrue(processor.isModelAvailable())
    }
}
