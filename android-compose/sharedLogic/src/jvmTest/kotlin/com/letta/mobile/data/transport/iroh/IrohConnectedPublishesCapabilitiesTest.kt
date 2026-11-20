package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ChannelTransportState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * A published `Connected` always has its host capabilities: a collector that reads them the instant it sees the
 * state (as the shared shell state does on each connection change) must never find them empty, on the first
 * connection or on a reconnect.
 */
class IrohConnectedPublishesCapabilitiesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val config = IrohConnectConfig(baseShimUrl = "iroh://ticket", token = "", deviceId = "device", clientVersion = "test")

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun theFirstConnectedValueOfEveryConnectionAlreadyAdvertisesTheHostCapabilities() = runBlocking {
        val transport = IrohChannelTransport(
            scope = scope,
            activeConfigProvider = { config },
            testDialer = { dialConfig ->
                IrohConnectionHandle(
                    config = dialConfig, ticket = "ticket", sessionId = "session",
                    serverCapabilities = setOf(CAPABILITY), close = {},
                )
            },
        )
        val seen = CopyOnWriteArrayList<Set<String>>()
        // Unconfined: the collector runs inside the state assignment, so it sees exactly what a reader sees then.
        val collector = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            transport.state.collect { if (it is ChannelTransportState.Connected) seen += transport.hostCapabilities }
        }
        try {
            transport.connect("iroh://ticket", "", "device", "test")
            withTimeout(5.seconds) { transport.state.first { it is ChannelTransportState.Connected } }
            transport.disconnect()
            transport.connect("iroh://ticket", "", "device", "test")
            withTimeout(5.seconds) { while (seen.size < CONNECTIONS) kotlinx.coroutines.yield() }
            assertEquals(List(seen.size) { setOf(CAPABILITY) }, seen.toList(), "every Connected carried its capabilities")
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    private companion object {
        const val CAPABILITY = "shell_state_v1"
        const val CONNECTIONS = 2
    }
}
