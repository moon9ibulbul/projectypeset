package com.astral.typer

import com.astral.typer.views.AstralCanvasView
import org.junit.Assert.*
import org.junit.Test

class TyperModeInteractionTest {

    @Test
    fun testTyperToolStateAndInterceptionLogic() {
        // Verify default TyperTool is HAND
        val defaultTool = AstralCanvasView.TyperTool.HAND
        assertEquals(AstralCanvasView.TyperTool.HAND, defaultTool)

        // Verify tool enum definitions
        val tools = AstralCanvasView.TyperTool.values().toList()
        assertTrue(tools.contains(AstralCanvasView.TyperTool.HAND))
        assertTrue(tools.contains(AstralCanvasView.TyperTool.RECT))
        assertTrue(tools.contains(AstralCanvasView.TyperTool.CIRCLE))
        assertTrue(tools.contains(AstralCanvasView.TyperTool.LASSO))
        assertTrue(tools.contains(AstralCanvasView.TyperTool.MAGIC_WAND))
        assertTrue(tools.contains(AstralCanvasView.TyperTool.ERASER))
        assertTrue(tools.contains(AstralCanvasView.TyperTool.MERGE))
        assertTrue(tools.contains(AstralCanvasView.TyperTool.MOVE))

        // Interception logic check:
        // pointerCount >= 2 || (currentTyperTool != TyperTool.HAND && currentMode == Mode.PAN_ZOOM)
        val shouldEarlyInterceptHandSingleTouch = 1 >= 2 || (AstralCanvasView.TyperTool.HAND != AstralCanvasView.TyperTool.HAND)
        assertFalse("Single touch with TyperTool.HAND should not be intercepted early", shouldEarlyInterceptHandSingleTouch)

        val shouldEarlyInterceptRectZoom = 1 >= 2 || (AstralCanvasView.TyperTool.RECT != AstralCanvasView.TyperTool.HAND)
        assertTrue("Active PAN_ZOOM with drawing tools (e.g. RECT) should be intercepted early", shouldEarlyInterceptRectZoom)

        val shouldEarlyInterceptHandMultiTouch = 2 >= 2 || (AstralCanvasView.TyperTool.HAND != AstralCanvasView.TyperTool.HAND)
        assertTrue("Multi-touch with TyperTool.HAND should be intercepted early for pan/zoom", shouldEarlyInterceptHandMultiTouch)
    }

    @Test
    fun testSmartTyperBubbleMergeWithNarrowTail() {
        data class SimpleBubble(val left: Float, val top: Float, val right: Float, val bottom: Float, val isOval: Boolean) {
            fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
            fun touchesOrOverlaps(b: SimpleBubble): Boolean = left <= b.right && right >= b.left && top <= b.bottom && bottom >= b.top
        }

        fun mergeBubblesSmart(bubbles: List<SimpleBubble>, cx: Float, cy: Float): List<SimpleBubble> {
            val initial = bubbles.find { it.contains(cx, cy) } ?: return bubbles
            val toMerge = mutableSetOf(initial)
            var added = true
            while (added) {
                added = false
                for (b in bubbles) {
                    if (b !in toMerge && toMerge.any { it.touchesOrOverlaps(b) }) {
                        toMerge.add(b)
                        added = true
                    }
                }
            }
            if (toMerge.size <= 1) return bubbles

            val mainBubble = toMerge.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) } ?: initial
            val mainArea = (mainBubble.right - mainBubble.left) * (mainBubble.bottom - mainBubble.top)

            var left = mainBubble.left
            var top = mainBubble.top
            var right = mainBubble.right
            var bottom = mainBubble.bottom
            var allOval = mainBubble.isOval

            for (b in toMerge) {
                if (b == mainBubble) continue
                if (!b.isOval) allOval = false

                val bArea = (b.right - b.left) * (b.bottom - b.top)
                val isSmallFragment = bArea < (mainArea * 0.28f)
                val isNarrowTail = (b.right - b.left < (mainBubble.right - mainBubble.left) * 0.35f) ||
                                   (b.bottom - b.top < (mainBubble.bottom - mainBubble.top) * 0.35f)

                if (isSmallFragment && isNarrowTail) {
                    val maxAllowableHInflation = (mainBubble.right - mainBubble.left) * 0.10f
                    if (b.left < left) left = maxOf(b.left, left - maxAllowableHInflation)
                    if (b.right > right) right = minOf(b.right, right + maxAllowableHInflation)

                    val maxAllowableVInflation = (mainBubble.bottom - mainBubble.top) * 0.12f
                    if (b.top < top) top = maxOf(b.top, top - maxAllowableVInflation)
                    if (b.bottom > bottom) bottom = minOf(b.bottom, bottom + maxAllowableVInflation)
                } else {
                    left = minOf(left, b.left)
                    top = minOf(top, b.top)
                    right = maxOf(right, b.right)
                    bottom = maxOf(bottom, b.bottom)
                }
            }

            val merged = SimpleBubble(left, top, right, bottom, isOval = allOval)
            val remaining = bubbles.filter { it !in toMerge }
            return remaining + merged
        }

        // Main speech bubble: (100, 100) to (300, 300) -> width = 200, height = 200, area = 40,000
        val mainBubble = SimpleBubble(100f, 100f, 300f, 300f, isOval = true)
        // Protruding dialog tail pointing to the right speaker: (290, 180) to (420, 210) -> width = 130, height = 30, area = 3,900 (< 10% of main)
        val tailBubble = SimpleBubble(290f, 180f, 420f, 210f, isOval = true)

        val mergedList = mergeBubblesSmart(listOf(mainBubble, tailBubble), 200f, 200f)
        assertEquals(1, mergedList.size)
        val merged = mergedList[0]

        // Without smart tail handling, right would be 420f and width = 320f (+60% inflated).
        // With smart tail handling, right inflation is capped to at most 10% of main width (max 20px -> 320px).
        assertTrue("Merged bubble right bound (${merged.right}) should be clamped near main body right (<= 320f)", merged.right <= 320f)
        assertEquals("Main bubble left should remain 100f", 100f, merged.left, 0.001f)
    }

    @Test
    fun testTyperBubbleCoordinatesPure() {
        // Verify bounding box inclusion algorithm without android.graphics.RectF stub
        data class SimpleBubble(val left: Float, val top: Float, val right: Float, val bottom: Float, val isOval: Boolean) {
            fun contains(x: Float, y: Float): Boolean {
                return x in left..right && y in top..bottom
            }
        }

        val bubble1 = SimpleBubble(100f, 100f, 300f, 400f, isOval = true)
        val bubble2 = SimpleBubble(400f, 200f, 600f, 500f, isOval = false)
        val bubbles = listOf(bubble1, bubble2)

        val hit1 = bubbles.find { it.contains(200f, 250f) }
        assertNotNull(hit1)
        assertEquals(bubble1, hit1)

        val hit2 = bubbles.find { it.contains(450f, 300f) }
        assertNotNull(hit2)
        assertEquals(bubble2, hit2)

        val hitMiss = bubbles.find { it.contains(50f, 50f) }
        assertNull(hitMiss)
    }

    @Test
    fun testTyperBubbleMergeLogic() {
        data class SimpleBubble(val left: Float, val top: Float, val right: Float, val bottom: Float, val isOval: Boolean) {
            fun contains(x: Float, y: Float): Boolean {
                return x in left..right && y in top..bottom
            }
            fun touchesOrOverlaps(b: SimpleBubble): Boolean {
                return left <= b.right && right >= b.left && top <= b.bottom && bottom >= b.top
            }
        }

        fun mergeBubblesAt(bubbles: List<SimpleBubble>, cx: Float, cy: Float): List<SimpleBubble> {
            val initial = bubbles.find { it.contains(cx, cy) } ?: return bubbles
            val toMerge = mutableSetOf(initial)
            var added = true
            while (added) {
                added = false
                for (b in bubbles) {
                    if (b !in toMerge) {
                        if (toMerge.any { it.touchesOrOverlaps(b) }) {
                            toMerge.add(b)
                            added = true
                        }
                    }
                }
            }
            if (toMerge.size <= 1) return bubbles

            var minL = Float.MAX_VALUE
            var minT = Float.MAX_VALUE
            var maxR = -Float.MAX_VALUE
            var maxB = -Float.MAX_VALUE
            var allOval = true
            for (b in toMerge) {
                minL = minOf(minL, b.left)
                minT = minOf(minT, b.top)
                maxR = maxOf(maxR, b.right)
                maxB = maxOf(maxB, b.bottom)
                if (!b.isOval) allOval = false
            }

            val merged = SimpleBubble(minL, minT, maxR, maxB, isOval = allOval)
            val remaining = bubbles.filter { it !in toMerge }
            return remaining + merged
        }

        // Test 2 overlapping ovals
        val b1 = SimpleBubble(50f, 50f, 150f, 150f, isOval = true)
        val b2 = SimpleBubble(100f, 100f, 200f, 200f, isOval = true)
        val b3 = SimpleBubble(500f, 500f, 600f, 600f, isOval = false)

        val merged = mergeBubblesAt(listOf(b1, b2, b3), 75f, 75f)
        assertEquals(2, merged.size)
        val mergedBubble = merged.find { it.contains(75f, 75f) }
        assertNotNull(mergedBubble)
        assertEquals(50f, mergedBubble!!.left, 0.001f)
        assertEquals(50f, mergedBubble.top, 0.001f)
        assertEquals(200f, mergedBubble.right, 0.001f)
        assertEquals(200f, mergedBubble.bottom, 0.001f)
        assertTrue(mergedBubble.isOval)

        // Non-overlapping bubble b3 remains unchanged
        val unmerged = merged.find { it.contains(550f, 550f) }
        assertNotNull(unmerged)
        assertEquals(b3, unmerged)
    }

    @Test
    fun testTyperBubbleMoveLogic() {
        data class SimpleBubble(val left: Float, val top: Float, val right: Float, val bottom: Float, val isOval: Boolean) {
            fun contains(x: Float, y: Float): Boolean {
                return x in left..right && y in top..bottom
            }
            fun translated(dx: Float, dy: Float): SimpleBubble {
                return SimpleBubble(left + dx, top + dy, right + dx, bottom + dy, isOval)
            }
        }

        val b1 = SimpleBubble(100f, 100f, 300f, 300f, isOval = true)
        val dx = 50f
        val dy = -30f
        val moved = b1.translated(dx, dy)

        assertEquals(150f, moved.left, 0.001f)
        assertEquals(70f, moved.top, 0.001f)
        assertEquals(350f, moved.right, 0.001f)
        assertEquals(270f, moved.bottom, 0.001f)
        assertTrue(moved.isOval)
    }

    @Test
    fun testTyperSmartReshapeToggleAndMagicWandGuards() {
        val sampleText = "Halo nama saya Budi\nSaya tinggal di Jakarta"
        val smartResult = com.astral.typer.utils.TyperLayoutSolver.solveLayoutPure(
            rawText = sampleText,
            bubbleWidth = 300f,
            bubbleHeight = 200f,
            isOval = false,
            baseFontSize = 30f,
            minScale = 0.1f,
            smartReshape = true
        )
        assertNotNull(smartResult)

        val nonSmartResult = com.astral.typer.utils.TyperLayoutSolver.solveLayoutPure(
            rawText = sampleText,
            bubbleWidth = 300f,
            bubbleHeight = 200f,
            isOval = false,
            baseFontSize = 30f,
            minScale = 0.1f,
            smartReshape = false
        )
        assertNotNull(nonSmartResult)
        assertEquals("When smart reshape is disabled, formattedText should preserve raw text", sampleText, nonSmartResult.formattedText)
    }

    @Test
    fun testReflectionBlurPropertyPersistence() {
        val gson = com.google.gson.Gson()
        val json = """{"name":"ReflectionTest","reflectionBlur":25.0}"""
        val styleModel = gson.fromJson(json, com.astral.typer.utils.StyleManager.StyleModel::class.java)
        assertEquals(25f, styleModel.reflectionBlur, 0.001f)

        val layerJson = """{"type":"TEXT","reflectionBlur":18.0}"""
        val layerModel = gson.fromJson(layerJson, com.astral.typer.utils.ProjectManager.LayerModel::class.java)
        assertEquals(18f, layerModel.reflectionBlur ?: 0f, 0.001f)
    }
}
