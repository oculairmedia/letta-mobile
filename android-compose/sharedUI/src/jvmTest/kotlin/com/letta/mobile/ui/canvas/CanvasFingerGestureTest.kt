package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CanvasFingerGestureTest {

    @Test
    fun oneFingerPansInBothAxesFromWhereItLanded() {
        val gesture = CanvasFingerGesture()
        gesture.offer(1, CanvasBoardTouchSample.Phase.DOWN, 0f, 0f, onBoard = true)
        val place = gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 10f, 20f, onBoard = true)
        assertIs<CanvasBoardTouchResult.Consumed>(place.result)
        assertTrue(place.effects.isEmpty())

        val drag = gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 40f, 5f, onBoard = true)
        val pan = drag.effects.filterIsInstance<CanvasFingerEffect.Pan>().single()
        assertEquals(30f, pan.dx)
        assertEquals(-15f, pan.dy)
    }

    @Test
    fun aShortPressIsATapAndDoesNotPan() {
        val gesture = CanvasFingerGesture()
        gesture.offer(1, CanvasBoardTouchSample.Phase.DOWN, 0f, 0f, onBoard = true)
        gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 4f, 6f, onBoard = true)
        gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 6f, 7f, onBoard = true)
        val lift = gesture.offer(1, CanvasBoardTouchSample.Phase.UP, 6f, 7f, onBoard = true)
        val tap = assertIs<CanvasBoardTouchResult.Tap>(lift.result)
        assertEquals(4f, tap.x)
        assertEquals(6f, tap.y)
        val end = lift.effects.filterIsInstance<CanvasFingerEffect.End>().single()
        assertEquals("tap", end.kind)
        assertEquals(false, end.fling)
        assertTrue(lift.effects.none { it is CanvasFingerEffect.Release })
        assertTrue(lift.effects.none { it is CanvasFingerEffect.Pan })
    }

    @Test
    fun twoFingersPinchAndPanTheMidpoint() {
        val gesture = CanvasFingerGesture()
        gesture.offer(1, CanvasBoardTouchSample.Phase.DOWN, 0f, 0f, onBoard = true)
        gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 0f, 0f, onBoard = true)
        gesture.offer(2, CanvasBoardTouchSample.Phase.DOWN, 0f, 0f, onBoard = true)
        gesture.offer(2, CanvasBoardTouchSample.Phase.MOVE, 100f, 0f, onBoard = true)
        val armed = gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 0f, 0f, onBoard = true)
        assertTrue(armed.effects.none { it is CanvasFingerEffect.Zoom })

        val spread = gesture.offer(2, CanvasBoardTouchSample.Phase.MOVE, 200f, 40f, onBoard = true)
        val zoom = spread.effects.filterIsInstance<CanvasFingerEffect.Zoom>().single()
        val pan = spread.effects.filterIsInstance<CanvasFingerEffect.Pan>().single()
        assertEquals(hypot(200f, 40f) / 100f, zoom.factor, 0.001f)
        assertEquals(50f, pan.dx, 0.001f)
        assertEquals(20f, pan.dy, 0.001f)
    }

    @Test
    fun aMoveWithNoDownIsIgnored() {
        val gesture = CanvasFingerGesture()
        val move = gesture.offer(7, CanvasBoardTouchSample.Phase.MOVE, 40f, 40f, onBoard = true)
        assertIs<CanvasBoardTouchResult.Ignored>(move.result)
        assertTrue(move.effects.isEmpty())
    }

    @Test
    fun aFingerOffTheBoardIsLeftToOrdinaryScrolling() {
        val gesture = CanvasFingerGesture()
        gesture.offer(1, CanvasBoardTouchSample.Phase.DOWN, 0f, 0f, onBoard = false)
        val move = gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 80f, 10f, onBoard = false)
        assertIs<CanvasBoardTouchResult.Ignored>(move.result)
        assertTrue(move.effects.isEmpty())
    }

    @Test
    fun cancellingAFingerDoesNotTapOrFling() {
        val gesture = CanvasFingerGesture()
        gesture.offer(1, CanvasBoardTouchSample.Phase.DOWN, 0f, 0f, onBoard = true)
        gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 10f, 10f, onBoard = true)
        val drag = gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 40f, 10f, onBoard = true)
        assertTrue(drag.effects.filterIsInstance<CanvasFingerEffect.Pan>().isNotEmpty())

        val cancelled = gesture.offer(1, CanvasBoardTouchSample.Phase.CANCEL, 40f, 10f, onBoard = true)
        assertIs<CanvasBoardTouchResult.Consumed>(cancelled.result)
        assertTrue(cancelled.effects.none { it is CanvasFingerEffect.Release })
        assertTrue(cancelled.effects.none { it is CanvasFingerEffect.Pan })
    }

    @Test
    fun chromeInsideTheBoardIsNotTheBoard() {
        val chrome = CanvasChromeRegions()
        // Root space, not board-local. The board itself does not start at the origin.
        chrome.register { Rect(100f, 80f, 140f, 120f) }
        val binding = CanvasBoardTouchBinding()
        binding.board = Rect(100f, 80f, 300f, 280f)
        binding.density = 2f
        binding.chrome = chrome
        assertTrue(!binding.hits(55f, 45f))
        assertTrue(binding.hits(105f, 85f))
    }

    @Test
    fun aDragOf300IsTheFullTravelAndDensityScalesTheBoard() {
        val gesture = CanvasFingerGesture()
        gesture.offer(1, CanvasBoardTouchSample.Phase.DOWN, 0f, 0f, onBoard = true)
        gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 0f, 0f, onBoard = true)
        val drag = gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 300f, 0f, onBoard = true)
        val pan = drag.effects.filterIsInstance<CanvasFingerEffect.Pan>().single()
        assertEquals(300f, pan.dx)
        val binding = CanvasBoardTouchBinding()
        binding.density = 1.5f
        val applied = mutableListOf<androidx.compose.ui.geometry.Offset>()
        binding.pan = { applied.add(it) }
        val fling = io.ak1.drawbox.presentation.PanFling(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)) {}
        binding.apply(drag.effects, fling, 0L)
        assertEquals(450f, applied.sumOf { it.x.toDouble() }.toFloat(), 0.01f)
    }

    @Test
    fun aSymmetricPinchDoesNotPanAndDoesNotFling() {
        val gesture = CanvasFingerGesture()
        gesture.offer(1, CanvasBoardTouchSample.Phase.DOWN, 0f, 0f, onBoard = true)
        gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 0f, 0f, onBoard = true)
        gesture.offer(2, CanvasBoardTouchSample.Phase.DOWN, 0f, 0f, onBoard = true)
        gesture.offer(2, CanvasBoardTouchSample.Phase.MOVE, 100f, 0f, onBoard = true)
        val joined = gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 0f, 0f, onBoard = true)
        assertTrue(joined.effects.single() is CanvasFingerEffect.Arm)
        val left = gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, -50f, 0f, onBoard = true)
        val right = gesture.offer(2, CanvasBoardTouchSample.Phase.MOVE, 150f, 0f, onBoard = true)
        val panX = (left.effects + right.effects).filterIsInstance<CanvasFingerEffect.Pan>().sumOf { it.dx.toDouble() }
        assertEquals(0.0, panX, 0.5)
        val lift2 = gesture.offer(2, CanvasBoardTouchSample.Phase.UP, 150f, 0f, onBoard = true)
        val lift1 = gesture.offer(1, CanvasBoardTouchSample.Phase.UP, -50f, 0f, onBoard = true)
        val end = lift1.effects.filterIsInstance<CanvasFingerEffect.End>().single()
        assertEquals("pinch", end.kind)
        assertEquals(false, end.fling)
        assertTrue(lift2.effects.none { it is CanvasFingerEffect.Release })
        assertTrue(lift1.effects.none { it is CanvasFingerEffect.Release })
    }

    @Test
    fun aSecondFingerJoiningDoesNotPanOnThatFrame() {
        val gesture = CanvasFingerGesture()
        gesture.offer(1, CanvasBoardTouchSample.Phase.DOWN, 0f, 0f, onBoard = true)
        gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 0f, 0f, onBoard = true)
        gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 40f, 0f, onBoard = true)
        gesture.offer(2, CanvasBoardTouchSample.Phase.DOWN, 0f, 0f, onBoard = true)
        gesture.offer(2, CanvasBoardTouchSample.Phase.MOVE, 80f, 0f, onBoard = true)
        val joined = gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 40f, 0f, onBoard = true)
        assertTrue(joined.effects.single() is CanvasFingerEffect.Arm)
        assertTrue(joined.effects.none { it is CanvasFingerEffect.Pan })
    }

    @Test
    fun aFingerReturningFarAwayDoesNotJumpTheBoard() {
        val gesture = CanvasFingerGesture()
        gesture.offer(1, CanvasBoardTouchSample.Phase.DOWN, 0f, 0f, onBoard = true)
        gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 0f, 0f, onBoard = true)
        gesture.offer(2, CanvasBoardTouchSample.Phase.DOWN, 0f, 0f, onBoard = true)
        gesture.offer(2, CanvasBoardTouchSample.Phase.MOVE, 40f, 0f, onBoard = true)
        gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 0f, 0f, onBoard = true)
        gesture.offer(2, CanvasBoardTouchSample.Phase.UP, 40f, 0f, onBoard = true)
        gesture.offer(2, CanvasBoardTouchSample.Phase.DOWN, 0f, 0f, onBoard = true)
        val returned = gesture.offer(2, CanvasBoardTouchSample.Phase.MOVE, 240f, 0f, onBoard = true)
        assertTrue(returned.effects.none { it is CanvasFingerEffect.Pan })
    }

    @Test
    fun aRepeatedDownReplacesTheContact() {
        val gesture = CanvasFingerGesture()
        gesture.offer(1, CanvasBoardTouchSample.Phase.DOWN, 0f, 0f, onBoard = true)
        gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 0f, 0f, onBoard = true)
        gesture.offer(1, CanvasBoardTouchSample.Phase.DOWN, 0f, 0f, onBoard = true)
        val place = gesture.offer(1, CanvasBoardTouchSample.Phase.MOVE, 5f, 5f, onBoard = true)
        assertTrue(place.effects.none { it is CanvasFingerEffect.Pan })
    }

    private fun hypot(x: Float, y: Float): Float = kotlin.math.hypot(x, y)
}
