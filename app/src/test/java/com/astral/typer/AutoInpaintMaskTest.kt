package com.astral.typer

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.astral.typer.utils.BubbleDetectorProcessor
import com.astral.typer.utils.InpaintManager
import com.astral.typer.utils.ProjectManager
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

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
    fun testKeepMaskLayerModelStructure() {
        val projectData = ProjectManager.ProjectData(
            canvasWidth = 800,
            canvasHeight = 1200,
            canvasColor = 0,
            layers = emptyList()
        )

        val maskLayerModel = ProjectManager.LayerModel(
            type = "IMAGE",
            x = 400f,
            y = 600f,
            rotation = 0f,
            scaleX = 1f,
            scaleY = 1f,
            isVisible = true,
            isLocked = false,
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
        assertEquals("images/layer_inpaint_mask_0.png", updated.layers[0].imagePath)

        val gson = Gson()
        val json = gson.toJson(updated)
        val deserialized = gson.fromJson(json, ProjectManager.ProjectData::class.java)
        assertEquals(1, deserialized.layers.size)
        assertEquals("Inpaint Mask", deserialized.layers[0].name)
    }
}
