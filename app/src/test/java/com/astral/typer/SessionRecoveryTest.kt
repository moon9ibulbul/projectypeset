package com.astral.typer

import android.content.ContextWrapper
import com.astral.typer.utils.ProjectManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testHasSessionRecoveryAndClear() {
        val cacheDir = tempFolder.newFolder("cache")
        val fakeContext = object : ContextWrapper(null) {
            override fun getCacheDir(): File {
                return cacheDir
            }
        }

        // Initially no session recovery
        assertFalse(ProjectManager.hasSessionRecovery(fakeContext))

        // Create recovery directory and project.json
        val recoveryDir = File(cacheDir, "session_recovery")
        recoveryDir.mkdirs()
        val projectJson = File(recoveryDir, "project.json")
        projectJson.writeText("{\"canvasWidth\":1080,\"canvasHeight\":1080,\"canvasColor\":-1,\"layers\":[]}")

        // Should detect session recovery
        assertTrue(ProjectManager.hasSessionRecovery(fakeContext))

        // Clear session recovery
        ProjectManager.clearSessionRecovery(fakeContext)

        // Should be false after clear
        assertFalse(ProjectManager.hasSessionRecovery(fakeContext))
    }
}
