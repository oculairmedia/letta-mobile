package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.letta.mobile.data.controller.DefaultAppServerController
import com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * CLI command to serve the Letta App Server over Iroh transport.
 * 
 * Creates an Iroh endpoint, starts accepting connections, and prints the dialable
 * ticket/NodeID so clients can connect. This command blocks until interrupted.
 * 
 * Example usage:
 * ```
 * ./gradlew :cli:run -PcliArgs="app-server-serve-iroh"
 * ```
 * 
 * The command will print output like:
 * ```
 * [iroh-app-server] Starting Iroh endpoint...
 * [iroh-app-server] Node ID: <64-char-hex>
 * [iroh-app-server] Ticket: <base64-encoded-ticket>
 * [iroh-app-server] Listening on Iroh... (Ctrl+C to stop)
 * ```
 */
internal class AppServerServeIrohCommand : CliktCommand(
    name = "app-server-serve-iroh",
) {
    private val appServerUrl by option(
        "--app-server-url",
        envvar = "LETTA_APP_SERVER_URL",
        help = "App Server WebSocket URL to wrap (for hybrid mode). If not specified, runs in Iroh-only mode.",
    )

    private val requestTimeout by option(
        "--request-timeout-ms",
        help = "Request timeout in milliseconds",
    ).default("120000")

    private val irohPort by option(
        "--iroh-port",
        envvar = "LETTA_IROH_PORT",
        help = "UDP port to bind the Iroh endpoint on. Pinning a port (plus a persisted " +
            "secret key) keeps the ticket/dial URL STABLE across restarts. 0 = random.",
    ).default("0")

    private val irohSecretKeyPath by option(
        "--iroh-secret-key-file",
        envvar = "LETTA_IROH_SECRET_KEY_FILE",
        help = "Path to a 32-byte secret key file. Generated (mode 600) on first run if " +
            "missing. Keeps the NodeID stable across restarts.",
    )

    private val authToken by option(
        "--auth-token",
        envvar = "LETTA_IROH_AUTH_TOKEN",
        help = "Optional bearer/invite token clients must present before runtime, input, sync, or admin_rpc.",
    )

    private val allowedPeerIds by option(
        "--allowed-peer-ids",
        envvar = "LETTA_IROH_ALLOWED_PEER_IDS",
        help = "Optional comma-separated allowlist of remote EndpointIds (64 hex chars).",
    ).default("")

    private val adminBaseUrl by option(
        "--admin-base-url",
        envvar = "LETTA_IROH_ADMIN_BASE_URL",
        help = "Base URL of the server-local HTTP API that admin_rpc methods proxy to " +
            "(conversation/message/agent reads). Server-side localhost only; clients " +
            "never dial it directly.",
    ).default("http://127.0.0.1:8291")

    override fun run() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        
        try {
            println("[iroh-app-server] Starting Iroh endpoint...")
            
            // Create the Iroh endpoint
            val irohEndpoint = IrohNodeEndpoint(
                scope = scope,
                bindAddr = "0.0.0.0:${irohPort}",
                secretKeyPath = irohSecretKeyPath,
                requiredBearerToken = authToken?.takeIf { it.isNotBlank() },
                allowedPeerIds = allowedPeerIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            )
            irohEndpoint.create()
            
            // Get the dialable information
            val nodeId = irohEndpoint.nodeIdHex()
            val ticket = irohEndpoint.ticketString()
            
            // Print the dialable information
            println("[iroh-app-server] Node ID: $nodeId")
            println("[iroh-app-server] Ticket: $ticket")
            // Short human-friendly dial form: iroh://<node-id>@<host:port>[,...]
            // Only meaningful when the port is pinned; with a random port it
            // rotates like the ticket does.
            val port = irohPort.toIntOrNull() ?: 0
            if (port > 0) {
                val lanAddrs = try {
                    java.net.NetworkInterface.getNetworkInterfaces().toList()
                        .filter { it.isUp && !it.isLoopback }
                        .flatMap { it.inetAddresses.toList() }
                        .filterIsInstance<java.net.Inet4Address>()
                        .map { "${it.hostAddress}:$port" }
                } catch (_: Exception) { emptyList() }
                if (lanAddrs.isNotEmpty()) {
                    println("[iroh-app-server] Short URL: iroh://$nodeId@${lanAddrs.joinToString(",")}")
                }
            }
            
            // Create the controller (using a stub implementation for now)
            // In a full implementation, this would connect to a real Letta App Server
            val controller = createController(appServerUrl, requestTimeout.toLong(), scope)

            // Register admin_rpc handlers so clients on an iroh:// backend can
            // read conversations/messages/agents WITHOUT any direct HTTP route
            // to this host (Iroh purity: letta-mobile-qfa81). The handlers
            // proxy to the server-local HTTP API; only this process dials it.
            val rpcBase = adminBaseUrl.trimEnd('/')
            com.letta.mobile.data.controller.node.iroh.HealthAdminHandlers.register(irohEndpoint.adminRpcRouter, rpcBase)
            com.letta.mobile.data.controller.node.iroh.AgentAdminHandlers.register(irohEndpoint.adminRpcRouter, rpcBase)
            com.letta.mobile.data.controller.node.iroh.ConversationAdminHandlers.register(irohEndpoint.adminRpcRouter, rpcBase)
            com.letta.mobile.data.controller.node.iroh.RunAdminHandlers.register(irohEndpoint.adminRpcRouter, rpcBase)
            com.letta.mobile.data.controller.node.iroh.ArchiveAdminHandlers.register(irohEndpoint.adminRpcRouter, rpcBase)
            com.letta.mobile.data.controller.node.iroh.IdentityAdminHandlers.register(irohEndpoint.adminRpcRouter, rpcBase)
            com.letta.mobile.data.controller.node.iroh.ModelAdminHandlers.register(irohEndpoint.adminRpcRouter, rpcBase)
            com.letta.mobile.data.controller.node.iroh.ScheduleAdminHandlers.register(irohEndpoint.adminRpcRouter, rpcBase)
            com.letta.mobile.data.controller.node.iroh.ToolAdminHandlers.register(irohEndpoint.adminRpcRouter, rpcBase)
            com.letta.mobile.data.controller.node.iroh.McpAdminHandlers.register(irohEndpoint.adminRpcRouter, rpcBase)
            println("[iroh-app-server] admin_rpc handlers registered (proxy base: $rpcBase)")

            // Start accepting connections
            irohEndpoint.start(controller)
            println("[iroh-app-server] Listening on Iroh... (Ctrl+C to stop)")
            
            // Keep the server running
            // In production, this would handle graceful shutdown signals
            Runtime.getRuntime().addShutdownHook(Thread {
                runBlocking {
                    println("\n[iroh-app-server] Shutting down...")
                    irohEndpoint.shutdown()
                    scope.cancel()
                }
            })
            
            // Wait indefinitely
            while (true) {
                delay(1000)
            }
        } catch (e: Exception) {
            System.err.println("[iroh-app-server] Error: ${e.message}")
            e.printStackTrace()
            scope.cancel()
            exitProcess(1)
        }
    }

    private fun createController(
        appServerUrl: String?,
        requestTimeoutMs: Long,
        scope: CoroutineScope,
    ): DefaultAppServerController {
        // If an app server URL is provided, create a client that wraps it
        // Otherwise, create a stub controller for testing
        return if (appServerUrl != null) {
            val httpClient = HttpClient(OkHttp) {
                install(WebSockets)
                install(HttpTimeout) {
                    this.requestTimeoutMillis = requestTimeoutMs
                    this.connectTimeoutMillis = 30_000
                    this.socketTimeoutMillis = requestTimeoutMs
                }
            }
            
            val transport = KtorAppServerWebSocketTransport(
                httpClient = httpClient,
                baseUrl = appServerUrl,
                scope = scope,
                bearerToken = null,
            )
            
            val client = DefaultAppServerClient(transport, requestTimeoutMs = requestTimeoutMs)
            DefaultAppServerController(client)
        } else {
            // Stub controller - the server side will return errors for now
            // This allows testing the Iroh transport layer without a full app server
            val httpClient = HttpClient(OkHttp) {
                install(WebSockets)
            }
            
            // Use a dummy WebSocket URL that won't be reached
            // The Iroh transport will handle the actual communication
            val transport = KtorAppServerWebSocketTransport(
                httpClient = httpClient,
                baseUrl = "ws://127.0.0.1:0",
                scope = scope,
                bearerToken = null,
            )
            
            val client = DefaultAppServerClient(transport, requestTimeoutMs = requestTimeoutMs)
            DefaultAppServerController(client)
        }
    }
}
