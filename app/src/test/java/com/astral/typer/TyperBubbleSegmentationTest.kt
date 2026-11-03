package com.astral.typer

import com.astral.typer.utils.TyperBubbleSegmentationHelper
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class TyperBubbleSegmentationTest {

    @Test
    fun testDoubleOvalSegmentation() {
        // Create synthetic binary mask representing 2 overlapping ovals (like Panel 7)
        // Oval 1 centered at (80, 80) with radius Rx=50, Ry=40
        // Oval 2 centered at (170, 160) with radius Rx=60, Ry=45
        val w = 260
        val h = 240
        val mask = BooleanArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx1 = (x - 80).toFloat() / 50f
                val dy1 = (y - 80).toFloat() / 40f
                val inOval1 = (dx1 * dx1 + dy1 * dy1) <= 1.0f

                val dx2 = (x - 170).toFloat() / 60f
                val dy2 = (y - 160).toFloat() / 45f
                val inOval2 = (dx2 * dx2 + dy2 * dy2) <= 1.0f

                mask[y * w + x] = inOval1 || inOval2
            }
        }

        val bubbles = TyperBubbleSegmentationHelper.segmentConnectedMask(
            maskWidth = w,
            maskHeight = h,
            mask = mask,
            offsetX = 100f,
            offsetY = 100f
        )

        // It should detect 2 segmented bubbles rather than 1 giant bounding box
        assertEquals("Should segment into 2 distinct bubbles", 2, bubbles.size)

        // Verify bounding boxes cover both sub-bubbles separately
        val b1 = bubbles.minByOrNull { it.left }!!
        val b2 = bubbles.maxByOrNull { it.left }!!

        val c1X = (b1.left + b1.right) / 2f
        val c2X = (b2.left + b2.right) / 2f
        val w1 = b1.right - b1.left
        val w2 = b2.right - b2.left

        assertTrue("Bubble 1 should be roughly in upper-left (c1X: $c1X)", c1X < 220f)
        assertTrue("Bubble 2 should be roughly in lower-right (c2X: $c2X)", c2X > 220f)
        assertTrue("Bubble 1 width should be reasonable (~100px)", w1 in 80f..140f)
        assertTrue("Bubble 2 width should be reasonable (~120px)", w2 in 90f..150f)
    }

    @Test
    fun testDashedBorderGapClosing() {
        // Create synthetic image grid (300 x 200)
        // Inside background: white (0xFFFFFFFF)
        // Outside panel background: gray (0xFFCCCCCC)
        // Dashed border: blue (0xFF0000FF), rectangle from (50, 40) to (250, 160)
        // Dash pattern: 10px line, 8px gap
        val width = 300
        val height = 200
        val pixels = IntArray(width * height)

        val colWhite = 0xFFFFFFFF.toInt()
        val colGray = 0xFFCCCCCC.toInt()
        val colBorder = 0xFF0000FF.toInt()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val isInside = (x in 51 until 250) && (y in 41 until 160)
                pixels[y * width + x] = if (isInside) colWhite else colGray
            }
        }

        // Draw dashed border lines around the rectangle
        // Top and bottom horizontal borders
        for (x in 50..250) {
            if ((x / 10) % 2 == 0) { // dash on/off
                pixels[40 * width + x] = colBorder
                pixels[160 * width + x] = colBorder
            }
        }
        // Left and right vertical borders
        for (y in 40..160) {
            if ((y / 10) % 2 == 0) {
                pixels[y * width + 50] = colBorder
                pixels[y * width + 250] = colBorder
            }
        }

        val provider = TyperBubbleSegmentationHelper.PixelProvider { px, py ->
            pixels[py * width + px]
        }

        // Flood fill from inside the dashed box (100, 100)
        val result = TyperBubbleSegmentationHelper.performClosedFloodFill(
            startX = 100,
            startY = 100,
            canvasW = width,
            canvasH = height,
            pixelProvider = provider,
            maxDiff = 30,
            gapClosingRadius = 15
        )

        assertNotNull("Closed flood fill should successfully detect the bounded region without leaking", result)
        val (bounds, mask) = result!!

        val left = bounds[0]
        val top = bounds[1]
        val right = bounds[2]
        val bottom = bounds[3]

        // Bounds should match rectangle around (50, 40) to (250, 160) closely
        assertTrue("Left bound ($left) should be near 50", left in 48..55)
        assertTrue("Top bound ($top) should be near 40", top in 38..45)
        assertTrue("Right bound ($right) should be near 250", right in 245..252)
        assertTrue("Bottom bound ($bottom) should be near 160", bottom in 155..162)
    }
}
