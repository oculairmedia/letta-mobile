package com.letta.mobile.desktop.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation

/**
 * [DesktopTransportResources] owns either the (endpoint, transport) pair or
 * the (WS transport, HttpClient) pair the desktop App Server factory wires at
 * create() time. Uses the internal list-of-thunks constructor so teardown
 * ordering, failure isolation, and the per-step timeout are testable without
 * a live iroh endpoint or WebSocket.
 */
class DesktopTransportResourcesTest {

    @Test
    fun close_tearsDownInOrder_transportThenShutdownThenClose() {
        val calls = mutableListOf<String>()
        val resources = DesktopTransportResources(
            teardownSteps = listOf(
                { calls += "transport" },
                { calls += "shutdown" },
                { calls += "close" },
            ),
        )

        resources.close()

        assertEquals(listOf("transport", "shutdown", "close"), calls)
    }

    @Test
    fun close_isIdempotent_eachThunkRunsExactlyOnce() {
        var transportCalls = 0
        var shutdownCalls = 0
        var closeCalls = 0
        val resources = DesktopTransportResources(
            teardownSteps = listOf(
                { transportCalls++ },
                { shutdownCalls++ },
                { closeCalls++ },
            ),
        )

        resources.close()
        resources.close()

        assertEquals(1, transportCalls)
        assertEquals(1, shutdownCalls)
        assertEquals(1, closeCalls)
    }

    @Test
    fun close_survivesTransportCloseFailure_endpointStillTornDown() {
        var shutdownCalled = false
        var closeCalled = false
        val resources = DesktopTransportResources(
            teardownSteps = listOf(
                { error("transport close failed") },
                { shutdownCalled = true },
                { closeCalled = true },
            ),
        )

        // close() itself must not throw even though the first step does.
        resources.close()

        assert(shutdownCalled) { "endpoint shutdown must still run after a transport close failure" }
        assert(closeCalled) { "endpoint close must still run after a transport close failure" }
    }

    @Test
    fun close_survivesShutdownFailure_closeStillRuns() {
        var closeCalled = false
        val resources = DesktopTransportResources(
            teardownSteps = listOf(
                {},
                { error("shutdown failed") },
                { closeCalled = true },
            ),
        )

        resources.close()

        assert(closeCalled) { "endpoint close must still run after a shutdown failure" }
    }

    @Test
    fun close_hungStepIsCutOffAtTimeout_laterStepsStillRun() {
        var shutdownRan = false
        var closeRan = false
        val resources = DesktopTransportResources(
            teardownSteps = listOf(
                { awaitCancellation() },
                { shutdownRan = true },
                { closeRan = true },
            ),
            stepTimeoutMs = 50L,
        )

        resources.close()

        assertTrue(shutdownRan)
        assertTrue(closeRan)
    }
}
