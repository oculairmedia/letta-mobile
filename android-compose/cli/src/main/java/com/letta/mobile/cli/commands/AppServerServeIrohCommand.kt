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

    override fun run() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        
        try {
            println("[iroh-app-server] Starting Iroh endpoint...")
            
            // Create the Iroh endpoint
            val irohEndpoint = IrohNodeEndpoint(scope = scope)
            irohEndpoint.create()
            
            // Get the dialable information
            val nodeId = irohEndpoint.nodeIdHex()
            val ticket = irohEndpoint.ticketString()
            
            // Print the dialable information
            println("[iroh-app-server] Node ID: $nodeId")
            println("[iroh-app-server] Ticket: $ticket")
            
            // Create the controller (using a stub implementation for now)
            // In a full implementation, this would connect to a real Letta App Server
            val controller = createController(appServerUrl, requestTimeout.toLong(), scope)
            
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
