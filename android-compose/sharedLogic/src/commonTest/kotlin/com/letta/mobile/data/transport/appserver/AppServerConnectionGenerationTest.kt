package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppServerConnectionGenerationTest {
    @Test
    fun startsDisconnectedNotOptimisticallyConnected() {
        val generation = AppServerConnectionGeneration()
        assertEquals(AppServerConnectionState.Disconnected, generation.state.value)
        assertTrue(!generation.state.value.isReady)
    }

    @Test
    fun becomesReadyWhenSessionOpens() {
        val generation = AppServerConnectionGeneration()
        generation.markConnecting()
        assertEquals(AppServerConnectionState.Connecting, generation.state.value)

        generation.onSessionOpen()
        assertEquals(AppServerConnectionState.Ready, generation.state.value)
    }

    @Test
    fun sessionFailureTearsDownGenerationExactlyOnce() {
        var teardownCount = 0
        val generation = AppServerConnectionGeneration(onTeardown = { teardownCount++ })
        generation.onSessionOpen()
        assertEquals(AppServerConnectionState.Ready, generation.state.value)

        generation.onSessionClosedOrFailed(terminal = false, reason = "session dropped")
        val failed = assertIs<AppServerConnectionState.Failed>(generation.state.value)
        assertEquals(false, failed.terminal)
        assertEquals("session dropped", failed.reason)
        assertEquals(1, teardownCount)

        // A later close notification is a no-op (teardown fires once).
        generation.onSessionClosedOrFailed(terminal = true, reason = "duplicate close")
        assertEquals(1, teardownCount)
        assertEquals(failed, generation.state.value)
    }

    @Test
    fun failureBeforeReadyIsStillTerminalForTheGeneration() {
        val generation = AppServerConnectionGeneration()
        generation.markConnecting()
        generation.onSessionClosedOrFailed(terminal = true, reason = "1008 policy")
        val failed = assertIs<AppServerConnectionState.Failed>(generation.state.value)
        assertTrue(failed.terminal)
    }

    @Test
    fun opensAreIgnoredAfterFailure() {
        val generation = AppServerConnectionGeneration()
        generation.onSessionClosedOrFailed(terminal = false, reason = "dropped")
        // A late open must not resurrect readiness.
        generation.onSessionOpen()
        assertIs<AppServerConnectionState.Failed>(generation.state.value)
    }
}
