package com.letta.mobile.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.letta.mobile.cli.commands.ConnectCommand
import com.letta.mobile.cli.commands.CaptureCommand
import com.letta.mobile.cli.commands.DisconnectCommand
import com.letta.mobile.cli.commands.DumpTimelineCommand
import com.letta.mobile.cli.commands.ProfileCommand
import com.letta.mobile.cli.commands.ProfileDeleteCommand
import com.letta.mobile.cli.commands.ProfileExportCommand
import com.letta.mobile.cli.commands.ProfileImportCommand
import com.letta.mobile.cli.commands.ProfileListCommand
import com.letta.mobile.cli.commands.ProfileSetCommand
import com.letta.mobile.cli.commands.ProfileShowCommand
import com.letta.mobile.cli.commands.ProfileUseCommand
import com.letta.mobile.cli.commands.RecordCommand
import com.letta.mobile.cli.commands.RecordCursorStateCommand
import com.letta.mobile.cli.commands.ReconnectCommand
import com.letta.mobile.cli.commands.RestCommand
import com.letta.mobile.cli.commands.RestDeleteCommand
import com.letta.mobile.cli.commands.RestGetCommand
import com.letta.mobile.cli.commands.RestPatchCommand
import com.letta.mobile.cli.commands.RestPostCommand
import com.letta.mobile.cli.commands.RestPutCommand
import com.letta.mobile.cli.commands.ReplayCommand
import com.letta.mobile.cli.commands.SendCommand
import com.letta.mobile.cli.commands.SetupApplyCommand
import com.letta.mobile.cli.commands.SetupCommand
import com.letta.mobile.cli.commands.SetupExportCommand
import com.letta.mobile.cli.commands.StreamCommand
import com.letta.mobile.cli.commands.buildResourceCommands

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
                CaptureCommand(),
                SendCommand(),
                DumpTimelineCommand(),
                ReplayCommand(),
                RecordCommand(),
                RecordCursorStateCommand(),
                DisconnectCommand(),
                ReconnectCommand(),
                RestCommand().subcommands(
                    RestGetCommand(),
                    RestPostCommand(),
                    RestPutCommand(),
                    RestPatchCommand(),
                    RestDeleteCommand(),
                ),
                ProfileCommand().subcommands(
                    ProfileListCommand(),
                    ProfileShowCommand(),
                    ProfileSetCommand(),
                    ProfileUseCommand(),
                    ProfileDeleteCommand(),
                    ProfileExportCommand(),
                    ProfileImportCommand(),
                ),
                SetupCommand().subcommands(
                    SetupApplyCommand(),
                    SetupExportCommand(),
                ),
                *buildResourceCommands().toTypedArray(),
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
          capture        Capture REST hydrate snapshots and WS frames as replayable JSONL.
          send           Send through admin-shim WS and fold frames into a headless timeline.
          dump-timeline  Fetch conversation history and emit stable timeline JSON.
          replay         Replay a recorded WS JSONL fixture through the reducer.
          record         Capture admin-shim WS wire frames as replay-compatible JSONL.
          record-cursor-state  Snapshot highest observed run cursors from a JSONL fixture.
          disconnect     Open WS and close it cleanly.
          reconnect      Exercise disconnect/reconnect and optional run resume.
          rest           Call arbitrary authenticated Letta REST endpoints.
          profile        Manage local CLI backend profiles and defaults.
          setup          Apply/export declarative CLI app/server setup files.
          agents         Manage agents and agent-scoped attachments/messages.
          conversations  Manage conversations and conversation messages.
          tools          Manage tools and tool-agent attachments.
          blocks         Manage core-memory blocks and identity attachments.
          archives       Manage archives and archive passages.
          folders        Manage folders, files, passages, and uploads.
          groups         Manage multi-agent groups.
          identities     Manage identities and identity links.
          schedules      Manage agent scheduled messages.
          mcp            Manage MCP servers and tools.
          runs/jobs/steps Inspect and mutate execution resources.
          projects       Manage Vibesync projects and beads remotes.
          stream         Direct Letta REST/SSE path for low-level comparison.

        Run with --help on any subcommand for details.
    """.trimIndent()
}
