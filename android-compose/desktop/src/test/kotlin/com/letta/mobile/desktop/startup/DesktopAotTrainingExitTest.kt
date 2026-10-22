package com.letta.mobile.desktop.startup

import java.awt.event.WindowEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopAotTrainingExitTest {
    private val scheduled = mutableListOf<Long>()
    private var exits = 0

    private fun trainingExit(training: Boolean) = DesktopAotTrainingExit(
        training = training,
        schedule = { delayMs, action ->
            scheduled += delayMs
            action()
        },
        exit = { exits++ },
    )

    @Test
    fun aTrainingRunExitsOnceStartupHasSettledAfterItsFirstWindow() {
        val training = trainingExit(training = true)

        training.onWindowEvent(WindowEvent.WINDOW_ACTIVATED)
        training.onWindowEvent(WindowEvent.WINDOW_OPENED)
        training.onWindowEvent(WindowEvent.WINDOW_OPENED)

        assertEquals(listOf(DesktopAotTrainingExit.SETTLE_MS), scheduled, "one exit, after the settle time")
        assertEquals(1, exits)
    }

    @Test
    fun aNormalLaunchNeverExits() {
        val normal = trainingExit(training = false)

        normal.install()
        normal.onWindowEvent(WindowEvent.WINDOW_OPENED)

        assertTrue(scheduled.isEmpty())
        assertEquals(0, exits)
    }
}
