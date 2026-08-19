package com.letta.mobile.desktop.touch

import java.awt.Component
import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The touch keyboard must be invisible to everyone who is not using a finger,
 * so the origin tracker and the session gate carry the whole policy and are
 * tested directly.
 */
class DesktopTouchKeyboardGateTest {

    private class RecordingKeyboard : DesktopTouchKeyboardController {
        var shows = 0
        var hides = 0
        override fun show() { shows++ }
        override fun hide() { hides++ }
    }

    @Test
    fun `a text session started right after a tap raises and dismisses the keyboard`() {
        val origin = DesktopTouchOriginTracker()
        val keyboard = RecordingKeyboard()
        var now = 1_000L
        val gate = DesktopTouchKeyboardSessionGate(keyboard, origin) { now }

        origin.record(isTouch = true, atMillis = 990L)
        val raised = gate.begin()
        assertTrue(raised)
        assertEquals(1, keyboard.shows)

        now = 20_000L
        gate.end(raised)
        assertEquals(1, keyboard.hides)
    }

    @Test
    fun `a mouse click never raises the keyboard`() {
        val origin = DesktopTouchOriginTracker()
        val keyboard = RecordingKeyboard()
        val gate = DesktopTouchKeyboardSessionGate(keyboard, origin) { 1_000L }

        origin.record(isTouch = false, atMillis = 995L)
        assertFalse(gate.begin())
        gate.end(raised = false)
        assertEquals(0, keyboard.shows)
        assertEquals(0, keyboard.hides)
    }

    @Test
    fun `a stale tap does not raise the keyboard for a later Tab or programmatic focus`() {
        val origin = DesktopTouchOriginTracker(recencyWindowMillis = 3_000)
        val keyboard = RecordingKeyboard()
        val gate = DesktopTouchKeyboardSessionGate(keyboard, origin) { 60_000L }

        origin.record(isTouch = true, atMillis = 1_000L)
        assertFalse(gate.begin())
        assertEquals(0, keyboard.shows)
    }

    @Test
    fun `an untouched tracker reports no touch, so a degraded accessor never shows the keyboard`() {
        // This is the fallback path: with no --add-opens the AWT shim never
        // installs, nothing is ever recorded, and the app keeps its old
        // behaviour instead of flashing TabTip at mouse users.
        val origin = DesktopTouchOriginTracker()
        assertFalse(origin.wasTouch(nowMillis = System.currentTimeMillis()))
    }

    @Test
    fun `the most recent pointer wins`() {
        val origin = DesktopTouchOriginTracker()
        origin.record(isTouch = true, atMillis = 1_000L)
        origin.record(isTouch = false, atMillis = 1_100L)
        assertFalse(origin.wasTouch(nowMillis = 1_200L))
        origin.record(isTouch = true, atMillis = 1_300L)
        assertTrue(origin.wasTouch(nowMillis = 1_400L))
    }

    @Test
    fun `the unavailable accessor classifies nothing as touch`() {
        val event = MouseEvent(
            HeadlessComponent(),
            MouseEvent.MOUSE_PRESSED,
            0L,
            0,
            0,
            0,
            1,
            false,
        )
        assertFalse(DesktopTouchEventAccessor.Unavailable.isCausedByTouchEvent(event))
    }

    @Test
    fun `binding the reflective accessor never throws, whatever the JDK allows`() {
        // On a JDK/launcher without --add-opens java.desktop/sun.awt this
        // returns null and the caller falls back; it must never propagate.
        val accessor = DesktopTouchEventAccessor.bindOrNull()
        if (accessor != null) {
            val event = MouseEvent(
                HeadlessComponent(),
                MouseEvent.MOUSE_PRESSED,
                0L,
                0,
                0,
                0,
                1,
                false,
            )
            assertFalse(accessor.isCausedByTouchEvent(event))
        }
    }

    /** `java.awt.Component` itself has no headless check, unlike its widgets. */
    private class HeadlessComponent : Component()

    // --- DesktopJdkTouchKeyboardAccessor -----------------------------------

    /** Exposes the two method names WToolkit is expected to carry, but throws from both. */
    @Suppress("unused")
    private class FailingStandInToolkit {
        fun showTouchKeyboard(show: Boolean) {
            throw IllegalStateException("boom: $show")
        }

        fun hideTouchKeyboard() {
            throw IllegalStateException("boom")
        }
    }

    @Test
    fun `binding against a stand-in with matching methods succeeds, and invoke failures never throw`() {
        val controller = assertNotNull(DesktopJdkTouchKeyboardAccessor.bindOrNull(FailingStandInToolkit()))
        // Both natives throw on this stand-in; the wrapper must swallow that.
        controller.show()
        controller.hide()
    }

    @Test
    fun `binding against an object without the expected methods returns null, never throws`() {
        assertEquals(null, DesktopJdkTouchKeyboardAccessor.bindOrNull(Any()))
    }

    @Test
    fun `binding against the live AWT toolkit never throws`() {
        // Exercises the real lookup path without ever invoking show or hide,
        // so this never pops a real keyboard on a CI machine.
        DesktopJdkTouchKeyboardAccessor.bindOrNull()
    }
}
