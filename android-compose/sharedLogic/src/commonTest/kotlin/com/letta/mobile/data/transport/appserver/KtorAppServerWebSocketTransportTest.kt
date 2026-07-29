package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
            AppServerProtocol.classifyInboundChannel("""{"type":"update_loop_status"}"""),
        )
        assertEquals(
            AppServerChannel.Stream,
            AppServerProtocol.classifyInboundChannel("""{"type":"stream_delta"}"""),
        )
        assertEquals(
            AppServerChannel.Control,
            AppServerProtocol.classifyInboundChannel("""{"type":"runtime_start_response","request_id":"r"}"""),
        )
        assertEquals(
            AppServerChannel.Control,
            AppServerProtocol.classifyInboundChannel("this-is-not-json"),
        )
    }
}
