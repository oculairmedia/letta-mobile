package com.letta.mobile.appservercli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.letta.mobile.data.runtime.host.LocalAppServerHost
import com.letta.mobile.data.runtime.host.LocalAppServerStartException
import com.letta.mobile.data.runtime.host.ownedLocalAppServerHost
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Runs one loopback App Server through the shared [LocalAppServerHost] (letta-mobile-by4vm.110.4):
 * `letta server --backend local` on an ephemeral 127.0.0.1 port with a fresh capability token in
 * `<backend-dir>/ws-token`. Prints the URL once `/readyz` answers, then blocks until the server
 * exits and exits with its code. It never restarts the server: systemd (or the caller) owns that.
 */
internal class AppServerServeCommand : CliktCommand(
    name = "app-server-serve",
) {
    private val lettaCommand by option(
        "--letta-command",
        envvar = "LETTA_APP_SERVER_COMMAND",
        help = "Letta Code executable to launch.",
    ).default("letta")

    private val lettaArguments by option(
        "--letta-arg",
        help = "Extra argument passed to the Letta Code executable before `server`. Repeatable.",
    ).multiple()

    private val backendDir by option(
        "--backend-dir",
        envvar = "LETTA_LOCAL_BACKEND_DIR",
        help = "Local backend root the server owns. Fenced for exclusive ownership: a second " +
            "invocation (or a crash-orphaned prior owner) is refused before the server starts.",
    ).required()

    private val unit by option(
        "--unit",
        envvar = "LETTA_APP_SERVER_UNIT",
        help = "Restart-authority label (e.g. systemd unit) recorded in the ownership sidecar.",
    )

    @Suppress("NoDetachedCoroutineLifecycle") // The command's own lifetime; cancelled below.
    override fun run() = runBlocking {
        val fence = acquireBackendFence(backendDir, unit, BackendOwnershipPreflight())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = File(backendDir).absolutePath
        val host = ownedLocalAppServerHost(listOf(lettaCommand) + lettaArguments, root, scope) { System.err.println("[app-server] $it") }
        // A JVM shutdown (SIGTERM, Ctrl-C) stops the server tree and releases the fence; SIGKILL cannot.
        val shutdownHook = Thread({
            host.close()
            fence.close()
        }, "app-server-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        try {
            val lease = try {
                host.acquire()
            } catch (e: LocalAppServerStartException) {
                System.err.println("[app-server] ${e.message}")
                throw ProgramResult(e.exitCode?.takeIf { it != 0 } ?: 1)
            }
            println("[app-server] ready at ${lease.serverUrl} (bearer token in $root/${LocalAppServerHost.TOKEN_FILE_NAME})")
            val exitCode = host.health.first { it.needsForceRestart }.lastExitCode
            if (exitCode != 0) throw ProgramResult(exitCode ?: 1)
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            host.close()
            fence.close()
            scope.cancel()
        }
    }
}

/**
 * Acquire the [BackendOwnershipPreflight] fence for [backendDir] (P0.1). Translates a live
 * competing writer / already-owned root into a non-zero [ProgramResult] so the serve command
 * fails closed instead of racing a second writer.
 */
internal fun acquireBackendFence(
    backendDir: String,
    unit: String?,
    preflight: BackendOwnershipPreflight,
): FencedOwnership =
    try {
        preflight.acquire(backendDir.trim(), unit = unit?.trim()?.takeIf { it.isNotEmpty() })
    } catch (e: BackendCompetingWriterException) {
        System.err.println("[app-server] ${e.message}")
        throw ProgramResult(1)
    } catch (e: BackendAlreadyOwnedException) {
        System.err.println("[app-server] ${e.message}")
        throw ProgramResult(1)
    }
