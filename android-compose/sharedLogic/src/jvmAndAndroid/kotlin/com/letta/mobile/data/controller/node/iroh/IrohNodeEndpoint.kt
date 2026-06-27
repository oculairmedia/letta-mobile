package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.node.IrohRelayConfig
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointOptions
import computer.iroh.EndpointTicket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class IrohNodeEndpoint(
    private val alpn: ByteArray = DEFAULT_ALPN,
    private val scope: CoroutineScope,
    private val relayConfig: IrohRelayConfig = IrohRelayConfig.Default,
) {
    private var endpoint: Endpoint? = null
    private var acceptJob: Job? = null

    /**
     * Returns the full EndpointAddr (node ID + relay + direct addresses).
     * This is the canonical dialable address for this endpoint.
     */
    fun addr(): EndpointAddr {
        val ep = checkNotNull(endpoint) { "IrohNodeEndpoint not created yet" }
        return ep.addr()
    }

    /**
     * Returns the node ID as a hex string (64 hex characters = 32 bytes).
     */
    fun nodeIdHex(): String {
        val endpointAddr = addr()
        val nodeIdBytes = endpointAddr.id().toBytes()
        return nodeIdBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns a serialized iroh ticket string that encodes the full endpoint address
     * (node ID + relay URL + direct addresses). This can be parsed back into an
     * EndpointAddr for dialing.
     */
    fun ticketString(): String {
        val endpointAddr = addr()
        val ticket = EndpointTicket.Companion.fromAddr(endpointAddr)
        return ticket.toString()
    }

    fun asAppServerEndpoint(): AppServerEndpoint {
        // Use the ticket format which includes direct addresses, so loopback works
        return AppServerEndpoint(
            scheme = "iroh",
            address = ticketString(),
            bearerToken = null,
        )
    }

    suspend fun create() {
        require(endpoint == null) { "IrohNodeEndpoint already created" }
        val relayMode = IrohRelayConfigMapper.toRelayMode(relayConfig)
        endpoint = Endpoint.bind(
            EndpointOptions(
                alpns = listOf(alpn),
                relayMode = relayMode,
            )
        )
    }

    fun start(controller: AppServerController) {
        val ep = checkNotNull(endpoint) { "IrohNodeEndpoint not created yet" }
        require(acceptJob == null) { "IrohNodeEndpoint already started" }

        acceptJob = scope.launch {
            while (isActive) {
                try {
                    val incoming = ep.acceptNext() ?: break
                    val accepting = incoming.accept()
                    val connection = accepting.connect()

                    launch {
                        IrohNodeConnection(connection, controller, alpn).serve()
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                }
            }
        }
    }

    suspend fun shutdown() {
        acceptJob?.cancel()
        acceptJob = null

        endpoint?.let {
            runCatching { it.shutdown() }
            runCatching { it.close() }
        }
        endpoint = null
    }

    companion object {
        val DEFAULT_ALPN = "/letta/appserver/0".toByteArray()
    }
}
