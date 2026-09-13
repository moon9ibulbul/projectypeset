package com.astral.typer

import com.astral.typer.utils.ProjectManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class ProjectManagerZipTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testZipProjectFolderPackagesChildProjectsAsAtdFiles() {
        val rootDir = tempFolder.newFolder("ParentProject")

        // Create a child project directory with project.json
        val childProject1 = File(rootDir, "Chapter1")
        childProject1.mkdirs()
        File(childProject1, "project.json").writeText("{\"canvasWidth\":1080,\"canvasHeight\":1080,\"canvasColor\":-1,\"layers\":[]}")
        File(childProject1, "images").mkdirs()
        File(File(childProject1, "images"), "background.png").writeText("fake_image_content")

        // Create another child project directory with project.json
        val childProject2 = File(rootDir, "Chapter2")
        childProject2.mkdirs()
        File(childProject2, "project.json").writeText("{\"canvasWidth\":1080,\"canvasHeight\":1920,\"canvasColor\":-1,\"layers\":[]}")

        // Create a non-project file
        File(rootDir, "read_me.txt").writeText("Info file")

        val outputZip = tempFolder.newFile("ExportedFolder.zip")

        val success = ProjectManager.zipProjectFolder(rootDir, outputZip)
        assertTrue("zipProjectFolder should return true", success)

        // Inspect the contents of outputZip
        val entryNames = mutableListOf<String>()
        ZipFile(outputZip).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                entryNames.add(entries.nextElement().name)
            }
        }

        println("Zip Entry Names: $entryNames")

        assertTrue("Should contain Chapter1.atd", entryNames.any { it.endsWith("Chapter1.atd") })
        assertTrue("Should contain Chapter2.atd", entryNames.any { it.endsWith("Chapter2.atd") })
        assertTrue("Should contain read_me.txt", entryNames.any { it.endsWith("read_me.txt") })

        // Verify that internal project files of Chapter1 were NOT added as raw uncompressed paths (e.g. ParentProject/Chapter1/project.json)
        assertFalse("Should not contain raw project.json inside child project folder",
            entryNames.any { it.contains("Chapter1/project.json") })
    }

    @Test
    fun testUnzipProjectFolderExtractsProjectJson() {
        val rootDir = tempFolder.newFolder("ParentProject2")

        val childProject1 = File(rootDir, "Chapter1")
        childProject1.mkdirs()
        File(childProject1, "project.json").writeText("{\"canvasWidth\":1080,\"canvasHeight\":1080,\"canvasColor\":-1,\"layers\":[]}")

        val outputZip = tempFolder.newFile("ExportedFolder2.zip")
        ProjectManager.zipProjectFolder(rootDir, outputZip)

        // Extract Chapter1.atd from outputZip to a temp dir
        val extractTarget = tempFolder.newFolder("UnpackedChapter1")
        val atdTempFile = tempFolder.newFile("extracted_chapter1.atd")

        ZipFile(outputZip).use { zip ->
            val entry = zip.getEntry("ParentProject2/Chapter1.atd") ?: zip.getEntry("Chapter1.atd")
            assertNotNull("Chapter1.atd entry should exist", entry)
            zip.getInputStream(entry).use { input ->
                FileOutputStream(atdTempFile).use { out -> input.copyTo(out) }
            }
        }

        val unzipSuccess = ProjectManager.unzipProjectFolder(atdTempFile, extractTarget)
        assertTrue("unzipProjectFolder should return true", unzipSuccess)
        assertTrue("project.json should exist inside extracted project folder", File(extractTarget, "project.json").exists())
    }
}
