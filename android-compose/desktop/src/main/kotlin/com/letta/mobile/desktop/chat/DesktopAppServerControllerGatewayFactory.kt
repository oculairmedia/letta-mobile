package com.letta.mobile.desktop.chat

import com.letta.mobile.data.controller.DefaultAppServerController
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.AppServerTransport
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import com.letta.mobile.data.transport.iroh.IrohAppServerTransport
import com.letta.mobile.data.transport.iroh.IrohAppServerTransportAdapter
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
            buildWebSocketTransport(serverUrl, lettaConfig) to null
        }

        val client = DefaultAppServerClient(transport)
        val controller = DefaultAppServerController(client)
        // The App Server doesn't expose conversation listing, message history,
        // agent CRUD, or the model catalog; those stay on HTTP.
        val httpGateway = DesktopLettaHttpChatGateway(
            config = lettaConfig,
            httpClient = createDesktopLettaHttpClient(),
        )
        DesktopHybridAppServerChatGateway(
            controller = controller,
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
    ): Pair<IrohAppServerTransport, DesktopIrohTransportResources> {
        val normalizedAddress = IrohChannelTransport.normalizeIrohAddress(serverUrl)
        val irohEndpoint = Endpoint.bind(EndpointOptions(relayMode = RelayMode.Companion.defaultMode()))
        var resources: DesktopIrohTransportResources? = null
        try {
            val irohTransport = IrohAppServerTransportAdapter(irohEndpoint).createTransport(
                endpoint = AppServerEndpoint(scheme = "iroh", address = normalizedAddress),
                scope = controllerScope,
            ) as IrohAppServerTransport
            resources = DesktopIrohTransportResources(irohEndpoint, irohTransport)
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
    ): AppServerTransport {
        val endpoint = AppServerEndpoint.fromWebSocketUrl(
            url = serverUrl,
            bearerToken = lettaConfig.accessToken,
        )
        return KtorAppServerWebSocketTransport(
            httpClient = createDesktopLettaHttpClient(),
            baseUrl = endpoint.address,
            scope = controllerScope,
            bearerToken = endpoint.bearerToken,
        )
    }
}

/**
 * The iroh auth exchange doubles as the transport handshake: it advertises the
 * frame_part chunked-frame capability so the server may split >1MiB frames.
 * Sent even with a blank token — servers without a required token still ack
 * and record capabilities (mirrors IrohChannelTransport.kt dial() and the
 * CLI probe's tokenless handshake).
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
    if (!auth.success && token.isNotBlank()) {
        error("Desktop iroh App Server auth failed: ${auth.error ?: "unknown error"}")
    }
}
