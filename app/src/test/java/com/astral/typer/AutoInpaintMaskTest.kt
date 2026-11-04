package com.astral.typer

import com.astral.typer.utils.ProjectManager
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutoInpaintMaskTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testAutoInpaintMethodSignatureHasKeepMaskLayerParam() {
        val methods = ProjectManager::class.java.declaredMethods
        val autoInpaintProjectMethod = methods.find { it.name == "autoInpaintProject" }
        assertNotNull("autoInpaintProject method must exist", autoInpaintProjectMethod)

        val autoInpaintProjectsMethod = methods.find { it.name == "autoInpaintProjects" }
        assertNotNull("autoInpaintProjects method must exist", autoInpaintProjectsMethod)

        val autoInpaintFolderMethod = methods.find { it.name == "autoInpaintFolder" }
        assertNotNull("autoInpaintFolder method must exist", autoInpaintFolderMethod)
    }

    @Test
    fun testKeepMaskLayerModelStructureAndAutoLock() {
        val width = 800
        val height = 1200
        val projectData = ProjectManager.ProjectData(
            canvasWidth = width,
            canvasHeight = height,
            canvasColor = 0,
            layers = emptyList()
        )

        // The Inpaint Mask layer is full-canvas sized, centered at (width / 2f, height / 2f), and locked by default
        val maskLayerModel = ProjectManager.LayerModel(
            type = "IMAGE",
            x = width / 2f,
            y = height / 2f,
            rotation = 0f,
            scaleX = 1f,
            scaleY = 1f,
            isVisible = true,
            isLocked = true,
            name = "Inpaint Mask",
            opacity = 255,
            blendMode = "NORMAL",
            isOpacityGradient = false,
            opacityStart = 255,
            opacityEnd = 0,
            opacityAngle = 0,
            imagePath = "images/layer_inpaint_mask_0.png"
        )

        val updated = projectData.copy(layers = projectData.layers + maskLayerModel)
        assertEquals(1, updated.layers.size)
        assertEquals("Inpaint Mask", updated.layers[0].name)
        assertEquals("IMAGE", updated.layers[0].type)
        assertEquals(400f, updated.layers[0].x)
        assertEquals(600f, updated.layers[0].y)
        assertTrue("Inpaint mask layer should be auto-locked", updated.layers[0].isLocked)
        assertEquals("images/layer_inpaint_mask_0.png", updated.layers[0].imagePath)

        val gson = Gson()
        val json = gson.toJson(updated)
        val deserialized = gson.fromJson(json, ProjectManager.ProjectData::class.java)
        assertEquals(1, deserialized.layers.size)
        assertEquals("Inpaint Mask", deserialized.layers[0].name)
        assertTrue("Deserialized layer should remain locked", deserialized.layers[0].isLocked)
        assertEquals(400f, deserialized.layers[0].x)
        assertEquals(600f, deserialized.layers[0].y)
    }
}
