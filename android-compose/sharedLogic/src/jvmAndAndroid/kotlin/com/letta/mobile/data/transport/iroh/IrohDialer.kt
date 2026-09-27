package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.runtime.answerApprovalReplaysFrom
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.controller.node.iroh.EphemeralIrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.IrohNodeProtocolHandler
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.TurnContextPreflight
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
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
) {
    suspend fun dial(
        config: IrohConnectConfig,
        effectiveUrlOverride: String? = null,
        onConnecting: () -> Unit = {},
    ): IrohConnectionHandle {
        val ticket = extractTicket(config, effectiveUrlOverride)
        onConnecting()
        val secretKey = secretKeyStore.loadOrCreate()
        return runCatching {
            val localEndpoint = bindLocalEndpoint(secretKey)
            var transport: IrohAppServerTransport? = null
            var notebook: InboundNotebook? = null
            try {
                if (notebookHandlerFactory != null) {
                    val notebookScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
                    try {
                        notebook = notebookHandlerFactory.invoke(localEndpoint, notebookScope)?.let { handler ->
                            try {
                                require(handler.alpn.contentEquals(AUTOMERGE_REPO_ALPN)) { "Unexpected notebook ALPN" }
                                InboundNotebook(localEndpoint, handler, notebookScope).also { it.start() }
                            } catch (error: Throwable) {
                                (handler as? AutoCloseable)?.close()
                                throw error
                            }
                        }
                    } finally {
                        if (notebook == null) notebookScope.coroutineContext[Job]?.cancel()
                    }
                }
                val dialedHandle = AtomicReference<IrohConnectionHandle?>(null)
                val irohTransport = createTransport(localEndpoint, ticket, dialedHandle)
                transport = irohTransport
                val appServerClient = DefaultAppServerClient(irohTransport)
                val serverCapabilities = authenticateClient(appServerClient, config.token)
                irohTransport.awaitConnectionReady()
                val (engine, eventRouter) = buildIrohTurnEngine(appServerClient, config.clientVersion, scope)
                buildHandle(
                    HandleComponents(config, ticket, irohTransport, engine, serverCapabilities, eventRouter, localEndpoint, notebook),
                ).also { dialedHandle.set(it) }
            } catch (error: Throwable) {
                notebook?.close()
                closeIrohResources("dial_failed", transport, localEndpoint)
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
        if (!auth.success && token.isNotBlank()) {
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
        turnEngine = components.engine,
        serverCapabilities = components.serverCapabilities,
        openConnection = { alpn ->
            components.localEndpoint.connect(IrohAppServerTransportAdapter.parseIrohAddress(components.ticket), alpn)
        },
        close = { reason ->
            components.notebook?.close()
            components.eventRouter.detach()
            onCloseResources(reason)
            closeIrohResources(reason, components.transport, components.localEndpoint)
        },
    )

    private data class HandleComponents(
        val config: IrohConnectConfig,
        val ticket: String,
        val transport: IrohAppServerTransport,
        val engine: AppServerTurnEngine,
        val serverCapabilities: Set<String>?,
        val eventRouter: AppServerRuntimeEventRouter,
        val localEndpoint: Endpoint,
        val notebook: InboundNotebook?,
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
                while (isActive) {
                    try {
                        val incoming = endpoint.acceptNext()
                        if (incoming == null) {
                            delay(100)
                            continue
                        }
                        launch { serve(incoming) }.also { child ->
                            servingJobs.add(child)
                            child.invokeOnCompletion { servingJobs.remove(child) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!isActive) break
                        Telemetry.event("IrohTransport", "notebook.accept.failed", "error" to (e.message ?: e.toString()))
                        delay(100)
                    }
                }
            }
        }

        private suspend fun serve(incoming: Incoming) {
            var connection: Connection? = null
            try {
                val accepting = incoming.accept()
                if (!accepting.alpn().contentEquals(AUTOMERGE_REPO_ALPN)) return
                connection = withTimeout(15_000) { accepting.connect() }
                val remoteId = IrohDiagnostics.endpointIdHex(connection.remoteId())
                if (closed || !handler.authorize(remoteId)) {
                    connection.close(4403L, "peer_not_allowed".encodeToByteArray())
                    return
                }
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

    private fun buildIrohTurnEngine(
        client: DefaultAppServerClient,
        clientVersion: String,
        routerScope: CoroutineScope,
    ): Pair<AppServerTurnEngine, AppServerRuntimeEventRouter> {
        val eventRouter = AppServerRuntimeEventRouter()
        eventRouter.attach(routerScope, client.events)
        val engine = AppServerTurnEngine(
            client = client,
            clientInfo = AppServerRuntimeStartClientInfo(
                name = "letta-mobile-android-iroh",
                version = clientVersion,
            ),
            permissionMode = AppServerPermissionMode.Unrestricted,
            turnContextPreflight = TurnContextPreflight.None,
            eventRouter = eventRouter,
            externalToolRegistry = externalToolRegistry,
        )
        engine.answerApprovalReplaysFrom(eventRouter, routerScope)
        return engine to eventRouter
    }

    private suspend fun closeIrohResources(reason: String, transport: IrohAppServerTransport?, endpoint: Endpoint?) {
        Telemetry.event("IrohTrace", "transport.close.resources", "reason" to reason)
        runCatching { transport?.close() }
        runCatching { endpoint?.shutdown() }
        runCatching { endpoint?.close() }
    }
}
