package com.astral.typer

import android.content.ContextWrapper
import com.astral.typer.utils.InpaintManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InpaintManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testInpaintEngineEnumValues() {
        val engines = InpaintManager.Engine.values()
        assertEquals(5, engines.size)

        assertEquals(InpaintManager.Engine.OPENCV, InpaintManager.Engine.valueOf("OPENCV"))
        assertEquals(InpaintManager.Engine.PATCHMATCH, InpaintManager.Engine.valueOf("PATCHMATCH"))
        assertEquals(InpaintManager.Engine.SMART_SOLID, InpaintManager.Engine.valueOf("SMART_SOLID"))
        assertEquals(InpaintManager.Engine.LAMA, InpaintManager.Engine.valueOf("LAMA"))
        assertEquals(InpaintManager.Engine.MIGAN, InpaintManager.Engine.valueOf("MIGAN"))
    }

    @Test
    fun testSetEngineState() {
        val filesDir = tempFolder.newFolder("files")
        val fakeContext = object : ContextWrapper(null) {
            override fun getFilesDir(): File {
                return filesDir
            }
        }

        val inpaintManager = InpaintManager(fakeContext)
        assertNotNull(inpaintManager)

        inpaintManager.setEngine(InpaintManager.Engine.PATCHMATCH)
        inpaintManager.setEngine(InpaintManager.Engine.SMART_SOLID)
        inpaintManager.setEngine(InpaintManager.Engine.OPENCV)
        inpaintManager.setEngine(InpaintManager.Engine.LAMA)
        inpaintManager.setEngine(InpaintManager.Engine.MIGAN)
    }
}
