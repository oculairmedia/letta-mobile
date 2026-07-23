package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import com.letta.mobile.data.transport.appserver.AppServerTransport
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import com.letta.mobile.data.transport.iroh.IrohAppServerTransport
import com.letta.mobile.data.transport.iroh.IrohAppServerTransportAdapter
import com.letta.mobile.desktop.security.DesktopIrohIdentity
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * Factory for the desktop chat gateway backed by the App Server controller.
 *
 * Wires the controller stack (transport -> client -> controller) for either an
 * iroh:// backend (QUIC, peer to the Android path) or a WebSocket App Server,
 * authenticates the iroh session, and returns a hybrid gateway that routes
 * send/stream through the controller and listing/CRUD through HTTP.
 */
class DesktopAppServerControllerGatewayFactory(
    /** Coroutine scope for the controller and transport. */
    private val controllerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * Stable client identity source (d6e8g.4): the desktop dials with the same
     * vault-protected NodeId across restarts instead of an ephemeral keypair.
     */
    private val irohIdentity: () -> ByteArray = { DesktopIrohIdentity.loadOrCreate() },
) : DesktopAppServerChatGatewayFactory {

    override suspend fun create(
        lettaConfig: LettaConfig,
        appServerConfig: DesktopAppServerRuntimeConfig,
    ): DesktopChatGateway = withContext(Dispatchers.IO) {
        val serverUrl = appServerConfig.serverUrl
            ?: throw IllegalArgumentException(
                "App Server URL is required when ${DesktopAppServerRuntimeConfig.ENABLED_PROPERTY} is enabled. " +
                    "Set ${DesktopAppServerRuntimeConfig.SERVER_URL_PROPERTY} or " +
                    "${DesktopAppServerRuntimeConfig.SERVER_URL_ENV}.",
            )

        val (transport, transportResources) = if (IrohChannelTransport.isIrohUrl(serverUrl)) {
            buildIrohTransport(serverUrl, lettaConfig)
        } else {
            buildWebSocketTransport(serverUrl, lettaConfig)
        }

        val client = DefaultAppServerClient(transport)
        val turnEngine = buildDesktopAppServerTurnEngine(client)
        // The App Server doesn't expose conversation listing, message history,
        // agent CRUD, or the model catalog; those stay on HTTP.
        val httpGateway = DesktopLettaHttpChatGateway(
            config = lettaConfig,
            httpClient = createDesktopLettaHttpClient(),
        )
        DesktopHybridAppServerChatGateway(
            turnEngine = turnEngine,
            client = client,
            httpGateway = httpGateway,
            transportResources = transportResources,
        )
    }

    /**
     * iroh://<ticket> — bind a local iroh endpoint, dial the backend over QUIC,
     * wait for the connection, and run the auth/capability handshake. Any
     * failure between bind and the authed hand-off tears the endpoint and
     * transport down before rethrowing (mirrors IrohChannelTransport.dial).
     */
    private suspend fun buildIrohTransport(
        serverUrl: String,
        lettaConfig: LettaConfig,
    ): Pair<IrohAppServerTransport, DesktopTransportResources> {
        val normalizedAddress = IrohChannelTransport.normalizeIrohAddress(serverUrl)
        val irohEndpoint = Endpoint.bind(
            EndpointOptions(relayMode = RelayMode.defaultMode(), secretKey = irohIdentity()),
        )
        var resources: DesktopTransportResources? = null
        try {
            val irohTransport = IrohAppServerTransportAdapter(irohEndpoint).createTransport(
                endpoint = AppServerEndpoint(scheme = "iroh", address = normalizedAddress),
                scope = controllerScope,
            ) as IrohAppServerTransport
            resources = DesktopTransportResources(irohEndpoint, irohTransport)
            irohTransport.awaitConnectionReady()
            authenticateDesktopIrohAppServer(
                client = DefaultAppServerClient(irohTransport),
                accessToken = lettaConfig.accessToken,
            )
            return irohTransport to resources
        } catch (t: Throwable) {
            resources?.close() ?: run {
                runCatching { irohEndpoint.shutdown() }
                runCatching { irohEndpoint.close() }
            }
            throw t
        }
    }

    private fun buildWebSocketTransport(
        serverUrl: String,
        lettaConfig: LettaConfig,
    ): Pair<AppServerTransport, DesktopTransportResources> {
        val endpoint = AppServerEndpoint.fromWebSocketUrl(url = serverUrl, bearerToken = lettaConfig.accessToken)
        val httpClient = createDesktopLettaHttpClient()
        val transport = KtorAppServerWebSocketTransport(
            httpClient = httpClient,
            baseUrl = endpoint.address,
            scope = controllerScope,
            bearerToken = endpoint.bearerToken,
        )
        return transport to DesktopTransportResources.forWebSocket(transport, httpClient)
    }
}

/**
 * Desktop runs every turn Unrestricted: no approval UI, so a Standard-mode
 * approval_request would stall the turn; the engine auto-allows instead
 * (parity with the Android iroh engine). Baking the mode into the engine lets
 * ensureRuntime's single runtime_start carry it — no eager
 * controller.startRuntime, no double runtime_start on first send (#831 Codex P2).
 */
internal fun buildDesktopAppServerTurnEngine(client: AppServerClient): AppServerTurnEngine =
    AppServerTurnEngine(
        client = client,
        clientInfo = AppServerRuntimeStartClientInfo(
            name = "letta-desktop",
            title = "Letta Desktop",
            version = "0.2.0",
        ),
        permissionMode = AppServerPermissionMode.Unrestricted,
    )

/**
 * The iroh auth exchange doubles as the transport handshake: it advertises the
 * frame_part chunked-frame capability so the server may split >1MiB frames.
 * Sent even with a blank token — servers without a required token still ack
 * and record capabilities (mirrors IrohChannelTransport.kt dial() and the
 * CLI probe's tokenless handshake). A failure response is always fatal: the
 * in-repo server (IrohNodeConnection.handleAuth) returns success=true for
 * no-token servers, so success=false unambiguously means unauthenticated —
 * tolerating it would hand an unauthenticated transport onward and surface as
 * an opaque runtime_start timeout instead of a clear auth error.
 */
internal suspend fun authenticateDesktopIrohAppServer(
    client: AppServerClient,
    accessToken: String?,
    requestIdFactory: () -> String = { "desktop-auth-${UUID.randomUUID()}" },
) {
    val token = accessToken?.trim().orEmpty()
    val auth = client.auth(
        AppServerCommand.Auth(
            requestId = requestIdFactory(),
            token = token,
            capabilities = listOf(IrohFrameCodec.FRAME_PART_CAPABILITY),
        ),
    )
    if (!auth.success) {
        error("Desktop iroh App Server auth failed: ${auth.error ?: "unknown error"}")
    }
}
