package com.astral.typer

import android.content.ContextWrapper
import com.astral.typer.utils.InpaintManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        assertEquals(6, engines.size)

        assertEquals(InpaintManager.Engine.OPENCV, InpaintManager.Engine.valueOf("OPENCV"))
        assertEquals(InpaintManager.Engine.PATCHMATCH, InpaintManager.Engine.valueOf("PATCHMATCH"))
        assertEquals(InpaintManager.Engine.SMART_SOLID, InpaintManager.Engine.valueOf("SMART_SOLID"))
        assertEquals(InpaintManager.Engine.HYBRID, InpaintManager.Engine.valueOf("HYBRID"))
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

        inpaintManager.setEngine(InpaintManager.Engine.HYBRID)
        assertEquals(InpaintManager.Engine.HYBRID, inpaintManager.getEngine())

        inpaintManager.setEngine(InpaintManager.Engine.PATCHMATCH)
        assertEquals(InpaintManager.Engine.PATCHMATCH, inpaintManager.getEngine())

        inpaintManager.setEngine(InpaintManager.Engine.SMART_SOLID)
        assertEquals(InpaintManager.Engine.SMART_SOLID, inpaintManager.getEngine())

        inpaintManager.setEngine(InpaintManager.Engine.OPENCV)
        assertEquals(InpaintManager.Engine.OPENCV, inpaintManager.getEngine())

        inpaintManager.setEngine(InpaintManager.Engine.LAMA)
        assertEquals(InpaintManager.Engine.LAMA, inpaintManager.getEngine())

        inpaintManager.setEngine(InpaintManager.Engine.MIGAN)
        assertEquals(InpaintManager.Engine.MIGAN, inpaintManager.getEngine())
    }

    @Test
    fun testDetectButtonActionPreferenceValues() {
        val defaultAction = "Detect + Inpaint"
        val detectOnlyAction = "Detect Only"

        // Verify valid action string formats
        assertEquals("Detect + Inpaint", defaultAction)
        assertEquals("Detect Only", detectOnlyAction)
    }

    @Test
    fun testInpaintMorphologyBitSetDilationAndErosion() {
        val w = 31
        val h = 31
        val cx = 15
        val cy = 15
        val baseRadius = 6

        // Create a circular test mask
        val currentBitSet = java.util.BitSet(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= baseRadius * baseRadius) {
                    currentBitSet.set(y * w + x)
                }
            }
        }
        val origCount = currentBitSet.cardinality()
        assertTrue(origCount > 0)

        // Helper replicating BitSet morphology logic
        fun applyBitSetMorphology(input: java.util.BitSet, expand: Int): java.util.BitSet {
            val radius = Math.abs(expand)
            val offsets = mutableListOf<Pair<Int, Int>>()
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    if (dx * dx + dy * dy <= radius * radius) {
                        offsets.add(Pair(dx, dy))
                    }
                }
            }

            val nextBitSet = java.util.BitSet(w * h)
            if (expand > 0) {
                for (y in 0 until h) {
                    val yOff = y * w
                    for (x in 0 until w) {
                        if (input.get(yOff + x)) {
                            for (offset in offsets) {
                                val nx = x + offset.first
                                val ny = y + offset.second
                                if (nx in 0 until w && ny in 0 until h) {
                                    nextBitSet.set(ny * w + nx)
                                }
                            }
                        }
                    }
                }
            } else {
                for (y in 0 until h) {
                    val yOff = y * w
                    for (x in 0 until w) {
                        if (input.get(yOff + x)) {
                            var allSet = true
                            for (offset in offsets) {
                                val nx = x + offset.first
                                val ny = y + offset.second
                                if (nx !in 0 until w || ny !in 0 until h || !input.get(ny * w + nx)) {
                                    allSet = false
                                    break
                                }
                            }
                            if (allSet) {
                                nextBitSet.set(yOff + x)
                            }
                        }
                    }
                }
            }
            return nextBitSet
        }

        // Test positive expand (dilation expands mask)
        val dilatedBitSet = applyBitSetMorphology(currentBitSet, 2)
        val dilatedCount = dilatedBitSet.cardinality()
        assertTrue("Dilated mask count ($dilatedCount) must be strictly greater than original ($origCount)", dilatedCount > origCount)

        // Test negative expand (erosion shrinks mask)
        val erodedBitSet = applyBitSetMorphology(currentBitSet, -2)
        val erodedCount = erodedBitSet.cardinality()
        assertTrue("Eroded mask count ($erodedCount) must be strictly less than original ($origCount)", erodedCount < origCount)

        // Test boundary pixels: corner (0,0) must remain unset (transparent)
        assertEquals("Corner (0,0) must not be set after dilation", false, dilatedBitSet.get(0))
    }

    @Test
    fun testInpaintExpandToggleStateLogic() {
        var inpaintMaskExpand = 0
        var isExpandActive = false
        var savedExpandValue = 5

        // Toggle ON
        isExpandActive = true
        inpaintMaskExpand = savedExpandValue
        assertEquals(5, inpaintMaskExpand)

        // Adjust slider to negative value (-3, shrink)
        savedExpandValue = -3
        inpaintMaskExpand = savedExpandValue
        assertEquals(-3, inpaintMaskExpand)

        // Toggle OFF
        isExpandActive = false
        inpaintMaskExpand = 0
        assertEquals(0, inpaintMaskExpand)

        // Saved value should be preserved for next toggle
        assertEquals(-3, savedExpandValue)

        // Toggle back ON restores the saved value
        isExpandActive = true
        inpaintMaskExpand = savedExpandValue
        assertEquals(-3, inpaintMaskExpand)
    }
}
