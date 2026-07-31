package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ChatConnectionState
import com.letta.mobile.data.chat.runtime.ConnectionStatusGateway
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-wxy4s (desktop surfacing + auto-recovery).
 *
 * During the 2026-07-31 incident the desktop app rendered cached conversations
 * over a dead QUIC connection for ~40 minutes with no indication anything was
 * wrong, and recovered only when the user restarted it manually. The transport
 * fix makes the drop OBSERVABLE; these tests cover what the controller must then
 * DO with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChatControllerConnectionLossTest {

    /** A gateway that can report (and be driven through) transport states. */
    private class ConnectionAwareFakeGateway :
        FakeDesktopChatGateway(),
        ConnectionStatusGateway {
        val states = MutableStateFlow<ChannelTransportState>(connected())
        override val connectionState: StateFlow<ChannelTransportState> = states
    }

    @Test
    fun transportDropSurfacesAsStreamDisconnected() = runTest {
        val gateway = ConnectionAwareFakeGateway()
        val controller = testController { gateway }
        controller.start()
        runCurrent()
        assertEquals(ChatConnectionState.Live, controller.state.value.connectionState)

        gateway.states.value = degraded()
        runCurrent()

        assertEquals(
            ChatConnectionState.StreamDisconnected,
            controller.state.value.connectionState,
            "a lost connection must be visible in the UI, never silently backed by cached data",
        )
        assertTrue(
            controller.state.value.statusMessage?.contains("Reconnect", ignoreCase = true) == true,
            "a redial in progress must read as reconnecting, not as a hard failure; " +
                "statusMessage=${controller.state.value.statusMessage}",
        )
        controller.close()
    }

    @Test
    fun reconnectRehydratesWithoutRebuildingTheGateway() = runTest {
        val gateway = ConnectionAwareFakeGateway()
        var gatewayBuilds = 0
        val controller = testController { gatewayBuilds += 1; gateway }
        controller.start()
        runCurrent()
        val listCallsBefore = gateway.conversationMessageRequests.size

        gateway.states.value = degraded()
        runCurrent()
        gateway.states.value = connected()
        runCurrent()

        assertEquals(
            ChatConnectionState.Live,
            controller.state.value.connectionState,
            "the redial must clear the disconnected state",
        )
        assertTrue(
            gateway.conversationMessageRequests.size > listCallsBefore,
            "the open conversation must be re-hydrated after the redial (frames were missed " +
                "during the dead window)",
        )
        assertEquals(
            1, gatewayBuilds,
            "recovery must NOT rebuild the gateway: that closes the transport and destroys the " +
                "supervisor plus the healthy connection it just redialed",
        )
        controller.close()
    }

    @Test
    fun authFailureIsTerminalAndNotDressedUpAsReconnecting() = runTest {
        val gateway = ConnectionAwareFakeGateway()
        val controller = testController { gateway }
        controller.start()
        runCurrent()

        gateway.states.value = ChannelTransportState.Disconnected(
            code = 4001,
            reason = "Iroh auth failed",
            isAuthFailure = true,
            willReconnect = false,
        )
        runCurrent()

        assertEquals(
            ChatConnectionState.Offline,
            controller.state.value.connectionState,
            "the supervisor stops redialing on auth failure, so there is nothing to auto-recover",
        )
        controller.close()
    }

    @Test
    fun sustainedDisconnectEscalatesOnceAndDoesNotLoop() = runTest {
        val gateway = ConnectionAwareFakeGateway()
        var gatewayBuilds = 0
        val controller = testController { gatewayBuilds += 1; gateway }
        controller.start()
        runCurrent()
        assertEquals(1, gatewayBuilds)

        gateway.states.value = degraded()
        runCurrent()
        // Well past the escalation threshold, and then well past it again: the
        // heavy rebuild must fire ONCE, never on a loop.
        advanceTimeBy(200_000)
        runCurrent()

        assertEquals(
            2, gatewayBuilds,
            "a connection down past the escalation threshold rebuilds the gateway exactly once",
        )
        controller.close()
    }

    private fun TestScope.testController(
        gatewayFactory: suspend () -> DesktopChatGateway,
    ): DesktopChatController = DesktopChatController(
        bootstrapState = defaultDesktopBootstrapState(),
        scope = this,
        gatewayFactory = gatewayFactory,
    )
}

private fun connected() = ChannelTransportState.Connected(
    serverId = "iroh-app-server",
    sessionId = "session-1",
    deviceId = "device",
    canonicalLiveTransport = "iroh",
)

/** What the supervisor publishes while it is backing off toward a redial. */
private fun degraded() = ChannelTransportState.Disconnected(
    code = 0,
    reason = "liveness_probe_failed: no health.check response",
    willReconnect = true,
)
