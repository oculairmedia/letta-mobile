package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.node.IrohRelayConfig
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointOptions
import computer.iroh.EndpointTicket
import kotlinx.coroutines.CoroutineExceptionHandler
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
    /**
     * UDP bind address. Default binds an OS-assigned random port, which makes
     * the ticket rotate on every restart (direct addrs change). Servers that
     * want a STABLE ticket should pin a port, e.g. "0.0.0.0:4501".
     */
    private val bindAddr: String = "0.0.0.0:0",
    /**
     * 32-byte secret key. Determines the NodeID. When null, a key is loaded
     * from (or generated + persisted to) [secretKeyPath] so the NodeID — and,
     * with a pinned [bindAddr], the whole ticket — is stable across restarts.
     */
    private val secretKey: ByteArray? = null,
    /**
     * Where to persist the auto-generated secret key when [secretKey] is null.
     * Ignored if [secretKey] is provided.
     */
    private val secretKeyPath: String? = null,
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
                bindAddr = bindAddr,
                secretKey = resolveSecretKey(),
                alpns = listOf(alpn),
                relayMode = relayMode,
            )
        )
    }

    /**
     * Key precedence: explicit [secretKey] > persisted key at [secretKeyPath]
     * (generated once, mode 600) > legacy fixed dev key (backward-compatible
     * fallback so existing setups keep their NodeID until a path is supplied).
     */
    private fun resolveSecretKey(): ByteArray {
        secretKey?.let {
            require(it.size == 32) { "iroh secret key must be 32 bytes, got ${it.size}" }
            return it
        }
        val path = secretKeyPath
        if (path != null) {
            val file = java.io.File(path)
            if (file.exists()) {
                val bytes = file.readBytes()
                require(bytes.size == 32) { "persisted iroh secret key at $path must be 32 bytes, got ${bytes.size}" }
                return bytes
            }
            val generated = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            file.parentFile?.mkdirs()
            file.writeBytes(generated)
            runCatching {
                java.nio.file.Files.setPosixFilePermissions(
                    file.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                )
            }
            Telemetry.event("IrohNode", "secretKey.generated", "path" to path)
            return generated
        }
        return kotlin.ByteArray(32) { i -> (i + 1).toByte() }
    }

    private val irohExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        com.letta.mobile.util.Telemetry.event("IrohNode", "crash.caught", "error" to (throwable.message ?: throwable.toString()), "class" to throwable::class.simpleName)
    }

    fun start(controller: AppServerController) {
        val ep = checkNotNull(endpoint) { "IrohNodeEndpoint not created yet" }
        require(acceptJob == null) { "IrohNodeEndpoint already started" }

        acceptJob = scope.launch(irohExceptionHandler) {
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
