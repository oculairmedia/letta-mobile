package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.letta.mobile.data.controller.DefaultAppServerController
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.reconnect.AppServerClientGeneration
import com.letta.mobile.data.controller.reconnect.ReconnectCoordinator
import com.letta.mobile.data.controller.reconnect.ReconnectingAppServerClient
import com.letta.mobile.data.controller.reconnect.ReconnectingClientListener
import com.letta.mobile.data.controller.registry.InMemoryRuntimeRegistry
import com.letta.mobile.data.controller.node.iroh.AdminRpcRegistry
import com.letta.mobile.data.controller.node.iroh.FilePairedPeerStore
import com.letta.mobile.data.controller.node.iroh.IrohAuthPolicy
import com.letta.mobile.data.controller.node.iroh.IrohAuthPolicyResolution
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint
import com.letta.mobile.data.controller.node.iroh.SubagentRegistrySource
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

import kotlin.time.Duration.Companion.seconds
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
internal fun buildProductionAdminRouter(
    adminBaseUrl: String,
    controller: DefaultAppServerController,
    subagentRegistrySource: SubagentRegistrySource?,
    pairingService: com.letta.mobile.data.controller.node.iroh.IrohPairingService? = null,
    nativeClient: com.letta.mobile.data.transport.appserver.AppServerClient? = null,
    vibesyncBaseUrl: String? = null,
): AdminRpcRouter = AdminRpcRegistry.buildRouter(adminBaseUrl, controller, subagentRegistrySource, pairingService, nativeClient, vibesyncBaseUrl = vibesyncBaseUrl)

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

    private val vibesyncBaseUrl by option(
        "--vibesync-base-url",
        envvar = "LETTA_IROH_VIBESYNC_BASE_URL",
        help = "Base URL of the VibeSync product service that project.* methods call " +
            "DIRECTLY (lgns8.9), bypassing the lettashim /api reverse-proxy splice. " +
            "Server-side localhost only.",
    ).default("http://127.0.0.1:3099")

    private val adminBaseUrl by option(
        "--admin-base-url",
        envvar = "LETTA_IROH_ADMIN_BASE_URL",
        help = "Base URL of the server-local HTTP API that admin_rpc methods proxy to " +
            "(conversation/message/agent reads). Server-side localhost only; clients " +
            "never dial it directly.",
    ).default("http://127.0.0.1:8291")

    private val pairingStoreFile by option(
        "--pairing-store-file",
        envvar = "LETTA_IROH_PAIRING_STORE",
        help = "Path to the paired-peer JSON store (d6e8g.5). When set, paired NodeIds " +
            "authenticate without a token and one-time invites can be minted via " +
            "pair.invite.create; redeem with an 'invite:<secret>' auth token.",
    )

    private val allowInsecureAnonymousIroh by option(
        "--allow-insecure-anonymous-iroh",
        help = "TEST/DEV ONLY: run the Iroh endpoint with NO authentication. Every peer that " +
            "can dial the ticket gets full runtime and admin access. Prohibited for release " +
            "or long-running service use; a warning is printed on every start.",
    ).flag(default = false)

    private val ownAppServer by option(
        "--own-app-server",
        envvar = "LETTA_OWN_APP_SERVER",
        help = "lgns8.18 (desktop/bundled): spawn 'letta app-server' as an OWNED child " +
            "process on an ephemeral loopback port and wrap it, instead of connecting to an " +
            "external --app-server-url. Gives deterministic lifecycle (child death = process " +
            "exit) with no external supervisor. NOT for the systemd server (keep external-ws " +
            "there). Overrides --app-server-url when set.",
    ).flag(default = false)

    private val lettaCommand by option(
        "--letta-command",
        envvar = "LETTA_COMMAND",
        help = "Executable used to spawn the owned App Server child (default 'letta'). " +
            "Only used with --own-app-server.",
    ).default("letta")

    override fun run() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        
        try {
            // d6e8g.2: fail closed — refuse anonymous startup unless explicitly
            // opted into via the loudly named test/dev-only flag.
            val authPolicy = when (
                val resolution = IrohAuthPolicy.resolve(
                    authToken = authToken,
                    allowedPeerIds = allowedPeerIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                    allowInsecureAnonymous = allowInsecureAnonymousIroh,
                )
            ) {
                is IrohAuthPolicyResolution.Secure -> resolution.policy
                is IrohAuthPolicyResolution.InsecureAccepted -> {
                    System.err.println("[iroh-app-server] ${resolution.warning}")
                    resolution.policy
                }
                is IrohAuthPolicyResolution.Refused -> {
                    System.err.println("[iroh-app-server] ${resolution.error}")
                    exitProcess(78)
                }
            }

            val pairingService = pairingStoreFile?.let { storePath ->
                println("[iroh-app-server] Pairing enabled (store: $storePath)")
                IrohPairingService(FilePairedPeerStore(java.nio.file.Path.of(storePath)))
            }

            println("[iroh-app-server] Starting Iroh endpoint...")
            
            // Create the Iroh endpoint
            val irohEndpoint = IrohNodeEndpoint(
                scope = scope,
                bindAddr = "0.0.0.0:${irohPort}",
                secretKeyPath = irohSecretKeyPath,
                authPolicy = authPolicy,
                pairingService = pairingService,
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
                    java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                        .filter { it.isUp && !it.isLoopback }
                        .flatMap { it.inetAddresses.asSequence() }
                        .filterIsInstance<java.net.Inet4Address>()
                        .map { "${it.hostAddress}:$port" }
                        .toList()
                } catch (_: Exception) { emptyList() }
                if (lanAddrs.isNotEmpty()) {
                    println("[iroh-app-server] Short URL: iroh://$nodeId@${lanAddrs.joinToString(",")}")
                }
            }
            
            // lgns8.18 (Path A, desktop): optionally spawn + OWN the App Server child
            // on an ephemeral loopback port, instead of connecting to an external URL.
            val ownedServer = if (ownAppServer) {
                println("[iroh-app-server] Spawning owned App Server child ($lettaCommand app-server)...")
                val owned = com.letta.mobile.cli.appserver.OwnedAppServerProcess.spawn(
                    command = com.letta.mobile.cli.appserver.OwnedAppServerProcess.buildCommand(lettaCommand),
                    log = { System.err.println(it) },
                )
                Runtime.getRuntime().addShutdownHook(Thread { owned.close() })
                println("[iroh-app-server] Owned App Server ready at ${owned.wsBaseUrl}")
                // Deterministic lifecycle: if the owned child dies, take the wrapper
                // down with it so the pair restarts together — no orphaned half-stack.
                scope.launch {
                    val code = withContext(Dispatchers.IO) { owned.process.waitFor() }
                    System.err.println("[iroh-app-server] Owned App Server child exited (code $code); shutting down wrapper.")
                    exitProcess(if (code == 0) 0 else 70)
                }
                owned
            } else {
                null
            }
            val effectiveAppServerUrl = ownedServer?.wsBaseUrl ?: appServerUrl

            // Create the controller. With --own-app-server this connects the WS
            // transport to the owned loopback child; otherwise to the external URL
            // (null = Iroh-only/stub mode).
            val (controller, nativeAdminClient) = createController(effectiveAppServerUrl, requestTimeout.toLong(), scope)

            // Register admin_rpc handlers so clients on an iroh:// backend can
            // read conversations/messages/agents WITHOUT any direct HTTP route
            // to this host (Iroh purity: letta-mobile-qfa81). The handlers
            // proxy to the server-local HTTP API; only this process dials it.
            val rpcBase = adminBaseUrl.trimEnd('/')
            val subagentRegistrySource =
                com.letta.mobile.data.controller.node.iroh.HttpSubagentRegistrySource.discover(rpcBase)
            val adminRpcRouter = buildProductionAdminRouter(rpcBase, controller, subagentRegistrySource, pairingService, nativeAdminClient, vibesyncBaseUrl)
            irohEndpoint.adminRpcRouter.copyHandlersFrom(adminRpcRouter)
            println(
                "[iroh-app-server] admin_rpc handlers registered " +
                    "(proxy base: $rpcBase, methods: ${adminRpcRouter.methodCount}, " +
                    "subagent_registry_v1: ${subagentRegistrySource != null})",
            )

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
                delay(1.seconds)
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
    ): Pair<DefaultAppServerController, com.letta.mobile.data.transport.appserver.AppServerClient?> {
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

            // lgns8.5: the controller holds ONE stable client; underneath, each
            // socket loss or App Server restart mints a fresh transport
            // generation with bounded full-jitter backoff. On loss the
            // controller's runtime caches are invalidated; on recovery every
            // registered runtime is reattached (runtime_start), external tools
            // re-registered, and sync issued with approval/device recovery
            // before the client reports Ready again.
            val runtimeRegistry = InMemoryRuntimeRegistry()
            var controllerRef: DefaultAppServerController? = null
            var coordinatorRef: ReconnectCoordinator? = null
            val reconnectingClient = ReconnectingAppServerClient(
                connect = {
                    val generationJob = Job(scope.coroutineContext.job)
                    val generationScope = CoroutineScope(scope.coroutineContext + generationJob)
                    val transport = KtorAppServerWebSocketTransport(
                        httpClient = httpClient,
                        baseUrl = appServerUrl,
                        scope = generationScope,
                        bearerToken = null,
                    )
                    AppServerClientGeneration(
                        client = DefaultAppServerClient(
                            transport,
                            requestTimeoutMs = requestTimeoutMs,
                            parentScope = generationScope,
                        ),
                        connectionState = transport.connectionState,
                        close = { reason -> generationJob.cancel(kotlinx.coroutines.CancellationException(reason)) },
                    )
                },
                listener = object : ReconnectingClientListener {
                    override suspend fun onDisconnected(reason: String?) {
                        println("[iroh-app-server] App Server connection lost: ${reason ?: "unknown"}")
                        controllerRef?.onTransportDisconnected(reason)
                    }

                    override suspend fun onRecovered(client: com.letta.mobile.data.transport.appserver.AppServerClient) {
                        val result = coordinatorRef?.reconnect()
                        if (result != null && result.errors.isNotEmpty()) {
                            result.errors.forEach {
                                System.err.println("[iroh-app-server] reattach failed: ${it.message}")
                            }
                        }
                        controllerRef?.markConnected()
                        println(
                            "[iroh-app-server] App Server connection recovered " +
                                "(reattached runtimes: ${result?.reconnectedCount ?: 0})",
                        )
                    }

                    override suspend fun onGaveUp(reason: String?) {
                        System.err.println("[iroh-app-server] App Server reconnect gave up: ${reason ?: "unknown"}")
                    }
                },
            )
            val controller = DefaultAppServerController(
                client = reconnectingClient,
                runtimeRegistry = runtimeRegistry,
                // lgns8.17: give the turn engine a registry so it can execute
                // controller-owned tools; independently, the engine GUARANTEES a
                // matched external_tool_call_response for every request (a
                // synthesized is_error when a tool isn't handled here) so a
                // tool-call turn over the App Server WS route never hangs.
                externalToolRegistry = ExternalToolRegistry.factoryDefault(),
            )
            controllerRef = controller
            coordinatorRef = ReconnectCoordinator(controller, runtimeRegistry)
            reconnectingClient.start(scope)
            // lgns8.7: the reconnecting client doubles as the native admin
            // command channel for runtime-native admin_rpc handlers.
            controller to reconnectingClient
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
            DefaultAppServerController(client) to null
        }
    }
}
