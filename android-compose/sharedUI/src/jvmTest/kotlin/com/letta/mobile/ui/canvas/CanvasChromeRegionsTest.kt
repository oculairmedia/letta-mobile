package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pen is offered board events by position, not by hit testing, so the board has to know where
 * its own controls are. Without that, a freehand tool took the pen's presses over the tool rail as
 * ink and the rail stopped responding - to the pen holding that tool, and to nothing else.
 */
class CanvasChromeRegionsTest {

    @Test
    fun aPointOverAControlIsNotDrawingSurface() {
        val regions = CanvasChromeRegions()
        regions.register { Rect(0f, 100f, 72f, 600f) }

        assertTrue(regions.contains(Offset(40f, 300f)))
    }

    @Test
    fun aPointOnTheOpenBoardIsDrawingSurface() {
        val regions = CanvasChromeRegions()
        regions.register { Rect(0f, 100f, 72f, 600f) }

        assertFalse(regions.contains(Offset(400f, 300f)))
    }

    @Test
    fun anyRegisteredControlCounts() {
        val regions = CanvasChromeRegions()
        regions.register { Rect(0f, 100f, 72f, 600f) }
        regions.register { Rect(300f, 0f, 700f, 48f) }

        assertTrue(regions.contains(Offset(500f, 20f)))
    }

    @Test
    fun aControlThatHasGoneNoLongerCounts() {
        // Bars come and go with a selection; a stale rectangle would leave a dead patch of board.
        val regions = CanvasChromeRegions()
        val unregister = regions.register { Rect(0f, 100f, 72f, 600f) }

        unregister()

        assertFalse(regions.contains(Offset(40f, 300f)))
    }

    @Test
    fun aControlThatHasNotBeenPlacedYetCountsAsNothing() {
        // Bounds are read through a provider, which is null until the control is laid out.
        val regions = CanvasChromeRegions()
        regions.register { null }

        assertFalse(regions.contains(Offset(40f, 300f)))
    }

    @Test
    fun aControlThatMovedIsCountedWhereItIsNow() {
        // The provider is read per query on purpose: a bar that moves or grows must not need
        // re-registering, or it protects the place it used to be.
        val regions = CanvasChromeRegions()
        var bounds = Rect(0f, 0f, 72f, 100f)
        regions.register { bounds }

        bounds = Rect(0f, 500f, 72f, 600f)

        assertFalse(regions.contains(Offset(40f, 50f)))
        assertTrue(regions.contains(Offset(40f, 550f)))
    }
}
