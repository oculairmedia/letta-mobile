package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerTransport
import com.letta.mobile.data.transport.appserver.AppServerTransportAdapter
import com.letta.mobile.data.transport.appserver.AppServerTransportRegistry
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointTicket
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
    private val onConnectionLost: (String) -> Unit = {},
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
            onConnectionLost = onConnectionLost,
        )
    }

    /**
     * Parses an iroh address from the endpoint address string.
     *
     * Supported formats (human-friendly SHORT forms first):
     * - `<node-id-hex>@<host:port>[,<host:port>...]` — 64-hex node ID plus one or
     *   more direct socket addresses. ~90 chars, share-able by hand, works on
     *   LAN/loopback with no relay. THIS is the recommended dial form.
     * - Hex-encoded node ID alone (64 hex chars) — requires relay/discovery.
     * - Full iroh ticket — machine-generated, includes relay + direct addrs.
     */
    // Delegates to the companion so parsing is unit-testable without binding a live Endpoint.
    companion object {
        /**
         * Default ALPN for Letta App Server over Iroh.
         * This should match the ALPN used by the server side.
         */
        val DEFAULT_ALPN = "/letta/appserver/0".toByteArray()

        /**
         * Parses an iroh address string into an [EndpointAddr].
         *
         * Pure function on the companion so it is unit-testable WITHOUT binding a
         * live Iroh endpoint (parser tests must not touch the network).
         *
         * Supported formats (human-friendly SHORT forms first):
         * - `<node-id-hex>@<host:port>[,<host:port>...]` — 64-hex node ID plus one
         *   or more direct socket addresses. ~110 chars, hand-shareable, works on
         *   LAN/loopback with no relay. Recommended dial form.
         * - Bare 64-hex node ID — requires relay/discovery.
         * - Full iroh ticket — machine-generated, includes relay + direct addrs.
         */
        internal fun parseIrohAddress(address: String): EndpointAddr {
            // Short form: <64-hex>@host:port[,host:port...]
            val atIndex = address.indexOf('@')
            if (atIndex > 0) {
                val idPart = address.take(atIndex)
                val addrsPart = address.substring(atIndex + 1)
                require(idPart.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                    "Invalid iroh address: node-id part before '@' must be 64 hex chars, got ${idPart.length}."
                }
                val directAddrs = addrsPart.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                require(directAddrs.isNotEmpty()) {
                    "Invalid iroh address: no direct addresses after '@'. Expected host:port[,host:port...]."
                }
                directAddrs.forEach {
                    require(it.contains(':')) { "Invalid direct address '$it': expected host:port." }
                }
                return EndpointAddr(hexToEndpointId(idPart), null, directAddrs)
            }

            // Bare node ID (relay/discovery required — no direct addrs)
            if (address.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                return EndpointAddr(hexToEndpointId(address), null, emptyList())
            }

            // Full iroh ticket (includes direct addresses) — machine format.
            try {
                val ticket = EndpointTicket.Companion.fromString(address)
                return ticket.endpointAddr()
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Invalid iroh address: '$address'. Expected an iroh ticket, " +
                        "64 hex chars (node ID), or <node-id-hex>@<host:port>.",
                    e
                )
            }
        }

        private fun hexToEndpointId(hex: String): EndpointId {
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return EndpointId.fromBytes(bytes)
        }

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
