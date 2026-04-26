package com.letta.mobile.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.letta.mobile.cli.commands.StreamCommand
import com.letta.mobile.cli.commands.WsStreamCommand

/**
 * letta-mobile-cli entrypoint.
 *
 * This binary exists to drive the same TimelineSyncLoop / SseParser /
 * LettaApiClient code paths the Android UI uses, but headlessly — so we
 * can reproduce, debug, and regression-test streaming bugs (like
 * letta-mobile-6p4o, the garbled SSE chunks) without putting eyes on a
 * device every time.
 */
class LettaMobileCli : CliktCommand(name = "letta-mobile-cli") {
    override fun run() = Unit
}

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            // When invoked from `:cli:run` with no args, print help and exit.
            // (Calling .main() with empty args would also print help, but
            // clikt would exit with code 1 and fail the JUnit test harness.)
            println(USAGE)
            return
        }
        LettaMobileCli()
            .subcommands(StreamCommand(), WsStreamCommand())
            .main(args)
    }

    private val USAGE = """
        letta-mobile-cli — drive Android-app streaming code paths headlessly.

        Usage:
          ./gradlew :cli:run -PcliArgs="<command> [options]"

        Commands:
          stream      Direct Letta path: POST /v1/conversations/{id}/messages,
                      print every SSE frame + merge state. Use to debug bugs
                      that happen against direct Letta (no lettabot in front).
          wsstream    Client-Mode path: open WebSocket to lettabot's
                      /api/v1/agent-gateway, print every WS chunk + merge
                      state. Use to debug Client-Mode bugs (letta-mobile-6p4o).

        Run with --help on any subcommand for details.
    """.trimIndent()
}
