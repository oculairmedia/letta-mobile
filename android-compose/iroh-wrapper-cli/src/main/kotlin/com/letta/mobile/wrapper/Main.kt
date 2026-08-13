package com.letta.mobile.wrapper

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.letta.mobile.cli.commands.AppServerServeIrohCommand
import com.letta.mobile.cli.commands.PairCommand

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
 *
 * letta-mobile-gw0h1 (sixv8.2): a second subcommand, `pair`, is registered so
 * operators can mint a QR-encoded invite (`letta-qr-v1.<base64url-json>` from
 * `reference/qr-pairing-protocol.md` §5.1 + §7.1) without bringing up the
 * full wrapper. The wire format is the protocol's contract; this command is
 * the renderer only.
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
        PairCommand(),
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
          meridian-iroh-wrapper pair [options]

        Commands:
          app-server-serve-iroh  Bridge Iroh QUIC <-> App Server WebSocket.
          pair                    Mint a QR-encoded pairing invite (letta-mobile-gw0h1).

        Run `meridian-iroh-wrapper <command> --help` for options.
    """.trimIndent()
}

