package com.letta.mobile.ui.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CanvasPenDoubleTapTest {

    private var now = 1_000L
    private val taps = CanvasPenDoubleTap(clock = { now })

    private fun tap(x: Float, y: Float, dot: String?) {
        taps.down(x, y)
        now += 60
        taps.up(x + 1f, y, dot)
    }

    @Test
    fun aSecondQuickTapNearTheFirstHandsBackTheFirstDot() {
        tap(100f, 100f, "pen-1")
        now += 150
        val first = taps.down(104f, 102f)
        assertEquals("pen-1", first?.dotId)
    }

    @Test
    fun aStrokeIsNotATap() {
        taps.down(100f, 100f)
        taps.move(160f, 100f)
        now += 60
        taps.up(160f, 100f, "pen-1")
        now += 100
        assertNull(taps.down(100f, 100f))
    }

    @Test
    fun aLateOrFarSecondTapDrawsAsUsual() {
        tap(100f, 100f, "pen-1")
        now += CanvasPenDoubleTap.DOUBLE_TAP_MILLIS + 1
        assertNull(taps.down(100f, 100f))

        tap(100f, 100f, "pen-2")
        now += 100
        assertNull(taps.down(200f, 100f))
    }

    @Test
    fun aThirdTapStartsOver() {
        tap(100f, 100f, "pen-1")
        now += 100
        taps.down(100f, 100f)
        now += 60
        taps.up(100f, 100f, null)
        now += 100
        // The second tap's up counts as a tap of its own only after a fresh down; the pair is spent.
        assertEquals(null, taps.down(100f, 100f)?.dotId)
    }
}
