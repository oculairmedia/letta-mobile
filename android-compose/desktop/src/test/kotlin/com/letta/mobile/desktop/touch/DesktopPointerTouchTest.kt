package com.letta.mobile.desktop.touch

import com.letta.mobile.desktop.input.FingerControlLatch
import com.letta.mobile.desktop.input.TabletBridge
import com.letta.mobile.desktop.input.isPenNib
import com.letta.mobile.desktop.input.keepsFingerOwnership
import com.letta.mobile.desktop.input.penDownWaitsForPose
import java.awt.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPointerTouchTest {

    @Test
    fun ownershipIsPerWindowAndReleaseClearsOnlyThatWindow() {
        val canvas = Frame("canvas")
        val other = Frame("other")
        try {
            DesktopPointerTouch.release(canvas)
            DesktopPointerTouch.release(other)
            assertFalse(DesktopPointerTouch.ownsFingers(canvas))
            DesktopPointerTouch.markOwned(canvas)
            assertTrue(DesktopPointerTouch.ownsFingers(canvas))
            assertFalse(DesktopPointerTouch.ownsFingers(other))
            DesktopPointerTouch.release(canvas)
            assertFalse(DesktopPointerTouch.ownsFingers(canvas))
        } finally {
            canvas.dispose()
            other.dispose()
        }
    }

    @Test
    fun aListScrollThatSlidesOntoThePanelDoesNotStop() {
        val latch = FingerControlLatch()
        assertFalse(latch.owns(TabletBridge.KIND_DOWN) { true })
        assertFalse(latch.owns(TabletBridge.KIND_MOVE) { false })
        assertFalse(latch.owns(TabletBridge.KIND_MOVE) { true })
        assertFalse(latch.owns(TabletBridge.KIND_UP) { true })
    }

    @Test
    fun aSliderDragStaysWithTheSliderAfterTheFingerSlidesOffIt() {
        val latch = FingerControlLatch()
        assertFalse(latch.owns(TabletBridge.KIND_DOWN) { false })
        assertTrue(latch.owns(TabletBridge.KIND_MOVE) { true })
        assertTrue(latch.owns(TabletBridge.KIND_MOVE) { false })
        assertTrue(latch.owns(TabletBridge.KIND_UP) { false })
    }

    @Test
    fun theStaleDownPoseDoesNotDecideWhetherTheFingerIsOnTheSlider() {
        val latch = FingerControlLatch()
        var asked = 0
        // The down sample is the previous pose. Only the first placed move is asked.
        latch.owns(TabletBridge.KIND_DOWN) { asked++; false }
        assertEquals(0, asked)
        assertTrue(latch.owns(TabletBridge.KIND_MOVE) { asked++; true })
        assertEquals(1, asked)
    }

    @Test
    fun aToolMenuSliderKeepsTheFingerDrag() {
        assertFalse(ownedFingerBlocksPointer(isTouch = true, ownsFingers = true, passthrough = true))
        assertTrue(ownedFingerBlocksPointer(isTouch = true, ownsFingers = true, passthrough = false))
        assertFalse(ownedFingerBlocksPointer(isTouch = false, ownsFingers = true, passthrough = false))
    }

    @Test
    fun aPenDownWithNoPoseIsNotAFinger() {
        assertTrue(penDownWaitsForPose(TabletBridge.TOOL_DRAW, TabletBridge.KIND_DOWN, TabletBridge.NO_PRESSURE))
        assertTrue(isPenNib(TabletBridge.TOOL_ERASER))
        assertFalse(penDownWaitsForPose(TabletBridge.TOOL_DRAW, TabletBridge.KIND_MOVE, 0.4f))
        assertFalse(penDownWaitsForPose(TabletBridge.TOOL_TOUCH, TabletBridge.KIND_DOWN, TabletBridge.NO_PRESSURE))
    }

    @Test
    fun aPressurelessPenSampleDoesNotClearFingerOwnership() {
        assertTrue(
            keepsFingerOwnership(
                tool = TabletBridge.TOOL_DRAW,
                kind = TabletBridge.KIND_MOVE,
                alreadyOwned = true,
            ),
        )
    }
}
