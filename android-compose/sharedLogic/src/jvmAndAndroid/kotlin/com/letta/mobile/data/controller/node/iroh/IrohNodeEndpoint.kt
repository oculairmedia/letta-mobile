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
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class IrohNodeEndpoint(
    private val alpn: ByteArray = DEFAULT_ALPN,
    private val scope: CoroutineScope,
    private val relayConfig: IrohRelayConfig = IrohRelayConfig.Default,
) {
    private var endpoint: Endpoint? = null
    private var acceptJob: Job? = null
    private var _adminRpcRouter: AdminRpcRouter? = null

    /**
     * The admin RPC router for this endpoint. Created lazily so handlers can
     * register before [start] is called. Passed to every incoming connection.
     */
    val adminRpcRouter: AdminRpcRouter
        get() {
            val r = _adminRpcRouter
            if (r != null) return r
            val created = AdminRpcRouter()
            _adminRpcRouter = created
            return created
        }

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
                bindAddr = "0.0.0.0:0",
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
                    val incoming = withTimeout(ACCEPT_TIMEOUT_MS) {
                        ep.acceptNext()
                    } ?: break
                    Telemetry.event("IrohNode", "incoming.accepting")
                    val accepting = incoming.accept()
                    val connection = accepting.connect()
                    Telemetry.event("IrohNode", "incoming.connected")

                    launch {
                        IrohNodeConnection(
                            connection = connection,
                            controller = controller,
                            alpn = alpn,
                            adminRpcRouter = adminRpcRouter,
                        ).serve()
                    }
                } catch (_: TimeoutCancellationException) {
                    continue
                } catch (e: Exception) {
                    if (!isActive) break
                    Telemetry.event("IrohNode", "incoming.accept.failed", "error" to (e.message ?: e.toString()), "class" to e::class.simpleName)
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
        private const val ACCEPT_TIMEOUT_MS = 120_000L
    }
}
