package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerTransport
import com.letta.mobile.data.transport.appserver.AppServerTransportAdapter
import com.letta.mobile.data.transport.appserver.AppServerTransportRegistry
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import kotlinx.coroutines.CoroutineScope

/**
 * Transport adapter for Iroh QUIC connections ("iroh" scheme).
 *
 * Wraps [IrohAppServerTransport] to conform to the pluggable transport factory seam.
 * Register this adapter before creating transports from Iroh endpoints:
 *
 * ```
 * val irohEndpoint = Endpoint.bind(EndpointOptions())
 * val adapter = IrohAppServerTransportAdapter(
 *     endpoint = irohEndpoint,
 *     alpn = "/letta/appserver/0".toByteArray()
 * )
 * AppServerTransportRegistry.register(adapter)
 * 
 * val endpoint = AppServerEndpoint(
 *     scheme = "iroh",
 *     address = "node-id-hex-string"
 * )
 * val transport = AppServerTransportRegistry.createTransport(endpoint, scope)
 * ```
 *
 * The [AppServerEndpoint.address] field should contain an iroh node ID in hex format,
 * or optionally a full iroh ticket (which includes relay/direct addresses).
 *
 * @param endpoint The local iroh endpoint to use for connections (must be bound)
 * @param alpn The ALPN protocol identifier for App Server connections
 */
class IrohAppServerTransportAdapter(
    private val endpoint: Endpoint,
    private val alpn: ByteArray = DEFAULT_ALPN,
) : AppServerTransportAdapter {
    override val scheme: String = "iroh"

    override fun createTransport(
        endpoint: AppServerEndpoint,
        scope: CoroutineScope,
        protocol: AppServerProtocol,
    ): AppServerTransport {
        require(endpoint.scheme == "iroh") {
            "IrohAppServerTransportAdapter only handles 'iroh' scheme, got '${endpoint.scheme}'"
        }

        // Parse the address as an iroh EndpointAddr
        // For now, we support node ID hex strings. In the future, we could support
        // full iroh tickets (which include relay + direct addresses).
        val remoteAddr = parseIrohAddress(endpoint.address)

        return IrohAppServerTransport(
            endpoint = this.endpoint,
            remoteAddr = remoteAddr,
            alpn = alpn,
            scope = scope,
            protocol = protocol,
        )
    }

    /**
     * Parses an iroh address from the endpoint address string.
     * 
     * Supported formats:
     * - Hex-encoded node ID (64 hex chars = 32 bytes)
     * - Future: iroh ticket format
     */
    private fun parseIrohAddress(address: String): EndpointAddr {
        // Try to parse as hex node ID
        if (address.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            // Decode hex to bytes
            val nodeIdBytes = address.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
            
            val endpointId = EndpointId.fromBytes(nodeIdBytes)
            // EndpointAddr constructor takes just the ID, with no relay/direct addrs
            return EndpointAddr(endpointId, null, emptyList())
        }
        
        // If not a simple hex ID, try parsing as iroh ticket/addr format
        // For now, we'll assume it's a node ID and throw if invalid
        throw IllegalArgumentException(
            "Invalid iroh address: '$address'. Expected 64 hex characters (node ID). " +
            "Example: 'd6dfd712061fd55bdfc4c1c6e6e7ed8c8f8f9a5a2e8e8e8e8e8e8e8e8e8e8e8e'"
        )
    }

    companion object {
        /**
         * Default ALPN for Letta App Server over Iroh.
         * This should match the ALPN used by the server side.
         */
        val DEFAULT_ALPN = "/letta/appserver/0".toByteArray()

        /**
         * Registers this adapter for the "iroh" scheme.
         * Call this once at app initialization if using Iroh transports.
         *
         * @param endpoint The local iroh endpoint to use for all connections
         * @param alpn The ALPN protocol identifier (defaults to "/letta/appserver/0")
         */
        fun registerDefault(endpoint: Endpoint, alpn: ByteArray = DEFAULT_ALPN) {
            val adapter = IrohAppServerTransportAdapter(endpoint, alpn)
            AppServerTransportRegistry.register(adapter)
        }
    }
}
