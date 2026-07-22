package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.CoroutineScope

/**
 * Factory interface for creating [AppServerTransport] instances from endpoints.
 *
 * Implementations are registered with [AppServerTransportRegistry] and selected
 * by the endpoint's [AppServerEndpoint.scheme].
 */
interface AppServerTransportAdapter {
    /**
     * The scheme this adapter handles (e.g., "ws", "wss", "iroh").
     */
    val scheme: String

    /**
     * Creates a transport instance for the given endpoint.
     *
     * @param endpoint The endpoint descriptor with address and auth
     * @param scope The coroutine scope for transport I/O jobs
     * @param protocol The App Server protocol codec (typically [AppServerProtocol])
     * @return A ready-to-use transport instance
     * @throws IllegalArgumentException if the endpoint is malformed for this adapter
     */
    fun createTransport(
        endpoint: AppServerEndpoint,
        scope: CoroutineScope,
        protocol: AppServerProtocol = AppServerProtocol,
    ): AppServerTransport
}

/**
 * Registry for transport adapters. Resolves [AppServerEndpoint] descriptors
 * to concrete [AppServerTransport] implementations.
 *
 * The Ktor WebSocket adapter is registered by default for "ws" and "wss".
 * Future adapters (Iroh QUIC, etc.) can be registered before use.
 */
object AppServerTransportRegistry {
    private val adapters = mutableMapOf<String, AppServerTransportAdapter>()

    /**
     * Registers a transport adapter for the given scheme(s).
     * Overwrites any existing adapter for the same scheme.
     */
    fun register(adapter: AppServerTransportAdapter) {
        adapters[adapter.scheme] = adapter
    }

    /**
     * Retrieves the adapter for the given scheme.
     * Returns null if no adapter is registered.
     */
    fun getAdapter(scheme: String): AppServerTransportAdapter? {
        return adapters[scheme]
    }

    /**
     * Creates a transport for the given endpoint, using the registered adapter.
     *
     * @throws IllegalArgumentException if no adapter is registered for the scheme
     */
    fun createTransport(
        endpoint: AppServerEndpoint,
        scope: CoroutineScope,
        protocol: AppServerProtocol = AppServerProtocol,
    ): AppServerTransport {
        val adapter = getAdapter(endpoint.scheme)
            ?: throw IllegalArgumentException(
                "No transport adapter registered for scheme '${endpoint.scheme}'. " +
                    "Available: ${adapters.keys.sorted()}"
            )
        return adapter.createTransport(endpoint, scope, protocol)
    }

    /**
     * Clears all registered adapters. Useful for testing.
     */
    internal fun clearForTest() {
        adapters.clear()
    }
}
