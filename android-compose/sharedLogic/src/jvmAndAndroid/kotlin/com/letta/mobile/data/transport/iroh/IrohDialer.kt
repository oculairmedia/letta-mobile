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
        val ticket = extractTicket(config, effectiveUrlOverride)
        onConnecting()
        return runCatching {
            val endpoint = bindLocalEndpoint(secretKeyStore.loadOrCreate())
            dialEndpoint(config, ticket, endpoint)
        }.onFailure { onCloseResources("dial_failed") }
            .getOrThrow()
    }

    private fun extractTicket(config: IrohConnectConfig, override: String?): String {
        val url = override?.takeIf { it.isNotBlank() } ?: config.baseShimUrl
        check(IrohChannelTransport.isIrohUrl(url)) { "IrohChannelTransport requires backend URL iroh://<EndpointTicket>." }
        return IrohChannelTransport.normalizeIrohAddress(url).takeIf { it.isNotBlank() }
            ?: error("IrohChannelTransport requires backend URL iroh://<EndpointTicket>.")
    }

    private suspend fun bindLocalEndpoint(secretKey: ByteArray): Endpoint = runCatching { bindEndpoint(secretKey) }
        .onFailure { error ->
            Telemetry.event("IrohTransport", "bind.failed", "error" to (error.message ?: error.toString()), "class" to error::class.simpleName)
        }.getOrThrow()

    private suspend fun dialEndpoint(config: IrohConnectConfig, ticket: String, endpoint: Endpoint): IrohConnectionHandle {
        var transport: IrohAppServerTransport? = null
        try {
            val holder = AtomicReference<IrohConnectionHandle?>(null)
            val connected = createTransport(endpoint, ticket, holder)
            transport = connected
            val client = DefaultAppServerClient(connected)
            val capabilities = authenticate(client, config.token)
            connected.awaitConnectionReady()
            val (engine, router) = buildIrohTurnEngine(client, config.clientVersion, scope)
            return IrohConnectionHandle(
                config = config,
                ticket = ticket,
                sessionId = ticket.hashCode().toString(),
                transport = connected,
                turnEngine = engine,
                serverCapabilities = capabilities,
                close = { reason -> router.detach(); onCloseResources(reason); closeIrohResources(reason, connected, endpoint) },
            ).also { holder.set(it) }
        } catch (error: Throwable) {
            closeIrohResources("dial_failed", transport, endpoint)
            throw error
        }
    }

    private fun createTransport(endpoint: Endpoint, ticket: String, holder: AtomicReference<IrohConnectionHandle?>): IrohAppServerTransport =
        IrohAppServerTransportAdapter(endpoint = endpoint, onConnectionLost = { reason -> onConnectionLost(reason, holder.get()) })
            .createTransport(AppServerEndpoint(scheme = "iroh", address = ticket), scope) as IrohAppServerTransport

    private suspend fun authenticate(client: DefaultAppServerClient, token: String): Set<String>? {
        val auth = client.auth(AppServerCommand.Auth("auth-${UUID.randomUUID()}", token, listOf(IrohFrameCodec.FRAME_PART_CAPABILITY)))
        if (!auth.success && token.isNotBlank()) throw IrohAuthFailure(auth.error ?: "Iroh auth failed")
        Telemetry.event("IrohTransport", "auth.negotiated", "success" to auth.success, "serverCapabilities" to (auth.capabilities ?: emptyList()).sorted().joinToString(","))
        return auth.capabilities?.toSet()
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
