package com.astral.typer

import com.astral.typer.utils.ProjectManager
import com.astral.typer.utils.StrokeSpan
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PerspectiveSkewAndSpanStrokeTest {

    @Test
    fun testStrokeSpanPropertiesAndCompanion() {
        val testColor = 0xFFFF0000.toInt()
        val span = StrokeSpan(testColor, 8f)
        assertEquals(testColor, span.strokeColor)
        assertEquals(8f, span.strokeWidth, 0.001f)

        // Test companion state
        StrokeSpan.overrideColor = 0xFF0000FF.toInt()
        StrokeSpan.extraWidth = 4f
        try {
            assertEquals(0xFF0000FF.toInt(), StrokeSpan.overrideColor)
            assertEquals(4f, StrokeSpan.extraWidth, 0.001f)
        } finally {
            StrokeSpan.overrideColor = null
            StrokeSpan.extraWidth = 0f
        }
    }

    @Test
    fun testStrokeSpanSerialization() {
        val hexColor = "#FFFF0000"
        val spanModel = ProjectManager.SpanModel("STROKE", 2, 7, "$hexColor,6.0")

        val gson = Gson()
        val json = gson.toJson(spanModel)
        val deserialized = gson.fromJson(json, ProjectManager.SpanModel::class.java)

        assertNotNull(deserialized)
        assertEquals("STROKE", deserialized.type)
        assertEquals(2, deserialized.start)
        assertEquals(7, deserialized.end)

        val parts = deserialized.value?.split(",")
        assertNotNull(parts)
        assertEquals(2, parts!!.size)
        assertEquals("#FFFF0000", parts[0])
        assertEquals(6f, parts[1].toFloat(), 0.001f)
    }

    @Test
    fun testPerspectiveSkewParallelogramMath() {
        // Initial 1x1 mesh rectangle: w = 200, h = 100
        // Point 0 (TL): (-100, -50)
        // Point 1 (TR): (100, -50)
        // Point 2 (BL): (-100, 50)
        // Point 3 (BR): (100, 50)
        val mesh = floatArrayOf(
            -100f, -50f,
             100f, -50f,
            -100f,  50f,
             100f,  50f
        )

        // Drag Top-Right (index 1) by (+30, +10)
        val warpPointIndex = 1
        val dx = 30f
        val dy = 10f
        when (warpPointIndex) {
            0 -> {
                mesh[0] += dx; mesh[1] += dy
                mesh[2] += dx
                mesh[5] += dy
            }
            1 -> {
                mesh[2] += dx; mesh[3] += dy
                mesh[0] += dx // TL moves horizontally
                mesh[7] += dy // BR moves vertically
            }
            2 -> {
                mesh[4] += dx; mesh[5] += dy
                mesh[6] += dx
                mesh[1] += dy
            }
            3 -> {
                mesh[6] += dx; mesh[7] += dy
                mesh[4] += dx
                mesh[3] += dy
            }
        }

        // Verify parallelogram constraints:
        // Top edge vector (TR - TL):
        val topEdgeX = mesh[2] - mesh[0]
        val topEdgeY = mesh[3] - mesh[1]

        // Bottom edge vector (BR - BL):
        val bottomEdgeX = mesh[6] - mesh[4]
        val bottomEdgeY = mesh[7] - mesh[5]

        // Left edge vector (BL - TL):
        val leftEdgeX = mesh[4] - mesh[0]
        val leftEdgeY = mesh[5] - mesh[1]

        // Right edge vector (BR - TR):
        val rightEdgeX = mesh[6] - mesh[2]
        val rightEdgeY = mesh[7] - mesh[3]

        assertEquals("Top and bottom edges must be parallel in X", bottomEdgeX, topEdgeX, 0.001f)
        assertEquals("Top and bottom edges must be parallel in Y", bottomEdgeY, topEdgeY, 0.001f)
        assertEquals("Left and right edges must be parallel in X", rightEdgeX, leftEdgeX, 0.001f)
        assertEquals("Left and right edges must be parallel in Y", rightEdgeY, leftEdgeY, 0.001f)
    }

    @Test
    fun testTextTailNormalizedWaveCalculations() {
        val tailLength = 200f
        val steps = 100
        val tailSeed = 12345L
        val tailRandom = Random(tailSeed)
        val phase1 = tailRandom.nextDouble() * Math.PI * 2
        val phase2 = tailRandom.nextDouble() * Math.PI * 2
        val phase3 = tailRandom.nextDouble() * Math.PI * 2

        // Verify that wave offsets remain within proper bounded range and start cleanly
        for (intensity in listOf(0f, 10f, 25f, 50f)) {
            var maxOffset = 0f
            var firstOffset = 0f

            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val d = t * tailLength

                val envelope = (t * 3f).coerceAtMost(1f)
                val wave1 = Math.sin(d * 0.035 + phase1)
                val wave2 = Math.sin(d * 0.08 + phase2) * 0.35
                val wave3 = Math.sin(d * 0.015 + phase3) * 0.5
                val waveOffset = (wave1 + wave2 + wave3).toFloat() * envelope * (intensity * 0.6f)

                if (i == 1) {
                    firstOffset = Math.abs(waveOffset)
                }
                maxOffset = Math.max(maxOffset, Math.abs(waveOffset))
            }

            // At intensity 0, offset must be exactly 0
            if (intensity == 0f) {
                assertEquals(0f, maxOffset, 0.001f)
            } else {
                // Initial offset at t -> 0 must be very small (smooth transition from text origin)
                assertTrue("First offset should be small for smooth connection", firstOffset < intensity * 0.1f)
                // Maximum offset at max intensity 50 should not exceed ~60px (proper wave, not radical)
                assertTrue("Max wave offset should be properly proportioned", maxOffset <= intensity * 1.2f)
            }
        }
    }
}
