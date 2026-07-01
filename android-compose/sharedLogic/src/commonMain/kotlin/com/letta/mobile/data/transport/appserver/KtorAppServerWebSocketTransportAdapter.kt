package com.letta.mobile.data.transport.appserver

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope

/**
 * Transport adapter for Ktor-based WebSocket connections ("ws" and "wss").
 *
 * Wraps [KtorAppServerWebSocketTransport] to conform to the pluggable
 * transport factory seam. Register this adapter before creating transports
 * from WebSocket endpoints:
 *
 * ```
 * AppServerTransportRegistry.register(
 *     KtorAppServerWebSocketTransportAdapter(httpClient)
 * )
 * val endpoint = AppServerEndpoint.fromWebSocketUrl("ws://127.0.0.1:4500")
 * val transport = AppServerTransportRegistry.createTransport(endpoint, scope)
 * ```
 *
 * Both "ws" and "wss" schemes are handled by the same adapter instance.
 */
class KtorAppServerWebSocketTransportAdapter(
    private val httpClient: HttpClient,
) : AppServerTransportAdapter {
    // This adapter handles both ws and wss, but we only report one scheme.
    // The registry will be configured to map both to this instance.
    override val scheme: String = "ws"

    override fun createTransport(
        endpoint: AppServerEndpoint,
        scope: CoroutineScope,
        protocol: AppServerProtocol,
    ): AppServerTransport {
        require(endpoint.scheme == "ws" || endpoint.scheme == "wss") {
            "KtorAppServerWebSocketTransportAdapter only handles 'ws' and 'wss' schemes, got '${endpoint.scheme}'"
        }

        return KtorAppServerWebSocketTransport(
            httpClient = httpClient,
            baseUrl = normalizeWsAddress(endpoint.address, endpoint.scheme),
            scope = scope,
            bearerToken = endpoint.bearerToken,
            protocol = protocol,
        )
    }

    /**
     * Normalizes a WebSocket address to a proper ws:// or wss:// URL.
     * If the address is a bare host:port (no scheme), prepends ws:// or wss://
     * so URLBuilder can parse it correctly (fixes P2 Codex review: normalize
     * host:port WebSocket endpoints before dialing).
     */
    private fun normalizeWsAddress(address: String, scheme: String): String =
        if (address.contains("://")) address else "$scheme://$address"

    companion object {
        /**
         * Registers this adapter for both "ws" and "wss" schemes.
         * Call this once at app initialization if using WebSocket transports.
         */
        fun registerDefault(httpClient: HttpClient) {
            val adapter = KtorAppServerWebSocketTransportAdapter(httpClient)
            AppServerTransportRegistry.register(adapter)
            // Also register for wss explicitly
            AppServerTransportRegistry.register(object : AppServerTransportAdapter {
                override val scheme: String = "wss"
                override fun createTransport(
                    endpoint: AppServerEndpoint,
                    scope: CoroutineScope,
                    protocol: AppServerProtocol
                ): AppServerTransport = adapter.createTransport(endpoint, scope, protocol)
            })
        }
    }
}
