package com.letta.mobile.ui.canvas

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pen events belong to the window they were reported against.
 *
 * With one process-wide consumer, a second canvas silently took the first one's place: whichever
 * registered last received everything, and whichever disposed first cleared the registration out
 * from under the other. The visible result is a window where pressure and the eraser end stop
 * working for no reason the person can see.
 */
class CanvasPenInputRoutingTest {

    private data class TestWindow(val name: String) : CanvasPenTarget

    private val windowA = TestWindow("a")
    private val windowB = TestWindow("b")

    private fun event(x: Float, tool: CanvasPenTool = CanvasPenTool.DRAW) =
        CanvasPenEvent(CanvasPenEvent.Phase.DOWN, x, 0f, 0.5f, tool)

    @AfterTest
    fun clearRegistrations() {
        // The registry outlives a test, as it outlives a composition.
        CanvasPenInput.register(windowA) { false }()
        CanvasPenInput.register(windowB) { false }()
    }

    @Test
    fun eachWindowSeesOnlyItsOwnEvents() {
        val seenByA = mutableListOf<Float>()
        val seenByB = mutableListOf<Float>()
        CanvasPenInput.register(windowA) { seenByA += it.x; true }
        CanvasPenInput.register(windowB) { seenByB += it.x; true }

        CanvasPenInput.deliver(windowA, event(10f))
        CanvasPenInput.deliver(windowB, event(20f))

        assertEquals(listOf(10f), seenByA)
        assertEquals(listOf(20f), seenByB)
    }

    @Test
    fun closingOneWindowLeavesTheOtherWorking() {
        val seenByB = mutableListOf<Float>()
        val disposeA = CanvasPenInput.register(windowA) { true }
        CanvasPenInput.register(windowB) { seenByB += it.x; true }

        disposeA()

        assertFalse(CanvasPenInput.deliver(windowA, event(10f)), "a closed window must take nothing")
        assertTrue(CanvasPenInput.deliver(windowB, event(20f)))
        assertEquals(listOf(20f), seenByB)
    }

    @Test
    fun aStaleDisposerCannotSilenceItsReplacement() {
        // A canvas re-created before the old one disposes: the old teardown must not remove the
        // registration that replaced it.
        val disposeFirst = CanvasPenInput.register(windowA) { false }
        CanvasPenInput.register(windowA) { true }

        disposeFirst()

        assertTrue(CanvasPenInput.deliver(windowA, event(10f)), "the current registration was removed")
    }

    @Test
    fun anEventForAWindowWithNoCanvasIsDeclined() {
        assertFalse(CanvasPenInput.hasConsumer(windowA))
        assertFalse(CanvasPenInput.deliver(windowA, event(10f)))
    }

    @Test
    fun theEraserEndRoutesLikeTheDrawingEnd() {
        val tools = mutableListOf<CanvasPenTool>()
        CanvasPenInput.register(windowA) { tools += it.tool; true }

        CanvasPenInput.deliver(windowA, event(10f, CanvasPenTool.ERASER))
        CanvasPenInput.deliver(windowB, event(20f, CanvasPenTool.ERASER))

        assertEquals(listOf(CanvasPenTool.ERASER), tools, "window B's eraser must not reach window A")
    }
}
