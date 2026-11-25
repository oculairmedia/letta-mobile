package com.letta.mobile.util

import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohConnectConfig
import com.letta.mobile.data.transport.iroh.IrohConnectionHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/**
 * An earlier transport whose owner scope ends without disconnect()/close() (what runTest-scoped
 * transport tests do) must not keep writing liveness telemetry into whichever history a later test
 * installs. Runs the lifecycle scenario in the same JVM after abandoning such a transport.
 */
class TelemetryLifecycleAfterAbandonedTransportTest {
    @Test
    fun lifecycleScenarioAfterAnAbandonedTransport() {
        abandonTransport()
        val test = TelemetryLifecycleTest()
        repeat(ITERATIONS) { test.existingCollectorsFollowReplacementAndObsoleteCloseCannotDetachNewOwner() }
    }

    private fun abandonTransport() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val config = IrohConnectConfig("iroh://ticket", "token", "device", "client")
        val transport = IrohChannelTransport(
            scope = owner,
            activeConfigProvider = { config },
            livenessProbeIntervalMs = 1,
            livenessProbeTimeoutMs = 1,
            testDialer = {
                IrohConnectionHandle(
                    config = config, ticket = "ticket", sessionId = "session",
                    adminRpcCall = { method, _, _ ->
                        if (method == "health.check") error("unexpected $method")
                        AppServerInboundFrame.AdminRpcResponse(method, true, null, null)
                    },
                    close = {},
                )
            },
        )
        transport.connect("iroh://ticket", "token", "device", "client")
        withTimeout(5.seconds) {
            while (transport.state.value !is ChannelTransportState.Connected) delay(5)
        }
        // The owner's lifecycle ends; the test never calls disconnect()/close().
        owner.coroutineContext[Job]!!.cancel()
        // Disarm is not quiescence: [IrohLivenessProbe.isArmed] turns false the instant
        // cancel() bites the job, but the loop's already-running tail still executes its
        // non-suspending remainder on its IO thread (up to one last probe classification
        // writing a Telemetry event into whichever recorder is installed by then). That
        // straggler write landing inside the scenario's `first` ring below was the
        // intermittent CI flake asserting at TelemetryLifecycleTest.kt:83 — the
        // disarm-only gate passed while the write was still in flight. Join the
        // transport's own job instead: when join() returns, every transport coroutine —
        // the liveness loop included — has finished executing, so no late write can land
        // in any history the scenario installs. The assertFalse keeps the #2631 contract
        // that the loop cannot outlive the transport's owner.
        transport.transportJob.join()
        assertFalse(transport.isLivenessProbeArmed, "the liveness loop outlived the transport's owner")
    }

    private companion object { const val ITERATIONS = 300 }
}
