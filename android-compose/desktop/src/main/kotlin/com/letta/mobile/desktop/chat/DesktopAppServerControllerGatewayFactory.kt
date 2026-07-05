package com.letta.mobile.desktop.chat

import com.letta.mobile.data.controller.DefaultAppServerController
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import com.letta.mobile.data.transport.iroh.IrohAppServerTransport
import com.letta.mobile.data.transport.iroh.IrohAppServerTransportAdapter
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * Factory for creating a desktop chat gateway backed by the App Server controller.
 *
 * This factory wires the controller stack (transport -> client -> controller) and
 * returns a gateway that routes chat operations through the App Server instead of
 * direct HTTP/SSE to the Letta backend.
 *
 * USAGE:
 * ```kotlin
 * val factory = DesktopAppServerControllerGatewayFactory()
 * val gateway = factory.create(lettaConfig, appServerConfig)
 * ```
 *
 * CURRENT STATUS:
 * This is the wiring/seam implementation for bead letta-mobile-xa2xc.9.
 * The gateway currently DELEGATES to HTTP for most operations while demonstrating
 * controller instantiation and wiring. Full App Server-backed operations (list
 * conversations, messages, etc.) require App Server API extensions and are tracked
 * as follow-up work.
 */
class DesktopAppServerControllerGatewayFactory(
    /**
     * Coroutine scope for the controller and transport.
     * Defaults to a supervised scope with IO dispatcher.
     */
    private val controllerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : DesktopAppServerChatGatewayFactory {

    override fun create(
        lettaConfig: LettaConfig,
        appServerConfig: DesktopAppServerRuntimeConfig,
    ): DesktopChatGateway {
        // Validate configuration
        val serverUrl = appServerConfig.serverUrl
            ?: throw IllegalArgumentException(
                "App Server URL is required when ${DesktopAppServerRuntimeConfig.ENABLED_PROPERTY} is enabled. " +
                    "Set ${DesktopAppServerRuntimeConfig.SERVER_URL_PROPERTY} or " +
                    "${DesktopAppServerRuntimeConfig.SERVER_URL_ENV}."
            )

        // Select the transport by scheme. Desktop is a peer host to Android, so it
        // supports the same iroh:// backend as mobile — the Iroh client transport
        // (IrohAppServerTransportAdapter) lives in jvmAndAndroid, which desktop
        // (jvmMain) consumes, and the computer.iroh:iroh JAR bundles the host-OS
        // native lib. letta-mobile-cq2ju.
        val transport = if (IrohChannelTransport.isIrohUrl(serverUrl)) {
            // iroh://<node-id>@<host:port>[,...] — bind a local iroh endpoint and
            // dial the backend over QUIC, mirroring the CLI probe + Android path.
            // Endpoint.bind + awaitConnectionReady are suspend; run them once at
            // wiring time.
            val normalizedAddress = serverUrl.trim().removePrefix("iroh://")
            runBlocking {
                val irohEndpoint = Endpoint.bind(EndpointOptions(relayMode = RelayMode.Companion.defaultMode()))
                val irohTransport = IrohAppServerTransportAdapter(irohEndpoint).createTransport(
                    endpoint = AppServerEndpoint(scheme = "iroh", address = normalizedAddress),
                    scope = controllerScope,
                ) as IrohAppServerTransport
                // The controller/client contract needs the connection ready before use.
                irohTransport.awaitConnectionReady()
                irohTransport
            }
        } else {
            // Default: WebSocket App Server. endpoint.address is the full WS URL.
            val endpoint = AppServerEndpoint.fromWebSocketUrl(
                url = serverUrl,
                bearerToken = lettaConfig.accessToken,
            )
            KtorAppServerWebSocketTransport(
                httpClient = createDesktopLettaHttpClient(),
                baseUrl = endpoint.address,
                scope = controllerScope,
                bearerToken = endpoint.bearerToken,
            )
        }

        // Create the App Server client
        val client = DefaultAppServerClient(transport)

        // Create the controller
        val controller = DefaultAppServerController(client)

        // Create the HTTP gateway for delegation
        // The App Server doesn't yet expose conversation listing, message history,
        // agent CRUD, or model catalog APIs, so we delegate those to HTTP.
        val httpGateway = DesktopLettaHttpChatGateway(
            config = lettaConfig,
            httpClient = createDesktopLettaHttpClient(),
        )

        // Return a hybrid gateway that uses the controller for send/stream
        // and HTTP for everything else
        return DesktopHybridAppServerChatGateway(
            controller = controller,
            httpGateway = httpGateway,
        )
    }
}
