package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.Serializable

/**
 * Transport-neutral endpoint descriptor for App Server connections.
 *
 * Describes where to connect and what authentication to use, independent of
 * the underlying transport mechanism (WebSocket, QUIC, etc.). The transport
 * adapter is selected by the [scheme] (e.g., "ws", "wss", "iroh").
 *
 * Example:
 * ```
 * // Local loopback WebSocket
 * AppServerEndpoint(scheme = "ws", address = "127.0.0.1:4500")
 *
 * // Remote WebSocket with auth
 * AppServerEndpoint(scheme = "wss", address = "example.com", bearerToken = "...")
 *
 * // Future: QUIC via Iroh
 * AppServerEndpoint(scheme = "iroh", address = "node-id", bearerToken = "...")
 * ```
 */
@Serializable
data class AppServerEndpoint(
    /**
     * Transport scheme: "ws", "wss", "iroh", etc.
     * Determines which [AppServerTransportAdapter] to use.
     */
    val scheme: String,

    /**
     * Transport-specific address. For WebSocket: a URL or host:port.
     * For QUIC/Iroh: a node ID or address. The adapter interprets this.
     */
    val address: String,

    /**
     * Optional bearer token for authentication.
     * `null` for loopback App Server runs that omit WS auth.
     * Required for non-loopback/headless hosts launched with `--ws-auth`.
     */
    val bearerToken: String? = null,
) {
    companion object {
        /**
         * Creates a WebSocket endpoint from a full URL.
         * Extracts scheme (ws/wss) and uses the full URL as the address.
         */
        fun fromWebSocketUrl(url: String, bearerToken: String? = null): AppServerEndpoint {
            val scheme = when {
                url.startsWith("wss://") -> "wss"
                url.startsWith("ws://") -> "ws"
                else -> throw IllegalArgumentException("URL must start with ws:// or wss://")
            }
            return AppServerEndpoint(
                scheme = scheme,
                address = url,
                bearerToken = bearerToken,
            )
        }
    }
}

private val WEB_SOCKET_SCHEMES = setOf("ws", "wss")

/**
 * The WebSocket base URL to dial for this endpoint. An [AppServerEndpoint.address] that already
 * carries a scheme (`ws://host:port`) is used as is; a bare `host:port` is prefixed with
 * [AppServerEndpoint.scheme]. Any scheme other than ws or wss, on the endpoint or in the address,
 * fails here with [IllegalArgumentException], before a connection is attempted.
 */
internal fun AppServerEndpoint.webSocketBaseUrl(): String {
    val addressScheme = address.substringBefore("://", missingDelimiterValue = "")
    val effective = addressScheme.ifEmpty { scheme }
    require(scheme in WEB_SOCKET_SCHEMES && effective in WEB_SOCKET_SCHEMES) {
        "App Server endpoint must use ws or wss, got scheme '$scheme' and address '$address'"
    }
    return if (addressScheme.isEmpty()) "$scheme://$address" else address
}
