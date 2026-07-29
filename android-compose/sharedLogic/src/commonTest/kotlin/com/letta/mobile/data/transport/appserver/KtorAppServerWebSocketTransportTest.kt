package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KtorAppServerWebSocketTransportTest {
    @Test
    fun sessionUrlUsesAppServerWsEndpointWithoutChannel() {
        assertEquals("ws://127.0.0.1:4500/ws", appServerUrl("ws://127.0.0.1:4500"))
        assertEquals(
            "wss://example.test/ws",
            appServerUrl("wss://example.test/base?old=true&channel=stream"),
        )
        assertFalse(appServerUrl("ws://127.0.0.1:4500").contains("channel="))
    }

    @Test
    @Suppress("DEPRECATION")
    fun deprecatedChannelUrlHelperAlsoOmitsChannel() {
        assertEquals(
            "ws://127.0.0.1:4500/ws",
            appServerChannelUrl("ws://127.0.0.1:4500", AppServerChannel.Control),
        )
        assertEquals(
            "wss://example.test/ws",
            appServerChannelUrl("wss://example.test/base?old=true", AppServerChannel.Stream),
        )
    }

    @Test
    fun classifyInboundChannelMatchesUpstreamStreamSet() {
        assertEquals(
            AppServerChannel.Stream,
            AppServerProtocol.decodeFrame("""{"type":"update_loop_status"}""").channel,
        )
        assertEquals(
            AppServerChannel.Stream,
            AppServerProtocol.decodeFrame("""{"type":"stream_delta"}""").channel,
        )
        assertEquals(
            AppServerChannel.Control,
            AppServerProtocol.decodeFrame(
                """{"type":"runtime_start_response","request_id":"r"}""",
            ).channel,
        )
        assertEquals(
            AppServerChannel.Control,
            AppServerProtocol.decodeFrame("this-is-not-json").channel,
        )
    }

    @Test
    fun terminalHandshakeFailureRequiresAnHttpStatusOrNamedRejection() {
        assertTrue(
            RuntimeException("Handshake exception, expected status code 101 but was 426")
                .isTerminalHandshakeFailure(),
        )
        assertTrue(
            RuntimeException("Handshake exception, expected status code 101 but was 403")
                .isTerminalHandshakeFailure(),
        )
        assertTrue(
            RuntimeException("Handshake exception, expected status code 101 but was 401")
                .isTerminalHandshakeFailure(),
        )
        assertFalse(RuntimeException("Failed to connect to /127.0.0.1:4260").isTerminalHandshakeFailure())
        assertFalse(RuntimeException("Failed to connect to /127.0.0.1:426").isTerminalHandshakeFailure())
    }
}
