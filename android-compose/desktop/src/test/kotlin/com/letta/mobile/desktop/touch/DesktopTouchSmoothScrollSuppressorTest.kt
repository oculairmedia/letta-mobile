package com.letta.mobile.desktop.touch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Covers the save/restore state machine independently of AWT and of the real
 * `WindowsWinUIConfig` singleton, plus the reflective bind against a
 * stand-in and against the real Compose Foundation classpath.
 */
class DesktopTouchSmoothScrollSuppressorTest {

    private class RecordingSwitch : DesktopSmoothScrollSwitch {
        val calls = mutableListOf<Boolean>()
        override fun setEnabled(enabled: Boolean) {
            calls.add(enabled)
        }
    }

    @Test
    fun `begin disables and end restores`() {
        val switch = RecordingSwitch()
        val suppressor = DesktopTouchSmoothScrollSuppressor(switch)

        suppressor.begin()
        suppressor.end()

        assertEquals(listOf(false, true), switch.calls)
    }

    @Test
    fun `begin is idempotent across repeated drag samples`() {
        val switch = RecordingSwitch()
        val suppressor = DesktopTouchSmoothScrollSuppressor(switch)

        suppressor.begin()
        suppressor.begin()
        suppressor.begin()

        assertEquals(listOf(false), switch.calls)
    }

    @Test
    fun `end without a prior begin is a no-op`() {
        val switch = RecordingSwitch()
        val suppressor = DesktopTouchSmoothScrollSuppressor(switch)

        suppressor.end()

        assertEquals(emptyList(), switch.calls)
    }

    @Test
    fun `end is idempotent, so an interrupted gesture that ends twice restores only once`() {
        val switch = RecordingSwitch()
        val suppressor = DesktopTouchSmoothScrollSuppressor(switch)

        suppressor.begin()
        // e.g. a fling's own end-of-coast tick races a window-focus-loss
        // cancellation; both call end().
        suppressor.end()
        suppressor.end()

        assertEquals(listOf(false, true), switch.calls)
    }

    @Test
    fun `a new gesture after a full begin-end cycle suppresses again`() {
        val switch = RecordingSwitch()
        val suppressor = DesktopTouchSmoothScrollSuppressor(switch)

        suppressor.begin()
        suppressor.end()
        suppressor.begin()
        suppressor.end()

        assertEquals(listOf(false, true, false, true), switch.calls)
    }

    @Test
    fun `a null switch (bind failed) is a permanent no-op`() {
        val suppressor = DesktopTouchSmoothScrollSuppressor(switch = null)

        // Must not throw, and there is nothing to assert on since there is no
        // switch to record calls — the point is that scrolling stays laggy
        // rather than the app crashing.
        suppressor.begin()
        suppressor.end()
    }

    // --- DesktopSmoothScrollSwitch.bindOrNull ------------------------------

    @Test
    fun `binding against the real Compose Foundation classpath finds the setter`() {
        // Exercises the real reflective lookup against
        // androidx.compose.foundation.gestures.WindowsWinUIConfig, which is on
        // the desktop module's compile classpath. Flip the flag and flip it
        // straight back so this test does not leak global state into others
        // sharing the test JVM.
        val switch = assertNotNull(DesktopSmoothScrollSwitch.bindOrNull())
        switch.setEnabled(false)
        switch.setEnabled(true)
    }
}
