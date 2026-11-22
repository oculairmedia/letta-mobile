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
import kotlin.time.Duration.Companion.seconds

/**
 * TEMPORARY ordering repro (never committed): an earlier transport test whose owner scope ends without
 * disconnect/close (what runTest-scoped transport tests do), then the lifecycle test in the same JVM.
 */
class ZzForeignTrafficReproTest {
    @Test
    fun lifecycleScenarioAfterLeakedTransportTest() {
        leakTransport()
        val test = TelemetryLifecycleTest()
        repeat(ITERATIONS) { test.existingCollectorsFollowReplacementAndObsoleteCloseCannotDetachNewOwner() }
    }

    private fun leakTransport() = runBlocking {
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
        val deadline = System.nanoTime() + 2_000_000_000L
        while (transport.isLivenessProbeArmed && System.nanoTime() < deadline) delay(5)
        println("REPRO probeStillArmedAfterOwnerEnded=${transport.isLivenessProbeArmed}")
    }

    private companion object { const val ITERATIONS = 300 }
}
