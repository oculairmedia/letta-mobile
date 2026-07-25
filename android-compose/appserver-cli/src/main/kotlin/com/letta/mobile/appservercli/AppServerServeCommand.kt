package com.letta.mobile.appservercli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import com.letta.mobile.appserver.AppServerServeSpec
import com.letta.mobile.appserver.AppServerServeSpecException
import com.letta.mobile.appserver.DEFAULT_APP_SERVER_LISTEN
import com.letta.mobile.appserver.DEFAULT_LETTA_COMMAND
import com.letta.mobile.appserver.buildAppServerServeCommand
import com.letta.mobile.appserver.formatProcessCommand

internal class AppServerServeCommand : CliktCommand(
    name = "app-server-serve",
) {
    private val listen by option(
        "--listen",
        envvar = "LETTA_APP_SERVER_LISTEN",
        help = "App Server listen URL.",
    ).default(DEFAULT_APP_SERVER_LISTEN)

    private val lettaCommand by option(
        "--letta-command",
        envvar = "LETTA_APP_SERVER_COMMAND",
        help = "Letta Code executable to launch.",
    ).default(DEFAULT_LETTA_COMMAND)

    private val lettaArguments by option(
        "--letta-arg",
        help = "Extra argument passed to the Letta Code executable before app-server. Repeatable.",
    ).multiple()

    private val wsAuth by option(
        "--ws-auth",
        help = "App Server auth mode: capability-token or signed-bearer-token.",
    )

    private val wsTokenFile by option("--ws-token-file")
    private val wsTokenSha256 by option("--ws-token-sha256")
    private val wsSharedSecretFile by option("--ws-shared-secret-file")
    private val wsIssuer by option("--ws-issuer")
    private val wsAudience by option("--ws-audience")
    private val wsMaxClockSkewSeconds by option("--ws-max-clock-skew-seconds").long()

    private val backendDir by option(
        "--backend-dir",
        envvar = "LETTA_LOCAL_BACKEND_DIR",
        help = "Local backend root to fence for exclusive ownership. When set, a second " +
            "invocation (or a crash-orphaned prior owner) is refused before the child spawns.",
    )

    private val unit by option(
        "--unit",
        envvar = "LETTA_APP_SERVER_UNIT",
        help = "Restart-authority label (e.g. systemd unit) recorded in the ownership sidecar.",
    )

    private val dryRun by option(
        "--dry-run",
        help = "Print the generated host command without starting a process.",
    ).flag(default = false)

    override fun run() {
        val command = try {
            buildAppServerServeCommand(
                AppServerServeSpec(
                    listen = listen,
                    lettaCommand = lettaCommand,
                    lettaArguments = lettaArguments,
                    wsAuth = wsAuth,
                    wsTokenFile = wsTokenFile,
                    wsTokenSha256 = wsTokenSha256,
                    wsSharedSecretFile = wsSharedSecretFile,
                    wsIssuer = wsIssuer,
                    wsAudience = wsAudience,
                    wsMaxClockSkewSeconds = wsMaxClockSkewSeconds,
                ),
            )
        } catch (error: AppServerServeSpecException) {
            throw UsageError(error.message ?: "invalid app-server-serve arguments")
        }

        val rendered = formatProcessCommand(command)
        if (dryRun) {
            println(rendered)
            return
        }

        println("[app-server] $rendered")
        runSupervised(command)
    }

    /**
     * Fence backend ownership (P0.1) then run [command] through the crash-recovery
     * supervisor: readiness-gated startup, bounded graceful shutdown, whole-tree kill.
     * A JVM shutdown (SIGTERM, Ctrl-C) stops the child tree and releases the fence so
     * the next start sees a clean root; a hard SIGKILL cannot run that hook.
     */
    private fun runSupervised(command: List<String>) {
        val fence = acquireBackendFence(backendDir, unit, BackendOwnershipPreflight())
        val controller = ProcessHandleController(command)
        val supervisor = AppServerSupervisor(
            process = controller,
            readiness = HttpReadinessProbe(listen),
            onEvent = ::logSupervisorEvent,
        )

        val shutdownHook = Thread({
            supervisor.stop()
            fence?.close()
        }, "app-server-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        try {
            handleStartupOutcome(supervisor.start(), controller)
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            supervisor.stop()
            fence?.close()
        }
    }

    /**
     * Map the supervisor [outcome] to the serve command's exit semantics: block on a
     * ready child and propagate its exit code; surface a non-zero [ProgramResult] when
     * the child exits before ready or the startup times out.
     */
    private fun handleStartupOutcome(
        outcome: AppServerSupervisor.StartupOutcome,
        controller: ProcessHandleController,
    ) {
        when (outcome) {
            is AppServerSupervisor.StartupOutcome.Ready -> {
                // Child is up and healthy; block on it and propagate its exit code.
                val exitCode = controller.awaitExit()
                if (exitCode != 0) throw ProgramResult(exitCode)
            }
            is AppServerSupervisor.StartupOutcome.ExitedBeforeReady -> {
                System.err.println(
                    "[app-server] child exited before becoming ready (code ${outcome.exitCode})",
                )
                throw ProgramResult(if (outcome.exitCode != 0) outcome.exitCode else 1)
            }
            is AppServerSupervisor.StartupOutcome.StartupTimedOut -> {
                System.err.println("[app-server] child did not become ready before the startup timeout")
                throw ProgramResult(1)
            }
        }
    }

    private fun logSupervisorEvent(event: AppServerSupervisor.SupervisorLifecycleEvent) {
        when (event) {
            AppServerSupervisor.SupervisorLifecycleEvent.Starting -> println("[app-server] starting child")
            AppServerSupervisor.SupervisorLifecycleEvent.Ready -> println("[app-server] child is ready")
            is AppServerSupervisor.SupervisorLifecycleEvent.ExitedBeforeReady ->
                System.err.println("[app-server] child exited before ready (code ${event.exitCode}): ${event.diagnostics}")
            is AppServerSupervisor.SupervisorLifecycleEvent.StartupTimedOut ->
                System.err.println("[app-server] startup timed out: ${event.diagnostics}")
            AppServerSupervisor.SupervisorLifecycleEvent.Stopping -> println("[app-server] stopping child")
            AppServerSupervisor.SupervisorLifecycleEvent.StoppedGracefully -> println("[app-server] child stopped gracefully")
            AppServerSupervisor.SupervisorLifecycleEvent.ForceKilled -> System.err.println("[app-server] child force-killed")
            AppServerSupervisor.SupervisorLifecycleEvent.AlreadyStopped -> Unit
        }
    }
}

/**
 * Acquire the [BackendOwnershipPreflight] fence for [backendDir] (P0.1). Returns
 * null when no backend dir is configured (fencing disabled — the child still runs,
 * but ownership cannot be enforced). Translates a live competing writer / already
 * -owned root into a non-zero [ProgramResult] so the serve command fails closed
 * instead of racing a second writer.
 *
 * Extracted from [AppServerServeCommand.run] so the fence policy is unit-testable
 * without spawning a real child process.
 */
internal fun acquireBackendFence(
    backendDir: String?,
    unit: String?,
    preflight: BackendOwnershipPreflight,
): FencedOwnership? {
    val dir = backendDir?.trim()?.takeIf { it.isNotEmpty() } ?: run {
        System.err.println(
            "[app-server] WARNING: ownership fencing disabled (no --backend-dir / LETTA_LOCAL_BACKEND_DIR)",
        )
        return null
    }
    return try {
        preflight.acquire(dir, unit = unit?.trim()?.takeIf { it.isNotEmpty() })
    } catch (e: BackendCompetingWriterException) {
        System.err.println("[app-server] ${e.message}")
        throw ProgramResult(1)
    } catch (e: BackendAlreadyOwnedException) {
        System.err.println("[app-server] ${e.message}")
        throw ProgramResult(1)
    }
}
