package com.letta.mobile.data.transport.appserver

import io.ktor.websocket.WebSocketDeflateExtension
import io.ktor.websocket.WebSocketExtensionsConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * letta-mobile data-efficiency Phase 2 (Q2): the JVM helper installs BOTH the
 * frame ceiling AND the permessage-deflate extension.
 *
 * The frame ceiling is verified end-to-end by
 * [AppServerWebSocketClientFrameLimitTest] (asserts `maxFrameSize` after a
 * real CIO client installs the helper). That test doesn't cover the deflate
 * half because Ktor's [WebSockets] plugin keeps its `extensionsConfig` private
 * — there's no public way to enumerate the installed extensions from a
 * configured [io.ktor.client.HttpClient].
 *
 * Instead we test the deflate install at the [WebSocketExtensionsConfig] layer
 * directly, which is the same `extensions { install(...) }` block the helper
 * uses. If the import path / class shape ever changes, this test will break
 * loudly instead of silently dropping the extension.
 */
class AppServerWebSocketClientDefaultsTest {

    @Test
    fun webSocketDeflateExtensionIsResolvableFromTheKtorWebsocketsPackage() {
        // Smoke check: the class is reachable. If Ktor ever moves it again
        // (it lived in io.ktor.server.websocket in 2.x and io.ktor.websocket
        // in 3.x), this test is the first to fail.
        val ref = WebSocketDeflateExtension::class
        assertTrue(ref.simpleName?.isNotEmpty() == true)
    }

    @Test
    fun installWebSocketDeflateExtensionIntoABareConfigRegistersIt() {
        val extensionsConfig = WebSocketExtensionsConfig()
        // Same install call the helper uses inside its `extensions { install(...) }`
        // block. If this throws or silently swallows the install, the helper
        // can't be relied on.
        extensionsConfig.install(WebSocketDeflateExtension)

        val installed = extensionsConfig.build()
        assertTrue(
            installed.any { it is WebSocketDeflateExtension },
            "install(WebSocketDeflateExtension) did not register the extension; got=$installed",
        )
    }
}