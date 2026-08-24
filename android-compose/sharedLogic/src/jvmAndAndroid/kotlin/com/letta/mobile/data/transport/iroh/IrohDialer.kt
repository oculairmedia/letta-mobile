package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.controller.node.iroh.EphemeralIrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore
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
import computer.iroh.RelayMode
import kotlinx.coroutines.CoroutineScope
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Encapsulates dialing, authentication, capabilities exchange, and resource wiring for Iroh connections.
 */
internal class IrohDialer(
    private val scope: CoroutineScope,
    private val secretKeyStore: IrohSecretKeyStore = EphemeralIrohSecretKeyStore(),
    private val onConnectionLost: (reason: String, handle: IrohConnectionHandle?) -> Unit,
    private val onCloseResources: (reason: String) -> Unit = {},
    private val bindEndpoint: suspend (ByteArray) -> Endpoint = { secretKey ->
        Endpoint.bind(EndpointOptions(relayMode = RelayMode.defaultMode(), secretKey = secretKey))
    },
) {
    suspend fun dial(
        config: IrohConnectConfig,
        effectiveUrlOverride: String? = null,
        onConnecting: () -> Unit = {},
    ): IrohConnectionHandle {
        val effectiveUrl = effectiveUrlOverride?.takeIf { it.isNotBlank() } ?: config.baseShimUrl
        if (!IrohChannelTransport.isIrohUrl(effectiveUrl)) {
            error("IrohChannelTransport requires backend URL iroh://<EndpointTicket>.")
        }
        val ticket = IrohChannelTransport.normalizeIrohAddress(effectiveUrl).takeIf { it.isNotBlank() }
            ?: error("IrohChannelTransport requires backend URL iroh://<EndpointTicket>.")
        onConnecting()
        val secretKey = secretKeyStore.loadOrCreate()
        return runCatching {
            val localEndpoint = runCatching {
                bindEndpoint(secretKey)
            }.onFailure { t ->
                Telemetry.event("IrohTransport", "bind.failed", "error" to (t.message ?: t.toString()), "class" to t::class.simpleName)
            }.getOrThrow()
            var transport: IrohAppServerTransport? = null
            val dialedHandle = AtomicReference<IrohConnectionHandle?>(null)
            try {
                val irohTransport = IrohAppServerTransportAdapter(
                    endpoint = localEndpoint,
                    onConnectionLost = { reason -> onConnectionLost(reason, dialedHandle.get()) },
                ).createTransport(
                    endpoint = AppServerEndpoint(scheme = "iroh", address = ticket),
                    scope = scope,
                ) as IrohAppServerTransport
                transport = irohTransport
                val appServerClient = DefaultAppServerClient(irohTransport)
                val auth = appServerClient.auth(
                    AppServerCommand.Auth(
                        requestId = "auth-${UUID.randomUUID()}",
                        token = config.token,
                        capabilities = listOf(IrohFrameCodec.FRAME_PART_CAPABILITY),
                    ),
                )
                if (!auth.success && config.token.isNotBlank()) {
                    throw IrohAuthFailure(auth.error ?: "Iroh auth failed")
                }
                Telemetry.event(
                    "IrohTransport", "auth.negotiated",
                    "success" to auth.success,
                    "serverCapabilities" to (auth.capabilities ?: emptyList()).sorted().joinToString(","),
                )
                irohTransport.awaitConnectionReady()
                val (engine, eventRouter) = buildIrohTurnEngine(
                    client = appServerClient,
                    clientVersion = config.clientVersion,
                    routerScope = scope,
                )
                IrohConnectionHandle(
                    config = config,
                    ticket = ticket,
                    sessionId = ticket.hashCode().toString(),
                    transport = irohTransport,
                    turnEngine = engine,
                    serverCapabilities = auth.capabilities?.toSet(),
                    close = { reason ->
                        eventRouter.detach()
                        onCloseResources(reason)
                        closeIrohResources(reason, irohTransport, localEndpoint)
                    },
                ).also { handle -> dialedHandle.set(handle) }
            } catch (error: Throwable) {
                closeIrohResources("dial_failed", transport, localEndpoint)
                throw error
            }
        }.onFailure {
            onCloseResources("dial_failed")
        }.getOrThrow()
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
        )
        return engine to eventRouter
    }

    private suspend fun closeIrohResources(reason: String, transport: IrohAppServerTransport?, endpoint: Endpoint?) {
        Telemetry.event("IrohTrace", "transport.close.resources", "reason" to reason)
        runCatching { transport?.close() }
        runCatching { endpoint?.shutdown() }
        runCatching { endpoint?.close() }
    }
}
