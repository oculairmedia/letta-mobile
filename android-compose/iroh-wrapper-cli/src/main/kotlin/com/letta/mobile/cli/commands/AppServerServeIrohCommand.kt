package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.letta.mobile.data.controller.DefaultAppServerController
import com.letta.mobile.data.controller.capability.RemoteCapabilities
import com.letta.mobile.data.controller.extras.CustomIrohMessagingTool
import com.letta.mobile.data.controller.extras.AgentDiscoveryTool
import com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore
import com.letta.mobile.data.controller.node.iroh.LocalBackendAgentDiscoverySource
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
import com.letta.mobile.data.transport.appserver.applyAppServerDefaults
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
     * letta-mobile-bn008.6: a2a (direct agent-to-agent) receiver wiring.
     *
     * The wrapper binds a SECOND Iroh endpoint on this UDP port, exposed only
     * over the `/letta/a2a/0` ALPN. Inbound QUIC connections land through
     * [IrohAgentMessageReceiver], which routes by the target agent's most-recent
     * interactive conversation and lands the body as a user message on it via
     * the same App Server client the rest of the wrapper uses. The wrapper's
     * stable secret-key file is reused so the a2a node id matches the
     * app-server node id (demux is by `toAgentId`, not by node id).
     *
     * `--a2a-port 0` => OS-assigned random (testing only). Production should pin.
     */
    private val a2aPort by option(
        "--a2a-port",
        envvar = "LETTA_A2A_PORT",
        help = "letta-mobile-bn008.6: UDP port for the a2a receiver (0 = OS-assigned). " +
            "Set to -1 to disable the a2a receiver entirely (default: 0).",
    ).int().default(0)

    private val a2aAddressBook by option(
        "--a2a-address-book",
        envvar = "LETTA_A2A_ADDRESS_BOOK",
        help = "letta-mobile-bn008.6: kv file holding agentId->Iroh EndpointAddr mappings " +
            "(agent-addresses.kv format). Default: ~/.letta/iroh/agent-addresses.kv.",
    )

    private val a2aIdentityDir by option(
        "--a2a-identity-dir",
        envvar = "LETTA_A2A_IDENTITY_DIR",
        help = "letta-mobile-bn008.6: directory of per-agent Ed25519 identity files. " +
            "Default: ~/.letta/iroh/identities. Loaded lazily via IrohAgentIdentity.loadOrCreate.",
    )

    // letta-mobile-xmpqm: the previous `LETTA_A2A_PUBLISH_AGENTS` allowlist
    // (and its --a2a-publish-agents CLI option) is GONE. Reachability is now
    // gated by `LocalBackendAdminStore.agentExists` — the wrapper publishes
    // exactly ONE host record per bind, and any agent present in the local
    // backend dir is automatically addressable. Scaling the per-agent list
    // was the wrong answer; this seam is removed, not enumerated.

    /**
     * letta-mobile-bn008-phase2-custom-tool (1vuec): path to the `meridian`
     * binary whose `agent-message send` subcommand the wrapper invokes when an
     * agent calls the `agent_message_send` tool. The wrapper distribution
     * itself does not ship the `agent-message` subcommand (deliberately —
     * only `app-server-serve-iroh` is built into `meridian-iroh-wrapper`); the
     * operator deploys the developer `meridian` binary separately and points
     * this option at it. Empty string disables the tool entirely (the
     * registry drops `agent_message_send` and the agent sees no Iroh surface).
     */
    private val meridianBinary by option(
        "--meridian-binary",
        envvar = "LETTA_MERIDIAN_BINARY",
        help = "Path to the `meridian` CLI binary whose `agent-message send` subcommand " +
            "the wrapper invokes when an agent calls `agent_message_send`. Empty disables " +
            "the tool. Default: empty (no Iroh agent-to-agent tool advertised).",
    ).default("")

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

            // letta-mobile-bn008.6: bind the a2a (direct agent-to-agent) receiver.
            // The receiver's accept loop runs on the wrapper's main scope so the
            // shutdown hook below cancels it. Disable with --a2a-port=-1; the default
            // is to enable with an OS-assigned port (the address-book entry carries
            // the dialable addr, so pinning the port is not required for peers).
            //
            // N9 (PR #1125): `a2aPort` is now an Int (option declared via
            // `.int().default(0)`), so the old `a2aPort.toIntOrNull()`-and-fallback
            // dance is gone — the parser owns the type conversion.
            if (a2aPort < 0) {
                println("[iroh-app-server] a2a receiver: DISABLED (--a2a-port=$a2aPort)")
            } else {
                // N8 (PR #1125): hoist the LETTA_IROH_HOME fallback so the
                // address-book and identity-dir defaults can't drift apart
                // (the previous duplicated expression was a footgun the day a
                // future operator sets the envvar on only one path).
                val irohHome = java.io.File(
                    System.getenv("LETTA_IROH_HOME") ?: "${System.getProperty("user.home")}/.letta/iroh",
                )
                val effectiveAddressBook: java.io.File = a2aAddressBook
                    ?.let { java.io.File(it) }
                    ?: java.io.File(irohHome, "agent-addresses.kv")
                val effectiveIdentityDir: java.io.File = a2aIdentityDir
                    ?.let { java.io.File(it) }
                    ?: java.io.File(irohHome, "identities")
                val localBackendFile = (localBackendDir ?: System.getenv("LETTA_LOCAL_BACKEND_DIR"))
                    ?.takeIf { it.isNotBlank() }
                    ?.let { java.io.File(it) }
                val a2aCfg = A2aWiringConfig(
                    // N8 (PR #1125): the `else` branch above already guarantees
                    // `a2aPort >= 0`, so the previous `coerceAtLeast(0)` was
                    // dead code.
                    port = a2aPort,
                    secretKeyPath = irohSecretKeyPath,
                    identityDir = effectiveIdentityDir,
                    addressBook = effectiveAddressBook,
                )
                // B1 (PR #1125): degrade gracefully when the a2a receiver can't
                // be built. letta-mobile-xmpqm: the address-book seed is no
                // longer required for the receiver to come up — the host record
                // is written by [publishHost] during the build itself. The
                // fallback here still exists for any setup-time failure (e.g.
                // permission denied on the kv file, or a host record that can't
                // be written for some other reason).
                val wiring = runCatching {
                    buildA2aWiring(
                        config = a2aCfg,
                        client = nativeAdminClient,
                        localBackendDir = localBackendFile,
                    )
                }.getOrElse { t ->
                    println(
                        "[iroh-app-server] a2a receiver: DISABLED (${t.message}). " +
                            "Check $effectiveAddressBook is writable.",
                    )
                    null
                }
                if (wiring != null) {
                    val acceptJob = wiring.start(scope)
                    scope.coroutineContext[Job]?.invokeOnCompletion { _ ->
                        runCatching { acceptJob.cancel() }
                        runCatching { wiring.close() }
                    }
                    println(
                        "[iroh-app-server] a2a receiver: BOUND " +
                            "(node=${wiring.nodeIdHex}, port=$a2aPort, " +
                            "address_book=${effectiveAddressBook.absolutePath}, " +
                            "local_backend=${localBackendFile?.absolutePath ?: "UNSET (membership gate disabled)"})",
                    )
                }
            }
            
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
            install(WebSockets) { applyAppServerDefaults() }
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
            // letta-mobile-bn008-phase2-custom-tool (1vuec): wire the Iroh
            // agent-message CLI as an external tool, gated by --meridian-binary.
            // When unset the registry advertises no extras, preserving the
            // previous (factoryDefault) behavior. When set, every agent's
            // runtime_start includes the `agent_message_send` tool in
            // external_tools so the model sees it without operator
            // intervention. The CLI binary is the same `meridian agent-message
            // send` the operator flow already uses; this is purely additive.
            externalToolRegistry = buildProductionExternalToolRegistry(),
        )
        controllerRef = controller
        coordinatorRef = ReconnectCoordinator(controller, runtimeRegistry)
        reconnectingClient.start(scope)
        return controller to reconnectingClient
    }

    /**
     * letta-mobile-bn008-phase2-custom-tool (1vuec): build the production
     * [ExternalToolRegistry] for the wrapper.
     *
     * Returns [ExternalToolRegistry.factoryDefault] (advertises nothing) when
     * `--meridian-binary` is empty, so an unconfigured wrapper has the same
     * observable behavior as before this bead.
     *
     * Returns a registry with the Iroh agent-message tool wired in (and
     * capability [com.letta.mobile.data.controller.capability.Capability.AgentMessaging]
     * enabled) when `--meridian-binary` points at a real binary. The tool is
     * constructed with the binary path and the wrapper's a2a identity /
     * address-book directories so the subprocess invocation reads from the
     * same kv files the operator flow already populated.
     *
     * Test seam: `buildProductionExternalToolRegistryForTesting` exposes the
     * same logic without the class-private option fields.
     */
    private fun buildProductionExternalToolRegistry(): ExternalToolRegistry =
        buildProductionExternalToolRegistryForTesting(
            binary = meridianBinary,
            identityDir = a2aIdentityDir,
            addressStore = a2aAddressBook,
            localBackendDir = localBackendDir,
        )

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
        val addrs = java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { isRealNetworkInterface(it) }
            .flatMap { iface ->
                iface.inetAddresses.asSequence()
                    .filterIsInstance<java.net.Inet4Address>()
                    .map { "${it.hostAddress}:$port" }
            }
            .toList()
        sortDirectAddresses(addrs)
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
            install(WebSockets) { applyAppServerDefaults() }
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

/**
 * Helper to determine if a network interface is a real physical/routable interface
 * (excludes loopback, down, point-to-point, and virtual Docker/bridge interfaces).
 */
internal fun isRealNetworkInterfaceName(
    name: String,
    displayName: String? = null,
    isUp: Boolean = true,
    isLoopback: Boolean = false,
    isPointToPoint: Boolean = false,
): Boolean {
    if (!isUp || isLoopback || isPointToPoint) return false
    val nameLower = name.lowercase()
    val displayLower = (displayName ?: "").lowercase()
    val virtualKeywords = listOf(
        "docker", "br-", "veth", "virbr", "cni", "flannel", "tun", "tap", "dummy"
    )
    val isVirtual = virtualKeywords.any { keyword ->
        nameLower.contains(keyword) || displayLower.contains(keyword)
    } || nameLower.matches(Regex("^br[-_\\d].*"))
    return !isVirtual
}

internal fun isRealNetworkInterface(iface: java.net.NetworkInterface): Boolean {
    return try {
        isRealNetworkInterfaceName(
            name = iface.name,
            displayName = iface.displayName,
            isUp = iface.isUp,
            isLoopback = iface.isLoopback,
            isPointToPoint = iface.isPointToPoint,
        )
    } catch (_: Exception) {
        false
    }
}

/**
 * letta-mobile-bn008-phase2-custom-tool (1vuec): build the production
 * [ExternalToolRegistry] for the wrapper, given the resolved CLI binary path
 * and a2a directory overrides. Exposed at top level (instead of as a private
 * method on the CliktCommand) so unit tests in `:iroh-wrapper-cli` can drive
 * the same logic with throwaway binary paths without spinning up a clikt
 * invocation.
 *
 * Behavior:
 *  - `binary` blank OR equals a sentinel => [ExternalToolRegistry.factoryDefault]
 *    (advertises nothing; matches the pre-1vuec behavior).
 *  - `binary` non-blank => registry advertises the Iroh agent-message tool
 *    with `agentMessaging` capability enabled.
 *
 * The agent-message tool uses `identityDir` and `addressStore` only when
 * non-null — the underlying CLI falls back to its own defaults
 * (`~/.letta/iroh/identities`, `~/.letta/iroh/agent-addresses.kv`).
 */
internal fun buildProductionExternalToolRegistryForTesting(
    binary: String,
    identityDir: String?,
    addressStore: String?,
    localBackendDir: String? = null,
): ExternalToolRegistry {
    if (binary.isBlank()) {
        return ExternalToolRegistry.factoryDefault()
    }
    val capabilities = RemoteCapabilities(agentMessaging = true)
    val tool = CustomIrohMessagingTool(
        binary = binary,
        identityDir = identityDir,
        addressStore = addressStore,
    )
    val discovery = localBackendDir?.takeIf { it.isNotBlank() }?.let {
        AgentDiscoveryTool(LocalBackendAgentDiscoverySource(LocalBackendAdminStore(java.io.File(it))))
    }
    return ExternalToolRegistry.standard(
        capabilities = capabilities,
        customIrohMessagingTool = tool,
        agentDiscoveryTool = discovery,
    )
}

/**
 * Priority ranking for IP address strings. Lower number = higher priority.
 * Priority 0/1: Physical LAN IPs (eth*, wlan*, en*, wl* or 192.168.*, 10.*).
 * Priority 2: Other IPs.
 * Priority 3: Virtual Docker bridge IPs (e.g. 172.17.x.x - 172.31.x.x or virtual interfaces).
 */
internal fun directAddressPriority(address: String): Int {
    val ipStr = address.substringBefore(':').trim()
    val iface = try {
        java.net.InetAddress.getByName(ipStr)?.let { java.net.NetworkInterface.getByInetAddress(it) }
    } catch (_: Exception) {
        null
    }

    if (iface != null) {
        if (!isRealNetworkInterface(iface)) {
            return 3
        }
        val name = iface.name.lowercase()
        if (name.startsWith("eth") || name.startsWith("wlan") || name.startsWith("en") || name.startsWith("wl")) {
            return 0
        }
    }

    if (ipStr.startsWith("192.168.") || ipStr.startsWith("10.")) {
        return 1
    }

    if (isDockerBridgeIp(ipStr)) {
        return 3
    }

    return 2
}

internal fun isDockerBridgeIp(ip: String): Boolean {
    if (ip.startsWith("172.")) {
        val secondOctet = ip.split('.').getOrNull(1)?.toIntOrNull()
        if (secondOctet != null && secondOctet in 16..31) {
            return true
        }
    }
    return false
}

/**
 * Sorts direct address strings ("IP:port") so physical LAN IP addresses appear before Docker bridge IPs.
 */
internal fun sortDirectAddresses(directAddresses: List<String>): List<String> {
    return directAddresses.sortedWith(Comparator { addr1, addr2 ->
        val p1 = directAddressPriority(addr1)
        val p2 = directAddressPriority(addr2)
        if (p1 != p2) p1.compareTo(p2) else addr1.compareTo(addr2)
    })
}
