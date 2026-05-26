package com.letta.mobile.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.letta.mobile.cli.commands.ConnectCommand
import com.letta.mobile.cli.commands.DisconnectCommand
import com.letta.mobile.cli.commands.DumpTimelineCommand
import com.letta.mobile.cli.commands.RecordCommand
import com.letta.mobile.cli.commands.ReconnectCommand
import com.letta.mobile.cli.commands.ReplayCommand
import com.letta.mobile.cli.commands.SendCommand
import com.letta.mobile.cli.commands.StreamCommand

class LettaMobileCli : CliktCommand(name = "meridian") {
    override fun run() = Unit
}

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println(USAGE)
            return
        }
        LettaMobileCli()
            .subcommands(
                ConnectCommand(),
                SendCommand(),
                DumpTimelineCommand(),
                ReplayCommand(),
                RecordCommand(),
                DisconnectCommand(),
                ReconnectCommand(),
                StreamCommand(),
            )
            .main(args)
    }

    private val USAGE = """
        meridian - drive Letta Mobile transport and timeline code paths headlessly.

        Usage:
          ./gradlew :cli:run -PcliArgs="<command> [options]"

        Commands:
          connect        Open admin-shim mobile WS and print welcome/session state.
          send           Send through admin-shim WS and fold frames into a headless timeline.
          dump-timeline  Fetch conversation history and emit stable timeline JSON.
          replay         Replay a recorded WS JSONL fixture through the reducer.
          record         Capture admin-shim WS wire frames as replay-compatible JSONL.
          disconnect     Open WS and close it cleanly.
          reconnect      Exercise disconnect/reconnect and optional run resume.
          stream         Direct Letta REST/SSE path for low-level comparison.

        Run with --help on any subcommand for details.
    """.trimIndent()
}
