package com.letta.mobile.data.transport.teardown

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runInterruptible

/**
 * [TransportTeardown] releases a transport's resources in order, once, isolating failures and
 * capping each step, without blocking the caller. Steps are thunks, so this needs no live iroh
 * endpoint or WebSocket.
 */
class TransportTeardownTest {
    /** Runs the teardown on the calling thread, so the ordering tests read its effects at once. */
    private val inline = Executor { it.run() }

    @Test
    fun close_returnsBeforeAHungTeardownFinishes_andTheTeardownStillCompletes() {
        val release = CountDownLatch(1)
        var closeRan = false
        val resources = TransportTeardown(
            steps = listOf(
                { runInterruptible { release.await() } },
                { closeRan = true },
            ),
        )

        val started = System.nanoTime()
        val teardown = resources.closeAsync()
        val returnedAfterMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(returnedAfterMs < 1_000, "close blocked its caller for $returnedAfterMs ms")
        assertFalse(teardown.isDone)
        release.countDown()
        teardown.get(5, TimeUnit.SECONDS)
        assertTrue(closeRan)
    }

    @Test
    fun close_tearsDownInOrder_transportThenShutdownThenClose() {
        val calls = mutableListOf<String>()
        val resources = TransportTeardown(
            steps = listOf(
                { calls += "transport" },
                { calls += "shutdown" },
                { calls += "close" },
            ),
            teardown = inline,
        )

        resources.close()

        assertEquals(listOf("transport", "shutdown", "close"), calls)
    }

    @Test
    fun close_isIdempotent_eachThunkRunsExactlyOnce() {
        var transportCalls = 0
        var shutdownCalls = 0
        var closeCalls = 0
        val resources = TransportTeardown(
            steps = listOf(
                { transportCalls++ },
                { shutdownCalls++ },
                { closeCalls++ },
            ),
            teardown = inline,
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
        val resources = TransportTeardown(
            steps = listOf(
                { error("transport close failed") },
                { shutdownCalled = true },
                { closeCalled = true },
            ),
            teardown = inline,
        )

        // close() itself must not throw even though the first step does.
        resources.close()

        assert(shutdownCalled) { "endpoint shutdown must still run after a transport close failure" }
        assert(closeCalled) { "endpoint close must still run after a transport close failure" }
    }

    @Test
    fun close_survivesShutdownFailure_closeStillRuns() {
        var closeCalled = false
        val resources = TransportTeardown(
            steps = listOf(
                {},
                { error("shutdown failed") },
                { closeCalled = true },
            ),
            teardown = inline,
        )

        resources.close()

        assert(closeCalled) { "endpoint close must still run after a shutdown failure" }
    }

    @Test
    fun close_hungStepIsCutOffAtTimeout_laterStepsStillRun() {
        var shutdownRan = false
        var closeRan = false
        val resources = TransportTeardown(
            steps = listOf(
                { awaitCancellation() },
                { shutdownRan = true },
                { closeRan = true },
            ),
            stepTimeoutMs = 50L,
            teardown = inline,
        )

        resources.close()

        assertTrue(shutdownRan)
        assertTrue(closeRan)
    }
}
