package com.astral.typer

import com.astral.typer.utils.AlphanumComparator
import com.astral.typer.utils.SfxFolder
import com.astral.typer.utils.SfxPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SfxPresetManagerTest {

    @Test
    fun testSfxPresetAlphabeticalSorting() {
        val presets = listOf(
            SfxPreset(name = "ZAP!"),
            SfxPreset(name = "BOOM!"),
            SfxPreset(name = "BANG!"),
            SfxPreset(name = "SFX 10"),
            SfxPreset(name = "SFX 2")
        )

        val sorted = presets.sortedWith { a, b -> AlphanumComparator.compare(a.name, b.name) }
        val sortedNames = sorted.map { it.name }

        assertEquals(listOf("BANG!", "BOOM!", "SFX 2", "SFX 10", "ZAP!"), sortedNames)
    }

    @Test
    fun testSfxFolderAssociationAndFallback() {
        val folder1 = SfxFolder(name = "Explosions")
        val folder2 = SfxFolder(name = "Impacts")

        val presetSingle = SfxPreset(name = "BOOM", folderId = folder1.id)
        assertEquals(listOf(folder1.id), presetSingle.getSfxFolderIds())

        val presetMulti = SfxPreset(name = "CRASH", folderIds = listOf(folder1.id, folder2.id))
        assertEquals(listOf(folder1.id, folder2.id), presetMulti.getSfxFolderIds())

        val presetUnassigned = SfxPreset(name = "SILENT")
        assertTrue(presetUnassigned.getSfxFolderIds().isEmpty())
    }
}
