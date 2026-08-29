package com.letta.mobile.desktop.touch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        val configClass = Class.forName(
            "androidx.compose.foundation.gestures.WindowsWinUIConfig",
            false,
            javaClass.classLoader,
        )
        assertTrue(
            configClass.methods.any { method ->
                method.name.startsWith("setSmoothScrollingEnabled") &&
                    method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
            },
        )

        // Initializing the Windows singleton on a non-Windows host starts an
        // AWT thread that outlives the test and prevents Gradle's worker from
        // exiting. The classpath contract above is platform-neutral; exercise
        // the live singleton and restore its global flag only on Windows.
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val switch = assertNotNull(DesktopSmoothScrollSwitch.bindOrNull())
        switch.setEnabled(false)
        switch.setEnabled(true)
    }
}
