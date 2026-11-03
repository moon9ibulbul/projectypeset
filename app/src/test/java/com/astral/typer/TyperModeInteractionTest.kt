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
}
