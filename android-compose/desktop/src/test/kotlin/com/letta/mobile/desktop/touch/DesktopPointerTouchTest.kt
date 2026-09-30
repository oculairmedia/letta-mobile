package com.letta.mobile.desktop.touch

import com.letta.mobile.desktop.input.FingerControlLatch
import com.letta.mobile.desktop.input.TabletBridge
import com.letta.mobile.desktop.input.isPenNib
import com.letta.mobile.desktop.input.keepsFingerOwnership
import com.letta.mobile.desktop.input.penDownWaitsForPose
import java.awt.Frame
import kotlin.test.Test
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
        assertFalse(latch.owns(TabletBridge.KIND_DOWN, pressedOnControl = false))
        assertFalse(latch.owns(TabletBridge.KIND_MOVE, pressedOnControl = true))
        assertFalse(latch.owns(TabletBridge.KIND_UP, pressedOnControl = true))
    }

    @Test
    fun aSliderDragStaysWithTheSliderAfterTheFingerSlidesOffIt() {
        val latch = FingerControlLatch()
        assertTrue(latch.owns(TabletBridge.KIND_DOWN, pressedOnControl = true))
        assertTrue(latch.owns(TabletBridge.KIND_MOVE, pressedOnControl = false))
        assertTrue(latch.owns(TabletBridge.KIND_UP, pressedOnControl = false))
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
