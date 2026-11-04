package com.astral.typer

import com.astral.typer.utils.TyperBubbleSegmentationHelper
import org.junit.Assert.*
import org.junit.Test

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
        assertTrue("Overlapping bubbles should be classified as ovals", b1.isOval && b2.isOval)
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
        for (x in 50..250) {
            if ((x / 10) % 2 == 0) {
                pixels[40 * width + x] = colBorder
                pixels[160 * width + x] = colBorder
            }
        }
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

        assertTrue("Left bound ($left) should be near 50", left in 48..55)
        assertTrue("Top bound ($top) should be near 40", top in 38..45)
        assertTrue("Right bound ($right) should be near 250", right in 245..252)
        assertTrue("Bottom bound ($bottom) should be near 160", bottom in 155..162)
    }

    @Test
    fun testDashedPanelWithIdenticalInsideAndOutsideBackground() {
        // Simulating Panel 5: inside and outside panel share the exact same gray color (0xFFD7D7D7)
        // Dashed lines are dark blue/black (0xFF1A1A30) with 15px dash and 18px gap
        val width = 500
        val height = 400
        val pixels = IntArray(width * height) { 0xFFD7D7D7.toInt() }

        val colBorder = 0xFF1A1A30.toInt()
        val rectLeft = 80
        val rectTop = 60
        val rectRight = 420
        val rectBottom = 220

        // Dash pattern: 15px dash, 18px gap (period 33px)
        val dashCycle = 33
        for (x in rectLeft..rectRight) {
            if ((x - rectLeft) % dashCycle < 15) {
                pixels[rectTop * width + x] = colBorder
                pixels[rectBottom * width + x] = colBorder
            }
        }
        for (y in rectTop..rectBottom) {
            if ((y - rectTop) % dashCycle < 15) {
                pixels[y * width + rectLeft] = colBorder
                pixels[y * width + rectRight] = colBorder
            }
        }

        val provider = TyperBubbleSegmentationHelper.PixelProvider { px, py ->
            pixels[py * width + px]
        }

        val result = TyperBubbleSegmentationHelper.performClosedFloodFill(
            startX = 200,
            startY = 140,
            canvasW = width,
            canvasH = height,
            pixelProvider = provider,
            maxDiff = 30,
            gapClosingRadius = 24
        )

        assertNotNull("Should detect dashed box without leaking even when background is identical", result)
        val (bounds, mask) = result!!

        val left = bounds[0]
        val top = bounds[1]
        val right = bounds[2]
        val bottom = bounds[3]

        assertTrue("Left bound ($left) should be within 2px of $rectLeft", left in (rectLeft - 2)..(rectLeft + 2))
        assertTrue("Top bound ($top) should be within 2px of $rectTop", top in (rectTop - 2)..(rectTop + 2))
        assertTrue("Right bound ($right) should be within 2px of $rectRight", right in (rectRight - 2)..(rectRight + 2))
        assertTrue("Bottom bound ($bottom) should be within 2px of $rectBottom", bottom in (rectBottom - 2)..(rectBottom + 2))

        // Check shape classification
        val mWidth = right - left + 1
        val mHeight = bottom - top + 1
        val isOval = TyperBubbleSegmentationHelper.detectIsOval(mWidth, mHeight, mask)
        assertFalse("Dashed panel box should be detected as rectangle (isOval = false)", isOval)

        val segmented = TyperBubbleSegmentationHelper.segmentConnectedMask(
            maskWidth = mWidth,
            maskHeight = mHeight,
            mask = mask,
            offsetX = left.toFloat(),
            offsetY = top.toFloat()
        )
        assertEquals(1, segmented.size)
        assertFalse("Segmented bubble should have isOval = false for rectangular dashed panel", segmented[0].isOval)
    }

    @Test
    fun testShapeClassificationOvalVsRectangle() {
        val w = 120
        val h = 80

        // Test 1: Filled rectangle
        val rectMask = BooleanArray(w * h) { true }
        val isOvalRect = TyperBubbleSegmentationHelper.detectIsOval(w, h, rectMask)
        assertFalse("Full rectangle should be detected as rectangle (isOval = false)", isOvalRect)

        // Test 2: Filled ellipse
        val ovalMask = BooleanArray(w * h)
        val rx = w / 2f
        val ry = h / 2f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (x - rx) / rx
                val dy = (y - ry) / ry
                if (dx * dx + dy * dy <= 1.0f) {
                    ovalMask[y * w + x] = true
                }
            }
        }
        val isOvalEllipse = TyperBubbleSegmentationHelper.detectIsOval(w, h, ovalMask)
        assertTrue("Ellipse should be detected as oval (isOval = true)", isOvalEllipse)
    }

    @Test
    fun testBubbleWithProtrudingTailTrimming() {
        // Create an oval bubble body (diameter 100x100) centered at (80, 80)
        // plus a thin narrow tail pointing far to the right from (130, 80) to (220, 80) with thickness 8px
        val w = 240
        val h = 160
        val mask = BooleanArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (x - 80).toFloat() / 50f
                val dy = (y - 80).toFloat() / 50f
                val inBody = (dx * dx + dy * dy) <= 1.0f

                // Narrow protruding tail pointing to speaker
                val inTail = (x in 120..210) && (y in 76..84)

                mask[y * w + x] = inBody || inTail
            }
        }

        val bubbles = TyperBubbleSegmentationHelper.segmentConnectedMask(
            maskWidth = w,
            maskHeight = h,
            mask = mask,
            offsetX = 0f,
            offsetY = 0f
        )

        assertEquals("Tail should not be split into an isolated bubble", 1, bubbles.size)
        val bubble = bubbles[0]
        val bubbleWidth = bubble.right - bubble.left

        // Main body is x in [30..130] (width ~ 100).
        // With tail trimming, width should not stretch to 180+ px (30 to 210 = 180px).
        assertTrue("Bubble width ($bubbleWidth) should focus on main body and trim thin tail", bubbleWidth < 140f)
        assertTrue("Bubble left (${bubble.left}) should align near main body left (~30)", bubble.left in 25f..35f)
    }
}
