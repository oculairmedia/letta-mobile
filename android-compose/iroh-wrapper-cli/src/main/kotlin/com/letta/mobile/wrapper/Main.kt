package com.letta.mobile.wrapper

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.letta.mobile.cli.commands.AppServerServeIrohCommand

/**
 * Root command of the packaged Iroh wrapper distribution (letta-mobile-zsgad).
 *
 * Deliberately narrow: this binary is what `meridian-iroh-wrapper.service` runs
 * in production, so it carries ONLY the wrapper command and none of the probe /
 * REST / profile tooling that `:cli`'s `meridian` CLI exposes for development.
 * A smaller surface means a smaller `lib/` and no accidental dependency on the
 * Android-only halves of the developer CLI.
 *
 * The subcommand name is unchanged (`app-server-serve-iroh`) so the existing
 * systemd `ExecStart` argument vector migrates verbatim — only the executable in
 * front of it changes.
 */
class IrohWrapperCli : CliktCommand(name = "meridian-iroh-wrapper") {
    override fun run() = Unit
}

/**
 * Builds the wired root command. Exposed (rather than inlined into [Main.main])
 * so tests can assert the clikt subcommand registry without spawning a process.
 */
fun buildIrohWrapperCli(): CliktCommand =
    IrohWrapperCli().subcommands(
        AppServerServeIrohCommand(),
    )

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println(USAGE)
            return
        }
        buildIrohWrapperCli().main(args)
    }

    private val USAGE = """
        meridian-iroh-wrapper - serve the Letta App Server over the Iroh QUIC transport.

        Usage:
          meridian-iroh-wrapper app-server-serve-iroh [options]

        Commands:
          app-server-serve-iroh  Bridge Iroh QUIC <-> App Server WebSocket.

        Run `meridian-iroh-wrapper app-server-serve-iroh --help` for options.
    """.trimIndent()
}
