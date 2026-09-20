package com.letta.mobile.ui.canvas

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
 *
 * The table is keyed by window AND owned by one - a [CanvasPenRegistry] instance the host holds -
 * so it cannot outlive the windows in it.
 */
class CanvasPenInputRoutingTest {

    private data class TestWindow(val name: String) : CanvasPenTarget

    private val windowA = TestWindow("a")
    private val windowB = TestWindow("b")

    private fun event(x: Float, tool: CanvasPenTool = CanvasPenTool.DRAW) =
        CanvasPenEvent(CanvasPenEvent.Phase.DOWN, x, 0f, 0.5f, tool)

    // One registry per test, because a registry belongs to whoever owns the window. There is
    // nothing to reset between tests any more: when this was an object, every test had to undo
    // its own registrations or leak them into the next one.
    private val registry = CanvasPenRegistry()

    @Test
    fun eachWindowSeesOnlyItsOwnEvents() {
        val seenByA = mutableListOf<Float>()
        val seenByB = mutableListOf<Float>()
        registry.register(windowA) { seenByA += it.x; true }
        registry.register(windowB) { seenByB += it.x; true }

        registry.deliver(windowA, event(10f))
        registry.deliver(windowB, event(20f))

        assertEquals(listOf(10f), seenByA)
        assertEquals(listOf(20f), seenByB)
    }

    @Test
    fun closingOneWindowLeavesTheOtherWorking() {
        val seenByB = mutableListOf<Float>()
        val disposeA = registry.register(windowA) { true }
        registry.register(windowB) { seenByB += it.x; true }

        disposeA()

        assertFalse(registry.deliver(windowA, event(10f)), "a closed window must take nothing")
        assertTrue(registry.deliver(windowB, event(20f)))
        assertEquals(listOf(20f), seenByB)
    }

    @Test
    fun aStaleDisposerCannotSilenceItsReplacement() {
        // A canvas re-created before the old one disposes: the old teardown must not remove the
        // registration that replaced it.
        val disposeFirst = registry.register(windowA) { false }
        registry.register(windowA) { true }

        disposeFirst()

        assertTrue(registry.deliver(windowA, event(10f)), "the current registration was removed")
    }

    @Test
    fun anEventForAWindowWithNoCanvasIsDeclined() {
        assertFalse(registry.hasConsumer(windowA))
        assertFalse(registry.deliver(windowA, event(10f)))
    }

    @Test
    fun theEraserEndRoutesLikeTheDrawingEnd() {
        val tools = mutableListOf<CanvasPenTool>()
        registry.register(windowA) { tools += it.tool; true }

        registry.deliver(windowA, event(10f, CanvasPenTool.ERASER))
        registry.deliver(windowB, event(20f, CanvasPenTool.ERASER))

        assertEquals(listOf(CanvasPenTool.ERASER), tools, "window B's eraser must not reach window A")
    }
}
