package com.letta.mobile.desktop.touch

import java.awt.Component
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fingers reach Compose as touch, so AWT's own copy of each one must go, or the board pans on
 * its wheel steps and jumps under the finger. A real mouse and wheel must still get through.
 */
class DesktopTouchEchoFilterTest {
    /** `java.awt.Component` itself has no headless check, unlike its widgets. */
    private class Plain : Component()

    private val source = Plain()

    private fun wheel(scrollType: Int) = MouseWheelEvent(source, MouseEvent.MOUSE_WHEEL, 0L, 0, 10, 10, 0, false, scrollType, 1, 1)

    private fun press() = MouseEvent(source, MouseEvent.MOUSE_PRESSED, 0L, 0, 10, 10, 1, false, MouseEvent.BUTTON1)

    @Test
    fun aTouchPanWheelStepIsAnEcho() {
        DesktopTouchEchoFilter.TOUCH_PAN_SCROLL_TYPES.forEach { type ->
            assertTrue(DesktopTouchEchoFilter.isEcho(wheel(type), touchCaused = null), "scroll type $type")
        }
    }

    @Test
    fun aRealWheelIsNot() {
        assertFalse(DesktopTouchEchoFilter.isEcho(wheel(MouseWheelEvent.WHEEL_UNIT_SCROLL), touchCaused = { false }))
        assertFalse(DesktopTouchEchoFilter.isEcho(wheel(MouseWheelEvent.WHEEL_BLOCK_SCROLL), touchCaused = null))
    }

    @Test
    fun aTouchCausedMousePressIsAnEchoAndAMouseClickIsNot() {
        assertTrue(DesktopTouchEchoFilter.isEcho(press(), touchCaused = { true }))
        assertFalse(DesktopTouchEchoFilter.isEcho(press(), touchCaused = { false }))
        assertFalse(DesktopTouchEchoFilter.isEcho(press(), touchCaused = null))
    }
}
