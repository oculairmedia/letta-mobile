package com.letta.mobile.desktop.touch

import com.letta.mobile.desktop.input.TabletBridge
import com.letta.mobile.desktop.input.keepsFingerOwnership
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
