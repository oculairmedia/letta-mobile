package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.SyncBudgetPolicy
import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.controller.fanout.StreamIntegrityBinding
import com.letta.mobile.data.controller.node.iroh.EphemeralIrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.IrohNodeProtocolHandler
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.util.Telemetry
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.Connection
import computer.iroh.Incoming
import computer.iroh.RelayMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Encapsulates dialing, authentication, capabilities exchange, and resource wiring for Iroh connections.
 */
internal class IrohDialer(
    private val scope: CoroutineScope,
    private val secretKeyStore: IrohSecretKeyStore = EphemeralIrohSecretKeyStore(),
    private val onConnectionLost: (reason: String, handle: IrohConnectionHandle?) -> Unit,
    private val onCloseResources: (reason: String) -> Unit = {},
    private val externalToolRegistry: ExternalToolRegistry? = null,
    // Create a fresh handler for each session; its peer policy is independent of App Server auth.
    private val notebookHandlerFactory: ((Endpoint, CoroutineScope) -> IrohNodeProtocolHandler?)? = null,
    private val bindEndpoint: suspend (ByteArray, List<ByteArray>) -> Endpoint = { secretKey, alpns ->
        Endpoint.bind(EndpointOptions(relayMode = RelayMode.defaultMode(), secretKey = secretKey, alpns = alpns))
    },
    /** letta-mobile-by4vm.50: when cadence syncs may run on this device; recovery syncs always may. */
    private val syncBudget: SyncBudgetPolicy = SyncBudgetPolicy.Unlimited,
) {
    private val endpointMutex = Mutex()

    /** letta-mobile-by4vm.95: one engine and router for every dial of this transport, built on the first. */
    private val transportEngine by lazy { IrohTransportEngine(scope, externalToolRegistry) }
    private var hostEndpoint: Endpoint? = null
    private var hostNotebook: InboundNotebook? = null

    /** Bind once for the host lifetime, independently of App Server readiness. */
    suspend fun startNotebook(): Endpoint? {
        if (notebookHandlerFactory == null) return null
        return endpointMutex.withLock {
            hostEndpoint?.let { return@withLock it }
            check(scope.isActive) { "Iroh host scope is closed" }
            val endpoint = bindLocalEndpoint(secretKeyStore.loadOrCreate())
            var notebook: InboundNotebook? = null
            try {
                val notebookScope = scope + SupervisorJob(scope.coroutineContext[Job])
                try {
                    notebook = notebookHandlerFactory.invoke(endpoint, notebookScope)?.let { handler ->
                        try {
                            require(handler.alpn.contentEquals(AUTOMERGE_REPO_ALPN)) { "Unexpected notebook ALPN" }
                            InboundNotebook(endpoint, handler, notebookScope).also { it.start() }
                        } catch (error: Throwable) {
                            (handler as? AutoCloseable)?.close()
                            throw error
                        }
                    }
                } finally {
                    if (notebook == null) notebookScope.coroutineContext[Job]?.cancel()
                }
                check(scope.isActive) { "Iroh host scope is closed" }
                hostNotebook = notebook
                hostEndpoint = endpoint
                scope.coroutineContext[Job]?.invokeOnCompletion {
                    notebook?.close()
                    runCatching { runBlocking { endpoint.shutdown() } }
                    runCatching { endpoint.close() }
                }
                endpoint
            } catch (error: Throwable) {
                notebook?.close()
                endpoint.shutdown()
                endpoint.close()
                throw error
            }
        }
    }

    suspend fun dial(
        config: IrohConnectConfig,
        effectiveUrlOverride: String? = null,
        onConnecting: () -> Unit = {},
    ): IrohConnectionHandle {
        val ticket = extractTicket(config, effectiveUrlOverride)
        onConnecting()
        return runCatching {
            val sharedEndpoint = startNotebook()
            val localEndpoint = sharedEndpoint ?: bindLocalEndpoint(secretKeyStore.loadOrCreate())
            var transport: IrohAppServerTransport? = null
            try {
                val dialedHandle = AtomicReference<IrohConnectionHandle?>(null)
                val irohTransport = createTransport(localEndpoint, ticket, dialedHandle)
                transport = irohTransport
                val appServerClient = DefaultAppServerClient(irohTransport)
                val serverCapabilities = authenticateClient(appServerClient, config.token)
                irohTransport.awaitConnectionReady()
                val engine = transportEngine.engine(config.clientVersion)
                val bound = transportEngine.startDial(appServerClient) { router ->
                    // letta-mobile-by4vm.50: per dial, since event_seq and capabilities are the dial's.
                    bindIrohStreamIntegrity(router, appServerClient, serverCapabilities, scope)
                }
                buildHandle(
                    HandleComponents(config, ticket, irohTransport, IrohDialedEngine(engine, bound), serverCapabilities, localEndpoint, sharedEndpoint == null),
                ).also { dialedHandle.set(it) }
            } catch (error: Throwable) {
                closeIrohResources("dial_failed", transport, localEndpoint.takeIf { sharedEndpoint == null })
                throw error
            }
        }.onFailure {
            onCloseResources("dial_failed")
        }.getOrThrow()
    }

    private fun extractTicket(config: IrohConnectConfig, effectiveUrlOverride: String?): String {
        val effectiveUrl = effectiveUrlOverride?.takeIf { it.isNotBlank() } ?: config.baseShimUrl
        if (!IrohChannelTransport.isIrohUrl(effectiveUrl)) {
            error("IrohChannelTransport requires backend URL iroh://<EndpointTicket>.")
        }
        return IrohChannelTransport.normalizeIrohAddress(effectiveUrl).takeIf { it.isNotBlank() }
            ?: error("IrohChannelTransport requires backend URL iroh://<EndpointTicket>.")
    }

    private suspend fun bindLocalEndpoint(secretKey: ByteArray): Endpoint = runCatching {
        bindEndpoint(secretKey, if (notebookHandlerFactory == null) emptyList() else listOf(AUTOMERGE_REPO_ALPN))
    }.onFailure { t ->
        Telemetry.event("IrohTransport", "bind.failed", "error" to (t.message ?: t.toString()), "class" to t::class.simpleName)
    }.getOrThrow()

    private fun createTransport(
        localEndpoint: Endpoint,
        ticket: String,
        dialedHandle: AtomicReference<IrohConnectionHandle?>,
    ): IrohAppServerTransport {
        return IrohAppServerTransportAdapter(
            endpoint = localEndpoint,
            onConnectionLost = { reason -> onConnectionLost(reason, dialedHandle.get()) },
        ).createTransport(
            endpoint = AppServerEndpoint(scheme = "iroh", address = ticket),
            scope = scope,
        ) as IrohAppServerTransport
    }

    private suspend fun authenticateClient(
        appServerClient: DefaultAppServerClient,
        token: String,
    ): Set<String>? {
        val auth = appServerClient.auth(
            AppServerCommand.Auth(
                requestId = "auth-${UUID.randomUUID()}",
                token = token,
                capabilities = listOf(IrohFrameCodec.FRAME_PART_CAPABILITY),
            ),
        )
        // letta-mobile-dpv7m.2: a denied auth is terminal even without a token. A
        // paired device sends a blank token and authenticates by NodeId; if the
        // host says no (never paired, or revoked) the session must surface
        // AuthFailed instead of carrying on "connected" into per-call denials.
        if (!auth.success) {
            throw IrohAuthFailure(auth.error ?: "Iroh auth failed")
        }
        Telemetry.event(
            "IrohTransport", "auth.negotiated",
            "success" to auth.success,
            "serverCapabilities" to (auth.capabilities ?: emptyList()).sorted().joinToString(","),
        )
        return auth.capabilities?.toSet()
    }

    private fun buildHandle(components: HandleComponents): IrohConnectionHandle = IrohConnectionHandle(
        config = components.config,
        ticket = components.ticket,
        sessionId = components.ticket.hashCode().toString(),
        transport = components.transport,
        turnEngine = components.engine.engine,
        serverCapabilities = components.serverCapabilities,
        openConnection = { alpn ->
            components.localEndpoint.connect(IrohAppServerTransportAdapter.parseIrohAddress(components.ticket), alpn)
        },
        close = { reason ->
            // Ends every turn the dial carried, as the per-dial router's detach did before by4vm.95.
            transportEngine.endDial(components.engine.dial)
            onCloseResources(reason)
            closeIrohResources(reason, components.transport, components.localEndpoint.takeIf { components.ownsEndpoint })
        },
    )

    private data class HandleComponents(
        val config: IrohConnectConfig,
        val ticket: String,
        val transport: IrohAppServerTransport,
        val engine: IrohDialedEngine,
        val serverCapabilities: Set<String>?,
        val localEndpoint: Endpoint,
        val ownsEndpoint: Boolean,
    )

    internal class InboundNotebook(
        private val endpoint: Endpoint,
        private val handler: IrohNodeProtocolHandler,
        private val parentScope: CoroutineScope,
    ) : AutoCloseable {
        private val connections = ConcurrentHashMap.newKeySet<Connection>()
        private var acceptJob: Job? = null
        private val servingJobs = ConcurrentHashMap.newKeySet<Job>()
        @Volatile private var closed = false

        fun start() {
            check(acceptJob == null && !closed)
            acceptJob = parentScope.launch {
                acceptLoop()
            }
        }

        private suspend fun CoroutineScope.acceptLoop() {
            while (isActive) {
                acceptNextConnection()
            }
        }

        private suspend fun CoroutineScope.acceptNextConnection() {
            try {
                val incoming = endpoint.acceptNext()
                if (incoming == null) {
                    delay(100)
                    return
                }
                launch { serve(incoming) }.also { child ->
                    servingJobs.add(child)
                    child.invokeOnCompletion { servingJobs.remove(child) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActive) return
                Telemetry.event("IrohTransport", "notebook.accept.failed", "error" to (e.message ?: e.toString()))
                delay(100)
            }
        }

        private suspend fun serve(incoming: Incoming) {
            var connection: Connection? = null
            try {
                connection = establishConnection(incoming) ?: return
                val remoteId = IrohDiagnostics.endpointIdHex(connection.remoteId())
                if (!isAuthorizedPeer(connection, remoteId)) return
                connections.add(connection)
                if (!closed) handler.accept(connection, remoteId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Telemetry.event("IrohTransport", "notebook.incoming.failed", "error" to (e.message ?: e.toString()))
            } finally {
                connection?.let {
                    connections.remove(it)
                    runCatching { it.close(0L, "closed".encodeToByteArray()) }
                }
            }
        }

        private suspend fun establishConnection(incoming: Incoming): Connection? {
            val accepting = incoming.accept()
            if (!accepting.alpn().contentEquals(AUTOMERGE_REPO_ALPN)) return null
            return withTimeout(15_000) { accepting.connect() }
        }

        private fun isAuthorizedPeer(connection: Connection, remoteId: String): Boolean {
            if (closed || !handler.authorize(remoteId)) {
                connection.close(4403L, "peer_not_allowed".encodeToByteArray())
                return false
            }
            return true
        }

        override fun close() {
            if (closed) return
            closed = true
            acceptJob?.cancel()
            servingJobs.forEach { it.cancel() }
            connections.forEach { runCatching { it.close(0L, "closed".encodeToByteArray()) } }
            connections.clear()
            parentScope.coroutineContext[Job]?.cancel()
            (handler as? AutoCloseable)?.close()
        }
    }

    /** The transport's engine and the dial bound to it. */
    private class IrohDialedEngine(val engine: AppServerTurnEngine, val dial: IrohBoundDial)

    /**
     * letta-mobile-by4vm.50: gap detection (and with it the busy/idle sync cadence, within
     * [syncBudget]) only against a host that advertises ordered event_seq; older hosts keep the
     * no-monitor behaviour, since their seq can reach the wire out of order.
     */
    internal fun bindIrohStreamIntegrity(
        router: AppServerRuntimeEventRouter,
        client: AppServerClient,
        hostCapabilities: Set<String>?,
        scope: CoroutineScope,
    ): Job? = StreamIntegrityBinding(client, { "iroh-sync-${UUID.randomUUID()}" }, syncBudget)
        .bindIfHostOrdersEventSeq(router, hostCapabilities, scope)

    private suspend fun closeIrohResources(reason: String, transport: IrohAppServerTransport?, endpoint: Endpoint?) {
        Telemetry.event("IrohTrace", "transport.close.resources", "reason" to reason)
        runCatching { transport?.close() }
        runCatching { endpoint?.shutdown() }
        runCatching { endpoint?.close() }
    }
}
