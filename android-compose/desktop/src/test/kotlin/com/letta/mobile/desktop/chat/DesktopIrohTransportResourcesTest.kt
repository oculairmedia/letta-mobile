package com.letta.mobile.desktop.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [DesktopIrohTransportResources] owns the (endpoint, transport) pair the
 * desktop App Server factory dials at wiring time. Uses the internal lambda
 * constructor so teardown ordering and failure isolation are testable without
 * a live iroh endpoint.
 */
class DesktopIrohTransportResourcesTest {

    @Test
    fun close_tearsDownInOrder_transportThenShutdownThenClose() {
        val calls = mutableListOf<String>()
        val resources = DesktopIrohTransportResources(
            closeTransport = { calls += "transport" },
            shutdownEndpoint = { calls += "shutdown" },
            closeEndpoint = { calls += "close" },
        )

        resources.close()

        assertEquals(listOf("transport", "shutdown", "close"), calls)
    }

    @Test
    fun close_isIdempotent_eachThunkRunsExactlyOnce() {
        var transportCalls = 0
        var shutdownCalls = 0
        var closeCalls = 0
        val resources = DesktopIrohTransportResources(
            closeTransport = { transportCalls++ },
            shutdownEndpoint = { shutdownCalls++ },
            closeEndpoint = { closeCalls++ },
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
        val resources = DesktopIrohTransportResources(
            closeTransport = { error("transport close failed") },
            shutdownEndpoint = { shutdownCalled = true },
            closeEndpoint = { closeCalled = true },
        )

        // close() itself must not throw even though closeTransport does.
        resources.close()

        assert(shutdownCalled) { "endpoint shutdown must still run after a transport close failure" }
        assert(closeCalled) { "endpoint close must still run after a transport close failure" }
    }

    @Test
    fun close_survivesShutdownFailure_closeStillRuns() {
        var closeCalled = false
        val resources = DesktopIrohTransportResources(
            closeTransport = {},
            shutdownEndpoint = { error("shutdown failed") },
            closeEndpoint = { closeCalled = true },
        )

        resources.close()

        assert(closeCalled) { "endpoint close must still run after a shutdown failure" }
    }
}
