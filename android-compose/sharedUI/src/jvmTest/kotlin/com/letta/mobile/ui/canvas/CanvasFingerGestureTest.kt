package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasFingerGestureTest {

    private var now = 0L
    private fun gesture() = CanvasFingerGesture(clock = { now })

    private fun CanvasFingerGesture.down(id: Int, onBoard: Boolean = true) =
        offer(id, CanvasBoardTouchSample.Phase.DOWN, 0f, 0f, onBoard)

    private fun CanvasFingerGesture.move(id: Int, x: Float, y: Float, onBoard: Boolean = true) =
        offer(id, CanvasBoardTouchSample.Phase.MOVE, x, y, onBoard)

    private fun CanvasFingerGesture.up(id: Int, x: Float = 0f, y: Float = 0f) =
        offer(id, CanvasBoardTouchSample.Phase.UP, x, y, true)

    private fun List<CanvasBoardTouchOutcome>.pans() = flatMap { it.effects }.filterIsInstance<CanvasFingerEffect.Pan>()

    private fun CanvasFingerGesture.tapAt(x: Float, y: Float): CanvasBoardTouchOutcome {
        down(1)
        move(1, x, y)
        return up(1, x, y)
    }

    @Test
    fun oneFingerHoldsTheMouseFromWhereItLandedAndNeverPans() {
        val gesture = gesture()
        gesture.down(1)
        val place = gesture.move(1, 10f, 20f)
        assertIs<CanvasBoardTouchResult.Consumed>(place.result)

        now += CanvasFingerGesture.SECOND_FINGER_WAIT_MILLIS
        val press = assertIs<CanvasBoardTouchResult.Press>(gesture.move(1, 40f, 5f).result)
        assertEquals(CanvasBoardTouchResult.Press(10f, 20f, 40f, 5f), press)
        val drag = gesture.move(1, 60f, 5f)
        assertEquals(CanvasBoardTouchResult.Drag(60f, 5f), drag.result)

        val lift = gesture.up(1, 60f, 5f)
        assertIs<CanvasBoardTouchResult.Release>(lift.result)
        assertEquals("cursor", lift.effects.filterIsInstance<CanvasFingerEffect.End>().single().kind)
        assertTrue(listOf(place, drag, lift).pans().isEmpty())
    }

    @Test
    fun aQuickDragWaitsForASecondFingerBeforeHoldingTheMouse() {
        val gesture = gesture()
        gesture.down(1)
        gesture.move(1, 0f, 0f)
        now += 30
        val early = gesture.move(1, 40f, 0f)
        assertIs<CanvasBoardTouchResult.Consumed>(early.result)
        assertTrue(early.effects.isEmpty())

        gesture.down(2)
        gesture.move(2, 100f, 0f)
        now += 200
        val joined = gesture.move(1, 40f, 0f)
        assertTrue(joined.effects.single() is CanvasFingerEffect.Arm)
        val together = gesture.move(1, 40f, 30f)
        assertIs<CanvasBoardTouchResult.Consumed>(together.result)
        assertEquals(15f, together.effects.filterIsInstance<CanvasFingerEffect.Pan>().single().dy, 0.001f)
    }

    @Test
    fun aShortPressIsATapAndDoesNotPan() {
        val gesture = gesture()
        gesture.down(1)
        gesture.move(1, 4f, 6f)
        gesture.move(1, 6f, 7f)
        val lift = gesture.up(1, 6f, 7f)
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
    fun aSecondTapCloseByZoomsInInsteadOfClicking() {
        val gesture = gesture()
        assertIs<CanvasBoardTouchResult.Tap>(gesture.tapAt(50f, 50f).result)
        now += 200
        val second = gesture.tapAt(60f, 55f)
        assertIs<CanvasBoardTouchResult.Consumed>(second.result)
        assertEquals(CanvasFingerEffect.DoubleTap(60f, 55f), second.effects.filterIsInstance<CanvasFingerEffect.DoubleTap>().single())

        // A third tap starts over rather than zooming again.
        now += 100
        assertIs<CanvasBoardTouchResult.Tap>(gesture.tapAt(60f, 55f).result)
    }

    @Test
    fun aStillFingerBecomesALongPressThatAddsToTheSelection() {
        val gesture = gesture()
        gesture.down(1)
        assertTrue(gesture.mayLongPress())
        gesture.move(1, 50f, 60f)
        assertNull(gesture.longPress())
        now += CanvasFingerGesture.LONG_PRESS_MILLIS
        gesture.move(1, 53f, 62f)
        val held = assertNotNull(gesture.longPress())
        assertEquals(listOf<CanvasFingerEffect>(CanvasFingerEffect.LongPress(50f, 60f)), held)
        assertNull(gesture.longPress())

        val lift = gesture.up(1, 53f, 62f)
        assertIs<CanvasBoardTouchResult.Consumed>(lift.result)
        assertEquals("long-press", lift.effects.filterIsInstance<CanvasFingerEffect.End>().single().kind)
    }

    @Test
    fun dragAfterALongPressDrawsASelectionBoxInsteadOfTheTool() {
        val gesture = gesture()
        gesture.down(1)
        gesture.move(1, 50f, 60f)
        now += CanvasFingerGesture.LONG_PRESS_MILLIS
        assertNotNull(gesture.longPress())

        val drag = gesture.move(1, 150f, 160f)
        assertIs<CanvasBoardTouchResult.Consumed>(drag.result)
        val box = drag.effects.filterIsInstance<CanvasFingerEffect.Marquee>().single()
        assertEquals(CanvasFingerEffect.Marquee(50f, 60f, 150f, 160f, commit = false), box)

        val lift = gesture.up(1, 150f, 160f)
        val commit = lift.effects.filterIsInstance<CanvasFingerEffect.Marquee>().single()
        assertTrue(commit.commit)
        assertTrue(lift.result !is CanvasBoardTouchResult.Release)
    }

    @Test
    fun aMovingFingerNeverBecomesALongPress() {
        val gesture = gesture()
        gesture.down(1)
        gesture.move(1, 0f, 0f)
        now += CanvasFingerGesture.LONG_PRESS_MILLIS
        gesture.move(1, 80f, 0f)
        assertTrue(!gesture.mayLongPress())
        assertNull(gesture.longPress())
    }

    @Test
    fun theSelectionBoxIsTheSameWhicheverWayTheFingerDragged() {
        val box = boxOf(androidx.compose.ui.geometry.Offset(150f, 20f), androidx.compose.ui.geometry.Offset(50f, 80f))
        assertEquals(Rect(50f, 20f, 150f, 80f), box)
    }

    @Test
    fun aFingerWidensThePickToleranceOnlyForItsOwnPress() {
        val recency = CanvasFingerRecency()
        assertEquals(12f, recency.pickTolerance(1_000L).value)
        recency.touched(1_000L)
        assertEquals(FINGER_PICK_TOLERANCE, recency.pickTolerance(1_050L))
        assertEquals(12f, recency.pickTolerance(5_000L).value)
    }

    @Test
    fun aDoubleTapOnAShapeTypesIntoItAndOnOpenBoardZooms() {
        val binding = CanvasBoardTouchBinding()
        val zooms = mutableListOf<Float>()
        binding.animateZoom = { factor, _ -> zooms.add(factor) }
        val fling = io.ak1.drawbox.presentation.PanFling(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)) {}

        binding.doubleTap = { true }
        binding.apply(listOf(CanvasFingerEffect.DoubleTap(10f, 10f)), fling, 0L)
        assertTrue(zooms.isEmpty())

        binding.doubleTap = { false }
        binding.apply(listOf(CanvasFingerEffect.DoubleTap(10f, 10f)), fling, 0L)
        assertEquals(listOf(CanvasFingerGesture.DOUBLE_TAP_ZOOM), zooms)
    }

    @Test
    fun aLateOrDistantSecondTapIsAnotherClick() {
        val gesture = gesture()
        gesture.tapAt(50f, 50f)
        now += CanvasFingerGesture.DOUBLE_TAP_MILLIS + 1
        assertIs<CanvasBoardTouchResult.Tap>(gesture.tapAt(50f, 50f).result)
        now += 100
        assertIs<CanvasBoardTouchResult.Tap>(gesture.tapAt(300f, 50f).result)
    }

    @Test
    fun twoFingersPinchAndPanTheMidpoint() {
        val gesture = gesture()
        gesture.down(1)
        gesture.move(1, 0f, 0f)
        gesture.down(2)
        gesture.move(2, 100f, 0f)
        val armed = gesture.move(1, 0f, 0f)
        assertTrue(armed.effects.none { it is CanvasFingerEffect.Zoom })

        val spread = gesture.move(2, 200f, 40f)
        val zoom = spread.effects.filterIsInstance<CanvasFingerEffect.Zoom>().single()
        val pan = spread.effects.filterIsInstance<CanvasFingerEffect.Pan>().single()
        assertEquals(hypot(200f, 40f) / 100f, zoom.factor, 0.001f)
        assertEquals(50f, pan.dx, 0.001f)
        assertEquals(20f, pan.dy, 0.001f)
    }

    @Test
    fun aFingerLeftBehindByAPinchDoesNotStartDrawing() {
        val gesture = gesture()
        gesture.down(1)
        gesture.move(1, 0f, 0f)
        gesture.down(2)
        gesture.move(2, 100f, 0f)
        gesture.move(1, 0f, 0f)
        gesture.up(2, 100f, 0f)
        now += 500
        val alone = listOf(gesture.move(1, 80f, 80f), gesture.move(1, 160f, 160f))
        assertTrue(alone.none { it.result is CanvasBoardTouchResult.Press })
        assertTrue(alone.pans().isEmpty())
        val lift = gesture.up(1, 160f, 160f)
        assertTrue(lift.result !is CanvasBoardTouchResult.Tap)
    }

    @Test
    fun aSecondFingerWhileHoldingTheMouseDoesNotPinch() {
        val gesture = gesture()
        gesture.down(1)
        gesture.move(1, 0f, 0f)
        now += 200
        assertIs<CanvasBoardTouchResult.Press>(gesture.move(1, 40f, 0f).result)
        assertIs<CanvasBoardTouchResult.Consumed>(gesture.down(2).result)
        gesture.move(2, 200f, 0f)
        val rider = gesture.move(2, 300f, 0f)
        assertIs<CanvasBoardTouchResult.Consumed>(rider.result)
        assertTrue(rider.effects.isEmpty())
        assertEquals(CanvasBoardTouchResult.Drag(50f, 0f), gesture.move(1, 50f, 0f).result)
        assertIs<CanvasBoardTouchResult.Release>(gesture.up(1, 50f, 0f).result)
    }

    @Test
    fun cancellingTheMouseFingerLetsGo() {
        val gesture = gesture()
        gesture.down(1)
        gesture.move(1, 10f, 10f)
        now += 200
        assertIs<CanvasBoardTouchResult.Press>(gesture.move(1, 40f, 10f).result)

        val cancelled = gesture.offer(1, CanvasBoardTouchSample.Phase.CANCEL, 40f, 10f, onBoard = true)
        assertIs<CanvasBoardTouchResult.Release>(cancelled.result)
        assertTrue(cancelled.effects.none { it is CanvasFingerEffect.Pan })
    }

    @Test
    fun aMoveWithNoDownIsIgnored() {
        val move = gesture().move(7, 40f, 40f)
        assertIs<CanvasBoardTouchResult.Ignored>(move.result)
        assertTrue(move.effects.isEmpty())
    }

    @Test
    fun aFingerOffTheBoardIsLeftToOrdinaryScrolling() {
        val gesture = gesture()
        gesture.down(1, onBoard = false)
        now += 200
        val move = gesture.move(1, 80f, 10f, onBoard = false)
        assertIs<CanvasBoardTouchResult.Ignored>(move.result)
        assertTrue(move.effects.isEmpty())
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
    fun aTwoFingerDragOf300IsTheFullTravelAndDensityScalesTheBoard() {
        val gesture = gesture()
        gesture.down(1)
        gesture.move(1, 0f, 0f)
        gesture.down(2)
        gesture.move(2, 100f, 0f)
        gesture.move(1, 0f, 0f)
        val drag = listOf(gesture.move(1, 300f, 0f), gesture.move(2, 400f, 0f))
        assertEquals(300f, drag.pans().sumOf { it.dx.toDouble() }.toFloat(), 0.001f)
        val binding = CanvasBoardTouchBinding()
        binding.density = 1.5f
        val applied = mutableListOf<androidx.compose.ui.geometry.Offset>()
        binding.pan = { applied.add(it) }
        val fling = io.ak1.drawbox.presentation.PanFling(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)) {}
        drag.forEach { binding.apply(it.effects.filterIsInstance<CanvasFingerEffect.Pan>(), fling, 0L) }
        assertEquals(450f, applied.sumOf { it.x.toDouble() }.toFloat(), 0.01f)
    }

    @Test
    fun aSymmetricPinchDoesNotPanAndDoesNotFling() {
        val gesture = gesture()
        gesture.down(1)
        gesture.move(1, 0f, 0f)
        gesture.down(2)
        gesture.move(2, 100f, 0f)
        val joined = gesture.move(1, 0f, 0f)
        assertTrue(joined.effects.single() is CanvasFingerEffect.Arm)
        val left = gesture.move(1, -50f, 0f)
        val right = gesture.move(2, 150f, 0f)
        val panX = listOf(left, right).pans().sumOf { it.dx.toDouble() }
        assertEquals(0.0, panX, 0.5)
        val lift2 = gesture.up(2, 150f, 0f)
        val lift1 = gesture.up(1, -50f, 0f)
        val end = lift1.effects.filterIsInstance<CanvasFingerEffect.End>().single()
        assertEquals("pinch", end.kind)
        assertEquals(false, end.fling)
        assertTrue(lift2.effects.none { it is CanvasFingerEffect.Release })
        assertTrue(lift1.effects.none { it is CanvasFingerEffect.Release })
    }

    @Test
    fun aSecondFingerJoiningDoesNotPanOnThatFrame() {
        val gesture = gesture()
        gesture.down(1)
        gesture.move(1, 0f, 0f)
        gesture.move(1, 40f, 0f)
        gesture.down(2)
        gesture.move(2, 80f, 0f)
        val joined = gesture.move(1, 40f, 0f)
        assertTrue(joined.effects.single() is CanvasFingerEffect.Arm)
        assertTrue(joined.effects.none { it is CanvasFingerEffect.Pan })
    }

    @Test
    fun aRepeatedDownReplacesTheContact() {
        val gesture = gesture()
        gesture.down(1)
        gesture.move(1, 0f, 0f)
        gesture.down(1)
        val place = gesture.move(1, 5f, 5f)
        assertTrue(place.effects.none { it is CanvasFingerEffect.Pan })
        assertNull((place.result as? CanvasBoardTouchResult.Press))
    }

    private fun hypot(x: Float, y: Float): Float = kotlin.math.hypot(x, y)
}
