package com.letta.mobile.desktop.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * A restarted bundled child restarts the desktop's running node whichever backend the window shows: a generation left
 * running over the old child's port and token would be reused on the next return to LOCAL.
 */
class DesktopRuntimeRestartResponderTest {
    private val events = mutableListOf<String>()

    private fun responder(nodeRunning: Boolean) = DesktopRuntimeRestartResponder(
        nodeRunning = { nodeRunning },
        handOff = { reconnect ->
            events += "bridge"
            reconnect()
            events += "bridge-released"
        },
        restartNode = { events += "restart" },
    )

    @Test
    fun aRunningNodeIsRestartedThroughTheBridgeLeaseWithoutAskingWhichBackendIsShown() = runTest {
        responder(nodeRunning = true).onRuntimeRestarted()

        assertEquals(listOf("bridge", "restart", "bridge-released"), events)
    }

    @Test
    fun withNoGenerationRunningTheRestartStillRunsButNothingIsBridged() = runTest {
        responder(nodeRunning = false).onRuntimeRestarted()

        assertEquals(listOf("restart"), events, "a generation a dial is starting now is ended; no shell needs a bridge")
    }
}
