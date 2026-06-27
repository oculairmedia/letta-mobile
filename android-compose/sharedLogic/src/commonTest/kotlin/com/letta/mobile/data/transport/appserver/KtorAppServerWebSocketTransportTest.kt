package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertEquals

class KtorAppServerWebSocketTransportTest {
    @Test
    fun channelUrlsUseAppServerWsEndpoint() {
        assertEquals(
            "ws://127.0.0.1:4500/ws?channel=control",
            appServerChannelUrl("ws://127.0.0.1:4500", AppServerChannel.Control),
        )
        assertEquals(
            "wss://example.test/ws?channel=stream",
            appServerChannelUrl("wss://example.test/base?old=true", AppServerChannel.Stream),
        )
    }
}
