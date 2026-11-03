package com.astral.typer

import android.content.Context
import android.content.ContextWrapper
import com.astral.typer.utils.BubbleDetectorProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TightTextMaskTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testBubbleDetectorProcessorMethodsExist() {
        val filesDir = tempFolder.newFolder("files")
        val fakeContext = object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getApplicationContext(): Context = this
        }

        val processor = BubbleDetectorProcessor(fakeContext)
        assertNotNull(processor)

        // Verify generateTightTextMasks method exists via reflection
        val method = BubbleDetectorProcessor::class.java.getDeclaredMethod(
            "generateTightTextMasks",
            android.graphics.Bitmap::class.java,
            List::class.java,
            List::class.java,
            Int::class.javaPrimitiveType
        )
        assertNotNull(method)
    }

    @Test
    fun testRamerDouglasPeuckerAndPolygonExtractionMethods() {
        // Inspect internal helper methods via reflection to test pure algorithms
        val rdpMethod = BubbleDetectorProcessor::class.java.getDeclaredMethod(
            "ramerDouglasPeucker",
            List::class.java,
            Double::class.javaPrimitiveType
        )
        rdpMethod.isAccessible = true

        val ptClass = Class.forName("com.astral.typer.utils.BubbleDetectorProcessor\$PointInt")
        val constructor = ptClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        constructor.isAccessible = true

        // Create a line with an outlier point: (0,0), (5,5), (10,0)
        val p1 = constructor.newInstance(0, 0)
        val p2 = constructor.newInstance(5, 5)
        val p3 = constructor.newInstance(10, 0)
        val points = listOf(p1, p2, p3)

        val filesDir = tempFolder.newFolder("files_rdp")
        val fakeContext = object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getApplicationContext(): Context = this
        }
        val processor = BubbleDetectorProcessor(fakeContext)

        // With small epsilon (0.5), p2 should be retained
        @Suppress("UNCHECKED_CAST")
        val resDetailed = rdpMethod.invoke(processor, points, 0.5) as List<Any>
        assertEquals(3, resDetailed.size)

        // With large epsilon (10.0), p2 should be simplified away
        @Suppress("UNCHECKED_CAST")
        val resSimplified = rdpMethod.invoke(processor, points, 10.0) as List<Any>
        assertEquals(2, resSimplified.size)
    }

    @Test
    fun testContourAreaCalculation() {
        val filesDir = tempFolder.newFolder("files_area")
        val fakeContext = object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getApplicationContext(): Context = this
        }
        val processor = BubbleDetectorProcessor(fakeContext)

        val areaMethod = BubbleDetectorProcessor::class.java.getDeclaredMethod(
            "calculateContourArea",
            List::class.java
        )
        areaMethod.isAccessible = true

        val ptClass = Class.forName("com.astral.typer.utils.BubbleDetectorProcessor\$PointInt")
        val constructor = ptClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        constructor.isAccessible = true

        // Create a 10x10 square: (0,0), (10,0), (10,10), (0,10)
        val p1 = constructor.newInstance(0, 0)
        val p2 = constructor.newInstance(10, 0)
        val p3 = constructor.newInstance(10, 10)
        val p4 = constructor.newInstance(0, 10)
        val square = listOf(p1, p2, p3, p4)

        val area = areaMethod.invoke(processor, square) as Double
        assertEquals(100.0, area, 0.001)
    }

    @Test
    fun testMorphologyDilationAndErosion() {
        val filesDir = tempFolder.newFolder("files_morph")
        val fakeContext = object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getApplicationContext(): Context = this
        }
        val processor = BubbleDetectorProcessor(fakeContext)

        val dilateMethod = BubbleDetectorProcessor::class.java.getDeclaredMethod(
            "morphDilate",
            BooleanArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        dilateMethod.isAccessible = true

        val erodeMethod = BubbleDetectorProcessor::class.java.getDeclaredMethod(
            "morphErode",
            BooleanArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        erodeMethod.isAccessible = true

        val w = 21
        val h = 21
        val mask = BooleanArray(w * h)
        // Set center pixel
        mask[10 * w + 10] = true

        val dilated = dilateMethod.invoke(processor, mask, w, h, 2) as BooleanArray
        var dilatedCount = 0
        for (v in dilated) if (v) dilatedCount++
        // An ellipse with radius 2 in discrete grid has 13 pixels
        assertEquals(13, dilatedCount)

        val eroded = erodeMethod.invoke(processor, dilated, w, h, 2) as BooleanArray
        var erodedCount = 0
        for (v in eroded) if (v) erodedCount++
        // After erosion with same radius 2, only the center pixel (or <= 1) should remain
        assertTrue(erodedCount in 1..2)
        assertTrue(eroded[10 * w + 10])
    }

    @Test
    fun testFillHolesInMask() {
        val filesDir = tempFolder.newFolder("files_holes")
        val fakeContext = object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getApplicationContext(): Context = this
        }
        val processor = BubbleDetectorProcessor(fakeContext)

        val fillHolesMethod = BubbleDetectorProcessor::class.java.getDeclaredMethod(
            "fillHoles",
            BooleanArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        fillHolesMethod.isAccessible = true

        val w = 5
        val h = 5
        // Hollow square:
        // 0 0 0 0 0
        // 0 1 1 1 0
        // 0 1 0 1 0   <-- hole at (2, 2)
        // 0 1 1 1 0
        // 0 0 0 0 0
        val mask = BooleanArray(w * h)
        for (y in 1..3) {
            for (x in 1..3) {
                if (x == 2 && y == 2) continue
                mask[y * w + x] = true
            }
        }

        val filled = fillHolesMethod.invoke(processor, mask, w, h) as BooleanArray
        // The hole at (2, 2) should now be filled (true)
        assertTrue(filled[2 * w + 2])
        // Outside border should remain false
        assertEquals(false, filled[0])
    }
}
