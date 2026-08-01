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
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.FilePairedPeerStore
import com.letta.mobile.data.controller.node.iroh.HostSkillsEnumerator
import com.letta.mobile.data.controller.node.iroh.IrohAuthPolicy
import com.letta.mobile.data.controller.node.iroh.IrohAuthPolicyResolution
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.SubagentRegistrySource
import com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint
import com.letta.mobile.data.runtime.AppServerContextWindowPreflight
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import com.letta.mobile.data.transport.appserver.applyAppServerFrameLimits
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
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
fun buildProductionAdminRouter(
    controller: DefaultAppServerController,
    subagentRegistrySource: SubagentRegistrySource? = null,
    /**
     * lgns8.22.8: when set, the controller-native subagent registry is backed by
     * this JSON file, so chips survive a controller restart and are reconciled
     * against live state on the next authoritative snapshot. Unset keeps the
     * previous in-memory-only behaviour.
     */
    subagentRegistryFile: String? = null,
    pairingService: com.letta.mobile.data.controller.node.iroh.IrohPairingService? = null,
    nativeClient: com.letta.mobile.data.transport.appserver.AppServerClient? = null,
    vibesyncBaseUrl: String? = null,
    /**
     * lgns8.9: the letta-code on-disk backend root. Admin READS the App Server
     * exposes no command for (run/step history, agent context, memory blocks)
     * are served READ-ONLY from it — the same directory lettashim read. Unset =>
     * those methods fail closed; there is no HTTP admin fallback any more.
     */
    localBackendDir: String? = System.getenv("LETTA_LOCAL_BACKEND_DIR"),
    /**
     * letta-mobile-7dm1q / lgns8.21.2: the letta-code skills root. letta-code
     * 0.29.12 advertises no skill enumeration on the wire, so without a host-side
     * enumerator `skill.list` answers `hydrated=false` forever and the Skills
     * screen stays empty. Enumerating this directory at startup is that missing
     * authoritative source. Unset => `LETTA_SKILLS_DIR` => `~/.letta/skills`.
     */
    skillsDir: String? = null,
    eventScope: CoroutineScope? = null,
): AdminRpcRouter {
    val skillsCatalog = com.letta.mobile.data.controller.node.iroh.NativeSkillsCatalog()
    // Cold-start discovery: hydrate BEFORE the router is built, so the very first
    // skill.list after a restart is already authoritative (lgns8.21.2 AC:
    // "discovery at cold start" + "preserved across restart" — the skills root is
    // on disk, so re-enumerating on every boot preserves it by construction).
    val resolvedSkillsDir = com.letta.mobile.data.controller.node.iroh.HostSkillsEnumerator
        .resolveSkillsDir(skillsDir)
    com.letta.mobile.data.controller.node.iroh.HostSkillsEnumerator.enumerate(resolvedSkillsDir)
        ?.let { enumerated ->
            skillsCatalog.hydrateFromHost(enumerated)
            com.letta.mobile.util.Telemetry.event(
                "SkillsCatalog",
                "host.hydrated",
                "skillsDir" to resolvedSkillsDir,
                "skills" to enumerated.size.toString(),
            )
        }
        ?: com.letta.mobile.util.Telemetry.event(
            "SkillsCatalog",
            "host.root_missing",
            "skillsDir" to resolvedSkillsDir,
        )
    val subagentStore = subagentRegistryFile
        ?.let { com.letta.mobile.data.subagents.FileSubagentRegistryStore(java.nio.file.Path.of(it)) }
        ?: com.letta.mobile.data.subagents.InMemorySubagentRegistryStore()
    val subagentSource = subagentRegistrySource
        ?: com.letta.mobile.data.controller.node.iroh.ControllerSubagentRegistrySource(
            com.letta.mobile.data.subagents.DurableSubagentRegistry(store = subagentStore),
        ).also { source ->
            if (nativeClient != null && eventScope != null) {
                source.start(eventScope, nativeClient.events)
            }
        }
    if (nativeClient != null && eventScope != null) {
        skillsCatalog.start(eventScope, nativeClient.events)
    }
    return AdminRpcRegistry.buildRouter(
        controller = controller,
        subagentRegistrySource = subagentSource,
        pairingService = pairingService,
        nativeClient = nativeClient,
        vibesyncBaseUrl = vibesyncBaseUrl,
        localBackendDir = localBackendDir,
        skillsListing = skillsCatalog.asListingSource(),
    )
}

class AppServerServeIrohCommand : CliktCommand(
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

    private val pairingStoreFile by option(
        "--pairing-store-file",
        envvar = "LETTA_IROH_PAIRING_STORE",
        help = "Path to the paired-peer JSON store (d6e8g.5). When set, paired NodeIds " +
            "authenticate without a token and one-time invites can be minted via " +
            "pair.invite.create; redeem with an 'invite:<secret>' auth token.",
    )

    private val subagentRegistryFile by option(
        "--subagent-registry-file",
        envvar = "LETTA_SUBAGENT_REGISTRY_STORE",
        help = "Path to the durable subagent-chip registry JSON store (lgns8.22.8). When set, " +
            "chips survive a controller restart and are reconciled against live state on the " +
            "next authoritative subagent snapshot (orphans become terminal, never spinners).",
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

    /**
     * lgns8.23: take channels-host ownership in the controller.
     *
     * OFF by default because production still runs lettashim as the channels
     * host (SHIM_CHANNELS_ENABLED=1). Enabling both hosts would double-start the
     * same accounts against the same homeserver. Cutover is a single maintenance
     * step: set SHIM_CHANNELS_ENABLED=0 on lettashim, then start the wrapper with
     * --channels-host. See docs/architecture/lettashim-retirement-deployment-runbook.md.
     */
    private val channelsHost by option(
        "--channels-host",
        envvar = "LETTA_CHANNELS_HOST",
        help = "lgns8.23: restore enabled channel accounts (channels_list -> " +
            "channel_accounts_list -> channel_start) on every App Server connect and " +
            "reconnect, making this controller the channels host. Requires lettashim's " +
            "SHIM_CHANNELS_ENABLED=0 — running both hosts double-starts accounts.",
    ).flag(default = false)

    /**
     * letta-mobile-7dm1q: the letta-code skills root, enumerated at startup to
     * hydrate the native skills catalog. Without it `skill.list` reports
     * `hydrated=false` forever, because letta-code 0.29.12 exposes no skill
     * enumeration on the wire.
     */
    private val skillsDir by option(
        "--skills-dir",
        envvar = HostSkillsEnumerator.SKILLS_DIR_ENV,
        help = "letta-code skills root to enumerate for skill.list " +
            "(one directory per skill, each with a SKILL.md). " +
            "Defaults to ~/.letta/skills.",
    )

    /**
     * lgns8.9 made `local_backend_store` the declared owner of six READ-ONLY admin
     * reads (run/step history, agent context, memory blocks), but the shim-free
     * deployment template forbids `LETTA_LOCAL_BACKEND_DIR` in the wrapper env
     * (correctly — the wrapper must not inherit `appserver.env`). The result after
     * the 2026-08-01 cutover was that those six methods denied with
     * `capability_unavailable: no injected 'local_backend_store' service` in
     * production — observed live for `agent.context` and, with it, `block.list`,
     * which is why the memory surfaces went blank.
     *
     * An explicit flag resolves both constraints: the wrapper declares its own
     * read-only root in the committable unit file instead of inheriting the App
     * Server's environment. Reads only — this process never opens the root for
     * writing (the epic's one-writer-per-root constraint).
     */
    private val localBackendDir by option(
        "--local-backend-dir",
        envvar = "LETTA_LOCAL_BACKEND_DIR",
        help = "READ-ONLY letta-code local-backend root for the store-tier admin reads " +
            "(block.list/get, agent.context, run/step history). Unset => those methods fail closed.",
    )

    /**
     * o5bqk: process-lifetime cache of the last-known enabled channel accounts, so
     * every reconnect can re-wire channel ingress in its first round trip instead
     * of after a full enumeration. Held here (not in the coordinator) because a
     * fresh coordinator is built per App Server generation.
     */
    private val channelAccountCache =
        com.letta.mobile.data.controller.channels.InMemoryChannelAccountCache()

    private val lettaCommand by option(
        "--letta-command",
        envvar = "LETTA_COMMAND",
        help = "Executable used to spawn the owned App Server child (default 'letta'). " +
            "Only used with --own-app-server.",
    ).default("letta")

    override fun run() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        
        try {
            val authPolicy = resolveAuthPolicy()

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
            
            printDialInfo(irohEndpoint)

            // lgns8.18 (Path A, desktop): optionally spawn + OWN the App Server child
            // on an ephemeral loopback port, instead of connecting to an external URL.
            val ownedServer = maybeSpawnOwnedAppServer(scope)
            val effectiveAppServerUrl = ownedServer?.wsBaseUrl ?: appServerUrl

            // Create the controller. With --own-app-server this connects the WS
            // transport to the owned loopback child; otherwise to the external URL
            // (null = Iroh-only/stub mode).
            val (controller, nativeAdminClient) = createController(effectiveAppServerUrl, requestTimeout.toLong(), scope)

            // Register admin_rpc handlers so clients on an iroh:// backend can
            // read conversations/messages/agents WITHOUT any direct HTTP route
            // to this host (Iroh purity: letta-mobile-qfa81). Phase 4: no
            // LettaShim admin base / HTTP subagent discovery.
            val adminRpcRouter = buildProductionAdminRouter(
                controller = controller,
                pairingService = pairingService,
                nativeClient = nativeAdminClient,
                vibesyncBaseUrl = vibesyncBaseUrl,
                subagentRegistryFile = subagentRegistryFile,
                skillsDir = skillsDir,
                localBackendDir = localBackendDir ?: System.getenv("LETTA_LOCAL_BACKEND_DIR"),
                eventScope = scope,
            )
            irohEndpoint.adminRpcRouter.copyHandlersFrom(adminRpcRouter)
            println(
                "[iroh-app-server] admin_rpc handlers registered " +
                    "(methods: ${adminRpcRouter.methodCount}, " +
                    "subagent_registry_v1: ${AdminRpcRegistry.subagentMethods.all { it in adminRpcRouter.registeredMethods }})",
            )
            // The two wirings whose ABSENCE silently blanked the memory and skills
            // surfaces after the 2026-08-01 cutover. Print them so a deploy can be
            // verified from the log instead of from a client round trip.
            println(
                "[iroh-app-server] local_backend_store: " +
                    (localBackendDir ?: System.getenv("LETTA_LOCAL_BACKEND_DIR") ?: "UNSET (block.list/agent.context fail closed)"),
            )

            printChannelsHostBanner()

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

    /**
     * d6e8g.2: fail closed — refuse anonymous startup unless explicitly opted into
     * via the loudly named test/dev-only flag. Extracted from [run] to keep it
     * within complexity bounds.
     */
    private fun resolveAuthPolicy() = when (
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

    /**
     * lgns8.18 (Path A): with --own-app-server, spawn `letta app-server` as an owned
     * child on an ephemeral loopback port and return it; else null. Registers a
     * shutdown hook and takes the wrapper down if the child dies (no orphaned
     * half-stack). Kept out of [run] so it stays within complexity bounds.
     */
    private fun maybeSpawnOwnedAppServer(scope: CoroutineScope): com.letta.mobile.cli.appserver.OwnedAppServerProcess? {
        if (!ownAppServer) return null
        println("[iroh-app-server] Spawning owned App Server child ($lettaCommand app-server)...")
        val owned = com.letta.mobile.cli.appserver.OwnedAppServerProcess.spawn(
            command = com.letta.mobile.cli.appserver.OwnedAppServerProcess.buildCommand(lettaCommand),
            log = { System.err.println(it) },
        )
        Runtime.getRuntime().addShutdownHook(Thread { owned.close() })
        println("[iroh-app-server] Owned App Server ready at ${owned.wsBaseUrl}")
        // Deterministic lifecycle: if the owned child dies, take the wrapper down
        // with it so the pair restarts together — no orphaned half-stack.
        scope.launch {
            val code = withContext(Dispatchers.IO) { owned.process.waitFor() }
            System.err.println("[iroh-app-server] Owned App Server child exited (code $code); shutting down wrapper.")
            exitProcess(if (code == 0) 0 else 70)
        }
        return owned
    }

    private fun createController(
        appServerUrl: String?,
        requestTimeoutMs: Long,
        scope: CoroutineScope,
    ): Pair<DefaultAppServerController, com.letta.mobile.data.transport.appserver.AppServerClient?> {
        return if (appServerUrl != null) {
            createLiveController(appServerUrl, requestTimeoutMs, scope)
        } else {
            createStubController(requestTimeoutMs, scope)
        }
    }

    private fun createLiveController(
        appServerUrl: String,
        requestTimeoutMs: Long,
        scope: CoroutineScope,
    ): Pair<DefaultAppServerController, com.letta.mobile.data.transport.appserver.AppServerClient> {
        val httpClient = HttpClient(CIO) {
            install(WebSockets) { applyAppServerFrameLimits() }
            install(HttpTimeout) {
                this.requestTimeoutMillis = requestTimeoutMs
                this.connectTimeoutMillis = 30_000
                this.socketTimeoutMillis = requestTimeoutMs
            }
        }
        val runtimeRegistry = InMemoryRuntimeRegistry()
        var controllerRef: DefaultAppServerController? = null
        var coordinatorRef: ReconnectCoordinator? = null
        val reconnectingClient = ReconnectingAppServerClient(
            connect = { mintGeneration(httpClient, appServerUrl, requestTimeoutMs, scope) },
            listener = recoveryListener(
                controller = { controllerRef },
                coordinator = { coordinatorRef },
            ),
        )
        val controller = DefaultAppServerController(
            client = reconnectingClient,
            runtimeRegistry = runtimeRegistry,
            turnContextPreflight = AppServerContextWindowPreflight(reconnectingClient),
            externalToolRegistry = ExternalToolRegistry.factoryDefault(),
        )
        controllerRef = controller
        coordinatorRef = ReconnectCoordinator(controller, runtimeRegistry)
        reconnectingClient.start(scope)
        return controller to reconnectingClient
    }

    /**
     * Mint one connection generation: a fresh WS transport + client on a job
     * child of [scope], closable independently of its successors.
     */
    private fun mintGeneration(
        httpClient: HttpClient,
        appServerUrl: String,
        requestTimeoutMs: Long,
        scope: CoroutineScope,
    ): AppServerClientGeneration {
        val generationJob = Job(scope.coroutineContext.job)
        val generationScope = CoroutineScope(scope.coroutineContext + generationJob)
        val transport = KtorAppServerWebSocketTransport(
            httpClient = httpClient,
            baseUrl = appServerUrl,
            scope = generationScope,
            bearerToken = null,
        )
        return AppServerClientGeneration(
            client = DefaultAppServerClient(
                transport,
                requestTimeoutMs = requestTimeoutMs,
                parentScope = generationScope,
            ),
            connectionState = transport.connectionState,
            close = { reason -> generationJob.cancel(kotlinx.coroutines.CancellationException(reason)) },
        )
    }

    /**
     * Post-connect recovery sequence, run on every generation-ready: reattach
     * runtimes, then restore channel accounts (lgns8.23), then mark connected.
     *
     * The controller and coordinator are read through suppliers because both are
     * constructed AFTER the reconnecting client they are wired into.
     */
    private fun recoveryListener(
        controller: () -> DefaultAppServerController?,
        coordinator: () -> ReconnectCoordinator?,
    ): ReconnectingClientListener = object : ReconnectingClientListener {
        override suspend fun onDisconnected(reason: String?) {
            println("[iroh-app-server] App Server connection lost: ${reason ?: "unknown"}")
            controller()?.onTransportDisconnected(reason)
        }

        override suspend fun onRecovered(client: com.letta.mobile.data.transport.appserver.AppServerClient) {
            val result = coordinator()?.reconnect()
            result?.errors?.forEach {
                System.err.println("[iroh-app-server] reattach failed: ${it.message}")
            }
            // lgns8.23: restore channel accounts on THIS generation's client.
            // Must run on every generation-ready (not just the first): a repeat
            // channel_start re-wires ingress to the live socket, which a reconnect
            // otherwise leaves pointed at the dead one.
            restoreChannels(client)
            controller()?.markConnected()
            println(
                "[iroh-app-server] App Server connection recovered " +
                    "(reattached runtimes: ${result?.reconnectedCount ?: 0})",
            )
        }

        override suspend fun onGaveUp(reason: String?) {
            System.err.println("[iroh-app-server] App Server reconnect gave up: ${reason ?: "unknown"}")
        }
    }

    /**
     * Print the dialable NodeID/ticket, plus the short `iroh://<node-id>@<host:port>`
     * form. The short form is only meaningful when the port is pinned; with a
     * random port it rotates like the ticket does.
     */
    private fun printDialInfo(irohEndpoint: IrohNodeEndpoint) {
        val nodeId = irohEndpoint.nodeIdHex()
        println("[iroh-app-server] Node ID: $nodeId")
        println("[iroh-app-server] Ticket: ${irohEndpoint.ticketString()}")
        val port = irohPort.toIntOrNull() ?: 0
        if (port <= 0) return
        val lanAddrs = lanAddresses(port)
        if (lanAddrs.isNotEmpty()) {
            println("[iroh-app-server] Short URL: iroh://$nodeId@${lanAddrs.joinToString(",")}")
        }
    }

    private fun lanAddresses(port: Int): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .map { "${it.hostAddress}:$port" }
            .toList()
    } catch (_: Exception) {
        emptyList()
    }

    /** lgns8.23: announce channels-host ownership so a double-host misconfig is visible at start. */
    private fun printChannelsHostBanner() {
        if (!channelsHost) return
        println(
            "[iroh-app-server] channels-host ENABLED (lgns8.23): enabled channel accounts are " +
                "restored on every App Server connect/reconnect. lettashim MUST run with " +
                "SHIM_CHANNELS_ENABLED=0 — two hosts double-start the same accounts.",
        )
    }

    /**
     * lgns8.23: flag-gated channels-host restore. No-op unless --channels-host is
     * set, so the default deployment keeps lettashim as the sole channels host.
     * Never fails recovery — a channel outage must not block runtime reattach.
     */
    private suspend fun restoreChannels(client: com.letta.mobile.data.transport.appserver.AppServerClient) {
        if (!channelsHost) return
        val result = com.letta.mobile.data.controller.channels.ChannelRestoreCoordinator(
            client = client,
            log = { System.err.println("[iroh-app-server] $it") },
            // o5bqk: ONE cache for the whole process. The coordinator is rebuilt
            // per generation (it binds to that generation's client), so this is
            // what lets a reconnect re-wire ingress in the first round trip
            // instead of after a full channels/accounts enumeration.
            accountCache = channelAccountCache,
        ).restore()
        // Identifiers + counts only: channel account config carries cleartext
        // plugin credentials and must never reach a log sink (lgns8.23 landmine 2).
        println(
            "[iroh-app-server] channels-host restore: channels=${result.channelIds.size} " +
                "started=${result.startedAccounts} failed=${result.failures.size}",
        )
        result.failures.forEach {
            System.err.println(
                "[iroh-app-server] channel restore failed: " +
                    "${it.channelId ?: "-"}/${it.accountId ?: "-"} phase=${it.phase} reason=${it.reason}",
            )
        }
    }

    private fun createStubController(
        requestTimeoutMs: Long,
        scope: CoroutineScope,
    ): Pair<DefaultAppServerController, com.letta.mobile.data.transport.appserver.AppServerClient?> {
        val httpClient = HttpClient(CIO) {
            install(WebSockets) { applyAppServerFrameLimits() }
        }
        val transport = KtorAppServerWebSocketTransport(
            httpClient = httpClient,
            baseUrl = "ws://127.0.0.1:0",
            scope = scope,
            bearerToken = null,
        )
        val client = DefaultAppServerClient(transport, requestTimeoutMs = requestTimeoutMs)
        return DefaultAppServerController(client) to null
    }
}
