package com.letta.mobile.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.letta.mobile.cli.commands.DumpTimelineCommand
import com.letta.mobile.cli.commands.RecordCursorStateCommand
import com.letta.mobile.cli.commands.ReplayCommand
import com.letta.mobile.cli.commands.AppServerServeCommand
import com.letta.mobile.cli.commands.AgentMessageCommand
import com.letta.mobile.cli.commands.AgentMessageSendCommand
import com.letta.mobile.cli.commands.AppServerServeIrohCommand
import com.letta.mobile.cli.commands.AppServerServeIrohStubCommand
import com.letta.mobile.cli.commands.AppServerIrohDeviceGateCommand
import com.letta.mobile.cli.commands.AppServerIrohProbeCommand
import com.letta.mobile.cli.commands.AppServerIrohTwoClientProbeCommand
import com.letta.mobile.cli.commands.AppServerSmokeCommand
import com.letta.mobile.cli.commands.PeerCommand
import com.letta.mobile.cli.commands.PeerGetCommand
import com.letta.mobile.cli.commands.PeerInviteCommand
import com.letta.mobile.cli.commands.PeerInviteListCommand
import com.letta.mobile.cli.commands.PeerInviteRevokeCommand
import com.letta.mobile.cli.commands.PeerSetAgentsCommand
import com.letta.mobile.cli.commands.PeerListCommand
import com.letta.mobile.cli.commands.PeerRenameCommand
import com.letta.mobile.cli.commands.PeerRevokeCommand
import com.letta.mobile.cli.commands.PeerSetCapabilitiesCommand
import com.letta.mobile.cli.swarm.AgentsListCommand
import com.letta.mobile.cli.swarm.MessagePendingCommand
import com.letta.mobile.cli.swarm.MessageReadCommand
import com.letta.mobile.cli.swarm.MessageSendCommand
import com.letta.mobile.cli.swarm.MessageStatusCommand
import com.letta.mobile.cli.swarm.SwarmGroupCommand
import com.letta.mobile.cli.swarm.SwarmPairCommand
import com.letta.mobile.cli.swarm.SwarmUnpairCommand

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
                DumpTimelineCommand(),
                ReplayCommand(),
                RecordCursorStateCommand(),
                AppServerServeCommand(),
                AppServerServeIrohCommand(),
                AgentMessageCommand().subcommands(AgentMessageSendCommand()),
                PeerCommand().subcommands(
                    PeerInviteCommand(),
                    PeerListCommand(),
                    PeerGetCommand(),
                    PeerRenameCommand(),
                    PeerSetCapabilitiesCommand(),
                    PeerRevokeCommand(),
                    PeerInviteListCommand(),
                    PeerInviteRevokeCommand(),
                    PeerSetAgentsCommand(),
                ),
                AppServerServeIrohStubCommand(),
                AppServerIrohProbeCommand(),
                AppServerIrohTwoClientProbeCommand(),
                AppServerIrohDeviceGateCommand(),
                AppServerSmokeCommand(),
                // letta-mobile-jvctq: talk to the agent swarm as a paired device.
                SwarmPairCommand(),
                SwarmUnpairCommand(),
                SwarmGroupCommand("agents", "Agents this paired device may message.").subcommands(AgentsListCommand()),
                SwarmGroupCommand("message", "Message an agent as this paired device, with staged delivery receipts.")
                    .subcommands(MessageSendCommand(), MessageReadCommand(), MessageStatusCommand(), MessagePendingCommand()),
            )
            .main(args)
    }

    private val USAGE = """
        meridian - drive Letta Mobile transport and timeline code paths headlessly.

        Usage:
          ./gradlew :cli:run -PcliArgs="<command> [options]"

        Commands:
          dump-timeline  Fetch conversation history and emit stable timeline JSON.
          replay         Replay a recorded JSONL fixture through the reducer.
          record-cursor-state  Snapshot highest observed run cursors from a JSONL fixture.
          app-server-serve  Launch host letta app-server with optional WS auth.
          app-server-serve-iroh-stub  Hermetic stub app-server over Iroh for probe CI.
          app-server-iroh-probe  Run headless Iroh transport probe scenarios.
          app-server-iroh-device-gate  Assert on-device Iroh live-stream invariants from logcat + /proc/net/tcp.
          app-server-smoke  Send one turn through a running Letta App Server.
          pair / unpair  Pair this machine as a device with a one-time invite (or forget it).
          agents list    Agents this paired device may message.
          message send|read|status|pending  Message an agent over Iroh with staged receipts.

        Run with --help on any subcommand for details.
    """.trimIndent()
}
