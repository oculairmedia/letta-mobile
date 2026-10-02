package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CanvasDoubleTapTest {
    private var now = 1_000L
    private val taps = CanvasDoubleTap()

    private fun tap(x: Float, y: Float, mark: String?) {
        taps.down(x, y, now)
        now += 60
        taps.up(x + 1f, y, now, mark)
    }

    @Test
    fun aSecondQuickTapNearTheFirstHandsBackTheFirst() {
        tap(100f, 100f, "pen-1")
        now += 150
        assertEquals("pen-1", taps.down(104f, 102f, now)?.mark)
    }

    @Test
    fun aStrokeIsNotATap() {
        taps.down(100f, 100f, now)
        taps.move(160f, 100f)
        now += 60
        taps.up(160f, 100f, now, "pen-1")
        now += 100
        assertNull(taps.down(100f, 100f, now))
    }

    @Test
    fun aLateOrFarSecondTapIsAFirstTapAgain() {
        tap(100f, 100f, "pen-1")
        now += CanvasDoubleTap.DOUBLE_TAP_MILLIS + 1
        assertNull(taps.down(100f, 100f, now))

        tap(100f, 100f, "pen-2")
        now += 100
        assertNull(taps.down(200f, 100f, now))
    }

    @Test
    fun aHeldPressIsNotATap() {
        taps.down(100f, 100f, now)
        now += CanvasDoubleTap.TAP_MILLIS + 1
        taps.up(100f, 100f, now)
        now += 50
        assertNull(taps.down(100f, 100f, now))
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
    fun theSelectionBoxIsTheSameWhicheverWayTheFingerDragged() {
        assertEquals(Rect(50f, 20f, 150f, 80f), boxOf(Offset(150f, 20f), Offset(50f, 80f)))
    }
}
