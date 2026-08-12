package com.letta.mobile.data.transport.appserver

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.WebSocketDeflateExtension
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile data-efficiency Phase 2 (Q2): the JVM helper installs BOTH the
 * frame ceiling AND the permessage-deflate extension. The frame ceiling test
 * is in [AppServerWebSocketClientFrameLimitTest]; this one specifically covers
 * the deflate half of the combined helper.
 */
class AppServerWebSocketClientDefaultsTest {

    @Test
    fun applyAppServerDefaultsIncludesBothTheFrameCeilingAndTheDeflateExtension() {
        HttpClient(CIO) {
            install(WebSockets) { applyAppServerDefaults() }
        }.use { client ->
            val webSockets = client.plugin(WebSockets)
            // Frame ceiling preserved (see AppServerWebSocketClientFrameLimitTest for the
            // contract; re-asserted here so the combined helper's two-in-one promise
            // is locked in one place.)
            assertEquals(
                AppServerWebSocketLimits.MAX_FRAME_BYTES,
                webSockets.maxFrameSize,
            )
            // Deflate extension installed. The plugin's `extensions` list carries the
            // configured extensions; WebSocketDeflateExtension is a data object so
            // identity comparison is enough.
            val installed = webSockets.extensions
            assertTrue(
                installed.any { it is WebSocketDeflateExtension },
                "applyAppServerDefaults() did not install WebSocketDeflateExtension; got=$installed",
            )
        }
    }
}
