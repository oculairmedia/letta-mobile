package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.letta.mobile.cli.probe.ProbeStubAdminServer
import com.letta.mobile.cli.probe.ProbeStubBehavior
import com.letta.mobile.cli.probe.ProbeStubController
import com.letta.mobile.cli.probe.ProbeStubStore
import com.letta.mobile.data.controller.node.iroh.AdminRpcRegistry
import com.letta.mobile.data.controller.node.iroh.IrohAuthPolicy
import com.letta.mobile.data.controller.node.iroh.IrohAuthPolicyResolution
import com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

import kotlin.time.Duration.Companion.seconds
/**
 * Hermetic stub app-server over Iroh for the probe CI gate (letta-mobile-q5iiv).
 *
 * Boots a deterministic in-process app-server (no letta backend, no admin-shim)
 * plus a local HTTP admin API, and serves both over a real Iroh endpoint using
 * the SAME server bridge the production wrapper uses ([IrohNodeEndpoint] /
 * `IrohNodeConnection` / [AdminRpcRegistry]). `scripts/iroh_probe_hermetic.sh`
 * starts this command with a throwaway key on free ports and runs
 * `app-server-iroh-probe` against the printed ticket.
 *
 * Red-path regression injection (acceptance self-check) is controlled via env:
 *   LETTA_PROBE_STUB_SUPPRESS_TERMINAL=1  — drop terminal frames
 *   LETTA_PROBE_STUB_UNTYPED_FRAMES=1     — strip message_type from assistant deltas
 */
internal class AppServerServeIrohStubCommand : CliktCommand(
    name = "app-server-serve-iroh-stub",
) {
    private val irohPort by option(
        "--iroh-port",
        envvar = "LETTA_IROH_PORT",
        help = "UDP port for the Iroh endpoint. 0 = random.",
    ).default("0")

    private val irohSecretKeyPath by option(
        "--iroh-secret-key-file",
        envvar = "LETTA_IROH_SECRET_KEY_FILE",
        help = "Path to a 32-byte secret key file (throwaway for hermetic runs).",
    )

    private val authToken by option(
        "--auth-token",
        envvar = "LETTA_IROH_AUTH_TOKEN",
        help = "Bearer token clients must present.",
    )

    private val allowInsecureAnonymousIroh by option(
        "--allow-insecure-anonymous-iroh",
        help = "TEST/DEV ONLY: run the stub with NO authentication (see app-server-serve-iroh).",
    ).flag(default = false)

    private val adminPort by option(
        "--admin-port",
        help = "TCP port for the local stub HTTP admin API. 0 = random free port.",
    ).int().default(0)

    private val suppressTerminal by option(
        "--suppress-terminal",
        help = "Drop terminal frames so the turn engine stays busy (for kyqdt-busy gate reproduction).",
    ).flag(default = false)

    private val assistantDeltas by option(
        "--assistant-deltas",
        help = "Assistant deltas emitted per turn (spaced so cancel can land midstream).",
    ).int().default(3)

    private val deltaDelayMs by option(
        "--delta-delay-ms",
        help = "Delay between stream deltas in milliseconds.",
    ).int().default(200)

    private val maxLifetimeMs by option(
        "--max-lifetime-ms",
        envvar = "LETTA_PROBE_STUB_MAX_LIFETIME_MS",
        help = "Self-terminate after this long, so a stub leaked by a hard-killed " +
            "wrapper (SIGKILL/OOM: gradle's JavaExec child survives the client " +
            "process tree) cannot hold the UDP port forever. 0 = run until killed.",
    ).long().default(15 * 60 * 1000L)

    override fun run() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            val store = ProbeStubStore()
            val behavior = ProbeStubBehavior.fromEnv().copy(suppressTerminal = suppressTerminal || System.getProperty("letta.probe.stub.suppressTerminal") == "true",
                assistantDeltas = assistantDeltas,
                deltaDelayMs = deltaDelayMs.toLong(),
            )
            if (behavior.suppressTerminal) {
                println("[iroh-stub-server] REGRESSION INJECTED: suppress-terminal")
            }
            if (behavior.untypedFrames) {
                println("[iroh-stub-server] REGRESSION INJECTED: untyped-frames")
            }

            // The wrapper script kills this JVM directly by PID: with the Gradle
            // daemon in play, this process is a child of the daemon — NOT of the
            // backgrounded `./gradlew` client whose process tree the script walks.
            println("[iroh-stub-server] PID: ${ProcessHandle.current().pid()}")

            val adminServer = ProbeStubAdminServer(store, adminPort)
            println("[iroh-stub-server] Admin base: ${adminServer.baseUrl}")

            println("[iroh-stub-server] Starting Iroh endpoint...")
            // d6e8g.2: same fail-closed resolution as the production wrapper —
            // hermetic scripts always pass a token; anonymous stub runs need
            // the explicit test/dev flag.
            val authPolicy = when (
                val resolution = IrohAuthPolicy.resolve(
                    authToken = authToken,
                    allowedPeerIds = emptySet(),
                    allowInsecureAnonymous = allowInsecureAnonymousIroh,
                )
            ) {
                is IrohAuthPolicyResolution.Secure -> resolution.policy
                is IrohAuthPolicyResolution.InsecureAccepted -> {
                    System.err.println("[iroh-stub-server] ${resolution.warning}")
                    resolution.policy
                }
                is IrohAuthPolicyResolution.Refused -> {
                    System.err.println("[iroh-stub-server] ${resolution.error}")
                    exitProcess(78)
                }
            }
            val irohEndpoint = IrohNodeEndpoint(
                scope = scope,
                bindAddr = "0.0.0.0:$irohPort",
                secretKeyPath = irohSecretKeyPath,
                authPolicy = authPolicy,
            )
            irohEndpoint.create()

            println("[iroh-stub-server] Node ID: ${irohEndpoint.nodeIdHex()}")
            println("[iroh-stub-server] Ticket: ${irohEndpoint.ticketString()}")

            val adminRpcRouter = AdminRpcRegistry.buildRouter(adminServer.baseUrl)
            irohEndpoint.adminRpcRouter.copyHandlersFrom(adminRpcRouter)
            println(
                "[iroh-stub-server] admin_rpc handlers registered " +
                    "(proxy base: ${adminServer.baseUrl}, methods: ${adminRpcRouter.methodCount})",
            )

            irohEndpoint.start(ProbeStubController(store, behavior))
            println("[iroh-stub-server] Listening on Iroh... (Ctrl+C to stop)")

            Runtime.getRuntime().addShutdownHook(
                Thread {
                    runBlocking {
                        println("\n[iroh-stub-server] Shutting down...")
                        runCatching { irohEndpoint.shutdown() }
                        runCatching { adminServer.close() }
                        scope.cancel()
                    }
                },
            )

            val deadline = if (maxLifetimeMs > 0) System.currentTimeMillis() + maxLifetimeMs else Long.MAX_VALUE
            while (System.currentTimeMillis() < deadline) {
                delay(1.seconds)
            }
            println("[iroh-stub-server] max lifetime ${maxLifetimeMs}ms reached; shutting down")
            exitProcess(0)
        } catch (e: Exception) {
            System.err.println("[iroh-stub-server] Error: ${e.message}")
            e.printStackTrace()
            scope.cancel()
            exitProcess(1)
        }
    }
}
