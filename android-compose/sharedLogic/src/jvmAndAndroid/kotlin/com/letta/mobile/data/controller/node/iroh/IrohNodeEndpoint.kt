package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.node.IrohRelayConfig
import com.letta.mobile.data.transport.iroh.IrohDiagnostics
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointOptions
import computer.iroh.EndpointTicket
import computer.iroh.Preset
import computer.iroh.EndpointBuilder
import computer.iroh.AddrChangeCallback
import computer.iroh.HomeRelayCallback
import computer.iroh.NetworkChangeCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

import kotlin.time.Duration.Companion.milliseconds
class IrohNodeEndpoint(
    private val alpn: ByteArray = DEFAULT_ALPN,
    private val scope: CoroutineScope,
    private val relayConfig: IrohRelayConfig = IrohRelayConfig.Default,
    /**
     * UDP bind address. Default binds an OS-assigned random port, which makes
     * the ticket rotate on every restart (direct addrs change). Servers that
     * want a STABLE ticket should pin a port, e.g. "0.0.0.0:4501".
     */
    private val bindAddr: String = "0.0.0.0:0",
    /**
     * Backward-compatible explicit key injection. Prefer [secretKeyStore].
     */
    private val secretKey: ByteArray? = null,
    /**
     * Backward-compatible file-backed key path. Prefer [secretKeyStore].
     */
    private val secretKeyPath: String? = null,
    /**
     * Identity key source. Use [FileIrohSecretKeyStore] for stable server
     * identity; default is derived from [secretKey]/[secretKeyPath] or an
     * ephemeral safe key when neither is configured.
     */
    private val secretKeyStore: IrohSecretKeyStore? = null,
    private val requiredBearerToken: String? = null,
    private val allowedPeerIds: Set<String> = emptySet(),
) {
    private var endpoint: Endpoint? = null
    private var acceptJob: Job? = null
    private var _adminRpcRouter: AdminRpcRouter? = null

    /**
     * eaczz.1: ONE registry shared across every connection this endpoint
     * accepts. Maps conversationId -> the set of live viewer handles, enabling
     * a turn on one connection to fan its frames out to all connections viewing
     * the same conversation.
     */
    private val connectionRegistry = ConnectionRegistry()

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
        return nodeIdHex(addr())
    }

    /**
     * Returns a serialized iroh ticket string that encodes the full endpoint address
     * (node ID + relay URL + direct addresses). This can be parsed back into an
     * EndpointAddr for dialing.
     */
    fun ticketString(): String {
        val endpointAddr = addr()
        val ticket = EndpointTicket.fromAddr(endpointAddr)
        return ticket.toString()
    }

    suspend fun create() {
        require(endpoint == null) { "IrohNodeEndpoint already created" }
        val relayMode = IrohRelayConfigMapper.toRelayMode(relayConfig)
        endpoint = Endpoint.bind(
            EndpointOptions(
                preset = object : Preset {
                    override fun apply(builder: EndpointBuilder) {
                        builder.applyN0()
                    }
                },
                bindAddr = bindAddr,
                secretKey = resolveSecretKeyStore().loadOrCreate(),
                alpns = listOf(alpn),
                relayMode = relayMode,
            )
        ).also { ep ->
            ep.online()
            attachWatchers(ep)
            emitEndpointStatus(ep, "online")
        }
    }

    private fun resolveSecretKeyStore(): IrohSecretKeyStore = secretKeyStore ?: when {
        secretKey != null -> FixedIrohSecretKeyStore(secretKey)
        secretKeyPath != null -> FileIrohSecretKeyStore(secretKeyPath)
        else -> EphemeralIrohSecretKeyStore()
    }

    private fun attachWatchers(ep: Endpoint) {
        runCatching {
            ep.watchAddr(object : AddrChangeCallback {
                override suspend fun onChange(addr: EndpointAddr) {
                    Telemetry.event(
                        "IrohNode", "addr.changed",
                        "nodeId" to nodeIdHex(addr),
                        "relay" to (addr.relayUrl() ?: ""),
                        "directAddresses" to addr.directAddresses().joinToString(","),
                    )
                }
            })
        }
        runCatching {
            ep.watchHomeRelay(object : HomeRelayCallback {
                override suspend fun onChange(relays: List<String>) {
                    Telemetry.event("IrohNode", "homeRelay.changed", "relays" to relays.joinToString(","))
                }
            })
        }
        runCatching {
            ep.watchNetworkChange(object : NetworkChangeCallback {
                override suspend fun onChange() {
                    Telemetry.event("IrohNode", "network.changed")
                    emitEndpointStatus(ep, "network_changed")
                }
            })
        }
    }

    private fun emitEndpointStatus(ep: Endpoint, status: String) {
        val endpointAddr = ep.addr()
        Telemetry.event(
            "IrohNode", "endpoint.status",
            "status" to status,
            "nodeId" to nodeIdHex(endpointAddr),
            "relay" to (endpointAddr.relayUrl() ?: ""),
            "directAddresses" to endpointAddr.directAddresses().joinToString(","),
        )
    }

    private fun nodeIdHex(endpointAddr: EndpointAddr): String =
        IrohDiagnostics.endpointIdHex(endpointAddr.id())

    private val irohExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Telemetry.event("IrohNode", "crash.caught", "error" to (throwable.message ?: throwable.toString()), "class" to throwable::class.simpleName)
    }

    fun start(controller: AppServerController) {
        val ep = checkNotNull(endpoint) { "IrohNodeEndpoint not created yet" }
        require(acceptJob == null) { "IrohNodeEndpoint already started" }

        acceptJob = scope.launch(irohExceptionHandler) {
            while (isActive) {
                try {
                    val incoming = withTimeout(ACCEPT_TIMEOUT_MS.milliseconds) {
                        ep.acceptNext()
                    }
                    if (incoming == null) {
                        // A null accept is NOT a terminal signal by itself: breaking
                        // here left the endpoint bound-but-deaf (UDP port alive,
                        // no accepts) until the wrapper was restarted. Only stop
                        // when the scope is cancelled (shutdown()); otherwise log
                        // and keep accepting.
                        Telemetry.event(
                            "IrohNode", "incoming.accept.null",
                            level = Telemetry.Level.WARN,
                        )
                        if (!isActive) break
                        delay(ACCEPT_NULL_RETRY_MS.milliseconds)
                        continue
                    }
                    Telemetry.event("IrohNode", "incoming.accepting")
                    // Handshake + serve run in a CHILD coroutine so a stalled
                    // client handshake (peer died mid-connect) can never block
                    // the accept loop for other clients — the exact wedge that
                    // made every subsequent dial time out.
                    launch {
                        try {
                            val accepting = incoming.accept()
                            val connection = withTimeout(HANDSHAKE_TIMEOUT_MS.milliseconds) { accepting.connect() }
                            val remoteId = IrohDiagnostics.endpointIdHex(connection.remoteId())
                            Telemetry.event("IrohNode", "incoming.connected", "remoteEndpointId" to remoteId)
                            if (allowedPeerIds.isNotEmpty() && remoteId !in allowedPeerIds) {
                                Telemetry.event("IrohNode", "auth.failed", "remoteEndpointId" to remoteId, "reason" to "peer_not_allowed")
                                runCatching { connection.close(4403L, "peer_not_allowed".encodeToByteArray()) }
                            } else {
                                IrohNodeConnection(
                                    connection = connection,
                                    controller = controller,
                                    adminRpcRouter = adminRpcRouter,
                                    requiredBearerToken = requiredBearerToken,
                                    remoteEndpointId = remoteId,
                                    connectionRegistry = connectionRegistry,
                                ).serve()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Telemetry.event(
                                "IrohNode", "incoming.handshake.failed",
                                "error" to (e.message ?: e.toString()),
                                "class" to e::class.simpleName,
                                level = Telemetry.Level.WARN,
                            )
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    continue
                } catch (e: Exception) {
                    if (!isActive) break
                    Telemetry.event("IrohNode", "incoming.accept.failed", "error" to (e.message ?: e.toString()), "class" to e::class.simpleName)
                    delay(ACCEPT_FAILURE_RETRY_MS.milliseconds)
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
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L
        private const val ACCEPT_NULL_RETRY_MS = 1_000L
        private const val ACCEPT_FAILURE_RETRY_MS = 500L
    }
}
