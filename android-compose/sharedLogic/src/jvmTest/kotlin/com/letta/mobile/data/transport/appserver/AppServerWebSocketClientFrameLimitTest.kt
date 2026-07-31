package com.letta.mobile.data.transport.appserver

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-lgns8.21.7: an installed client actually carries the ceiling —
 * the constant alone proves nothing if a call site forgets to apply it.
 */
class AppServerWebSocketClientFrameLimitTest {

    @Test
    fun installedWebSocketsPluginCarriesTheFrameCeiling() {
        HttpClient(CIO) {
            install(WebSockets) { applyAppServerFrameLimits() }
        }.use { client ->
            assertEquals(
                AppServerWebSocketLimits.MAX_FRAME_BYTES,
                client.plugin(WebSockets).maxFrameSize,
            )
        }
    }
}
