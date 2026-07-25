package com.letta.mobile.cli.commands

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
        val process = ProcessBuilder(command)
            .inheritIO()
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
