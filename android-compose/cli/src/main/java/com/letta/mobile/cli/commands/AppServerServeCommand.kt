package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long

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
        val process = ProcessBuilder(command)
            .inheritIO()
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) throw ProgramResult(exitCode)
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
    command += requireNonBlank(spec.listen, "--listen")

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
