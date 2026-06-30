package com.letta.mobile.desktop.chat

import com.letta.mobile.data.controller.DefaultAppServerController
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json

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

        // Create the App Server endpoint
        val endpoint = AppServerEndpoint.fromWebSocketUrl(
            url = serverUrl,
            bearerToken = lettaConfig.accessToken,
        )

        // Create the WebSocket transport
        // The endpoint.address is the full WebSocket URL for WebSocket endpoints
        val transport = KtorAppServerWebSocketTransport(
            httpClient = createDesktopWsClient(),
            baseUrl = endpoint.address,
            scope = controllerScope,
            bearerToken = endpoint.bearerToken,
        )

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
            onClose = {
                controllerScope.cancel()
            }
        )
    }

    private fun createDesktopWsClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(desktopChatJson)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
        install(WebSockets)
    }
}
