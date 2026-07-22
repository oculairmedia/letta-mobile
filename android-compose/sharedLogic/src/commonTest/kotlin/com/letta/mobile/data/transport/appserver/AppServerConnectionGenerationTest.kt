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
    fun becomesReadyOnlyWhenBothSocketsAreOpen() {
        val generation = AppServerConnectionGeneration()
        generation.markConnecting()
        assertEquals(AppServerConnectionState.Connecting, generation.state.value)

        generation.onChannelOpen(AppServerChannel.Control)
        assertEquals(AppServerConnectionState.Connecting, generation.state.value)

        generation.onChannelOpen(AppServerChannel.Stream)
        assertEquals(AppServerConnectionState.Ready, generation.state.value)
    }

    @Test
    fun readinessIsIndependentOfSocketOpenOrder() {
        val generation = AppServerConnectionGeneration()
        // Stream opens before control (connect race).
        generation.onChannelOpen(AppServerChannel.Stream)
        assertEquals(AppServerConnectionState.Connecting, generation.state.value)
        generation.onChannelOpen(AppServerChannel.Control)
        assertEquals(AppServerConnectionState.Ready, generation.state.value)
    }

    @Test
    fun oneSocketFailureTearsDownGenerationExactlyOnce() {
        var teardownCount = 0
        val generation = AppServerConnectionGeneration(onTeardown = { teardownCount++ })
        generation.onChannelOpen(AppServerChannel.Control)
        generation.onChannelOpen(AppServerChannel.Stream)
        assertEquals(AppServerConnectionState.Ready, generation.state.value)

        generation.onChannelClosedOrFailed(terminal = false, reason = "stream dropped")
        val failed = assertIs<AppServerConnectionState.Failed>(generation.state.value)
        assertEquals(false, failed.terminal)
        assertEquals("stream dropped", failed.reason)
        assertEquals(1, teardownCount)

        // The sibling socket's later close is a no-op (teardown fires once).
        generation.onChannelClosedOrFailed(terminal = true, reason = "sibling cancelled")
        assertEquals(1, teardownCount)
        assertEquals(failed, generation.state.value)
    }

    @Test
    fun failureBeforeReadyIsStillTerminalForTheGeneration() {
        val generation = AppServerConnectionGeneration()
        generation.markConnecting()
        generation.onChannelOpen(AppServerChannel.Control)
        // Stream never opens; control fails as terminal (auth/policy).
        generation.onChannelClosedOrFailed(terminal = true, reason = "1008 policy")
        val failed = assertIs<AppServerConnectionState.Failed>(generation.state.value)
        assertTrue(failed.terminal)
    }

    @Test
    fun opensAreIgnoredAfterFailure() {
        val generation = AppServerConnectionGeneration()
        generation.onChannelOpen(AppServerChannel.Control)
        generation.onChannelClosedOrFailed(terminal = false, reason = "dropped")
        // A late open from a socket that had already connected must not resurrect readiness.
        generation.onChannelOpen(AppServerChannel.Stream)
        assertIs<AppServerConnectionState.Failed>(generation.state.value)
    }
}
