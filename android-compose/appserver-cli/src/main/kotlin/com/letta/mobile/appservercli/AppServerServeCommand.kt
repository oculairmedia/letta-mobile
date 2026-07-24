package com.letta.mobile.appservercli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import java.net.URI

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
        val command = buildAppServerServeCommand(
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

        val rendered = formatProcessCommand(command)
        if (dryRun) {
            println(rendered)
            return
        }

        println("[app-server] $rendered")

        // P0.1 (letta-mobile-gn7kr.1): fence backend ownership BEFORE spawning so a
        // second invocation — or a crash-orphaned prior owner — cannot race the
        // on-disk backend, and run the child through the crash-recovery supervisor
        // (readiness-gated startup, bounded graceful shutdown, whole-tree kill).
        val fence = acquireBackendFence(backendDir, unit, BackendOwnershipPreflight())
        val controller = ProcessHandleController(command)
        val supervisor = AppServerSupervisor(
            process = controller,
            readiness = HttpReadinessProbe(listen),
            onEvent = ::logSupervisorEvent,
        )

        // On any JVM shutdown (SIGTERM, Ctrl-C) stop the child tree and release the
        // fence so the next start sees a clean root. A hard SIGKILL cannot run this.
        val shutdownHook = Thread({
            supervisor.stop()
            fence?.close()
        }, "app-server-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        try {
            when (val outcome = supervisor.start()) {
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
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            supervisor.stop()
            fence?.close()
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

internal data class AppServerServeSpec(
    val listen: String = DEFAULT_APP_SERVER_LISTEN,
    val lettaCommand: String = DEFAULT_LETTA_COMMAND,
    val lettaArguments: List<String> = emptyList(),
    val wsAuth: String? = null,
    val wsTokenFile: String? = null,
    val wsTokenSha256: String? = null,
    val wsSharedSecretFile: String? = null,
    val wsIssuer: String? = null,
    val wsAudience: String? = null,
    val wsMaxClockSkewSeconds: Long? = null,
)

internal fun buildAppServerServeCommand(spec: AppServerServeSpec): List<String> {
    val command = mutableListOf<String>()

    command += requireNonBlank(spec.lettaCommand, "--letta-command")
    spec.lettaArguments.forEachIndexed { index, argument ->
        command += requireNonBlank(argument, "--letta-arg #${index + 1}")
    }
    command += "app-server"
    command += "--listen"
    val listen = requireNonBlank(spec.listen, "--listen")
    requireRemoteAuthForNonLoopback(listen, spec.wsAuth)
    command += listen

    spec.wsAuth?.let {
        val authMode = requireNonBlank(it, "--ws-auth")
        if (authMode != APP_SERVER_AUTH_CAPABILITY_TOKEN && authMode != APP_SERVER_AUTH_SIGNED_BEARER_TOKEN) {
            throw UsageError("--ws-auth must be $APP_SERVER_AUTH_CAPABILITY_TOKEN or $APP_SERVER_AUTH_SIGNED_BEARER_TOKEN")
        }
        command += "--ws-auth"
        command += authMode
    }
    appendOption(command, "--ws-token-file", spec.wsTokenFile)
    appendOption(command, "--ws-token-sha256", spec.wsTokenSha256)
    appendOption(command, "--ws-shared-secret-file", spec.wsSharedSecretFile)
    appendOption(command, "--ws-issuer", spec.wsIssuer)
    appendOption(command, "--ws-audience", spec.wsAudience)
    spec.wsMaxClockSkewSeconds?.let {
        if (it <= 0) throw UsageError("--ws-max-clock-skew-seconds must be > 0")
        command += "--ws-max-clock-skew-seconds"
        command += it.toString()
    }

    return command
}

private fun requireRemoteAuthForNonLoopback(listen: String, wsAuth: String?) {
    val uri = runCatching { URI(listen) }.getOrElse {
        throw UsageError("--listen must be a valid ws:// URL")
    }
    if (uri.scheme != "ws" || uri.host.isNullOrBlank()) {
        throw UsageError("--listen must be a valid ws:// URL")
    }
    if (!uri.host.isLoopbackHost() && wsAuth.isNullOrBlank()) {
        throw UsageError("--ws-auth is required when --listen is not a loopback host")
    }
}

private fun String.isLoopbackHost(): Boolean {
    val normalized = trim().removePrefix("[").removeSuffix("]").lowercase()
    return normalized == "localhost" ||
        normalized == "127.0.0.1" ||
        normalized == "::1" ||
        normalized.startsWith("127.")
}

internal fun formatProcessCommand(command: List<String>): String =
    command.joinToString(" ") { argument ->
        if (argument.isEmpty()) {
            "\"\""
        } else if (argument.any { it.isWhitespace() || it == '"' }) {
            "\"${argument.replace("\"", "\\\"")}\""
        } else {
            argument
        }
    }

private fun appendOption(command: MutableList<String>, name: String, value: String?) {
    value?.let {
        command += name
        command += requireNonBlank(it, name)
    }
}

private fun requireNonBlank(value: String, optionName: String): String {
    if (value.isBlank()) throw UsageError("$optionName must not be blank")
    return value
}

private const val DEFAULT_APP_SERVER_LISTEN = "ws://127.0.0.1:4500"
private const val DEFAULT_LETTA_COMMAND = "letta"
private const val APP_SERVER_AUTH_CAPABILITY_TOKEN = "capability-token"
private const val APP_SERVER_AUTH_SIGNED_BEARER_TOKEN = "signed-bearer-token"
