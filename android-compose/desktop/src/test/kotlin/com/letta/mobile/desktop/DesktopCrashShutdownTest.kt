package com.letta.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopCrashShutdownTest {
    @Test
    fun defersDialogAndRejectsNestedCrashesUntilExit() {
        val scheduled = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        val shutdown = DesktopCrashShutdown(scheduled::add) { events += "exit" }

        shutdown.request {
            events += "dialog"
            shutdown.request { events += "nested dialog" }
        }
        shutdown.request { events += "second dialog" }

        assertTrue(events.isEmpty(), "The exception handler must return before modal UI runs")
        assertEquals(1, scheduled.size)
        scheduled.single().invoke()
        assertEquals(listOf("dialog", "exit"), events)
    }

    @Test
    fun exitsEvenWhenShowingDialogFails() {
        val scheduled = mutableListOf<() -> Unit>()
        var exited = false
        val shutdown = DesktopCrashShutdown(scheduled::add) { exited = true }
        shutdown.request { error("Window disposed") }

        assertFailsWith<IllegalStateException> { scheduled.single().invoke() }
        assertTrue(exited)
    }
}
