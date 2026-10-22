package com.letta.mobile.data.chat.conversations

import com.letta.mobile.data.chat.branch.BranchSlashCommand
import com.letta.mobile.data.commands.AgentSlashCommand
import com.letta.mobile.data.commands.ClientSlashCommand
import com.letta.mobile.ui.chat.session.ChatHostCommand

/**
 * Where a host's client-local `/` commands lead. A null route leaves its
 * command out of the palette (the host has no such surface).
 */
data class ClientCommandRoutes(
    /** `/search`: the command palette, which searches chats and agents. */
    val search: (() -> Unit)? = null,
    val memory: (() -> Unit)? = null,
    /** `/mcp`: where the host lists MCP servers and tools. */
    val mcp: (() -> Unit)? = null,
    val models: (() -> Unit)? = null,
    val newChat: (() -> Unit)? = null,
    val rename: (() -> Unit)? = null,
    val connect: (() -> Unit)? = null,
    val recompile: (() -> Unit)? = null,
) {
    fun route(command: ClientSlashCommand): (() -> Unit)? = when (command) {
        ClientSlashCommand.Search -> search
        ClientSlashCommand.Memory -> memory
        ClientSlashCommand.Mcp -> mcp
        ClientSlashCommand.Models -> models
        ClientSlashCommand.New -> newChat
        ClientSlashCommand.Rename -> rename
        ClientSlashCommand.Connect -> connect
        ClientSlashCommand.Recompile -> recompile
    }
}

/** What a host's composer `/` palette is built from. */
data class ComposerHostCommandSources(
    val client: ClientCommandRoutes,
    /** Puts text in the composer: `/fork ` and a server command, for the user to finish and send. */
    val fillComposer: (String) -> Unit,
    /** Offers letta-code's `/fork [name]` (run by the client) where the backend forks. */
    val canFork: Boolean = false,
    /** The host's own page commands, after the client ones. */
    val pages: List<ChatHostCommand> = emptyList(),
    /** The focused agent's server slash commands (goal mode, installed skills). */
    val server: List<AgentSlashCommand> = emptyList(),
)

/**
 * The composer's `/` palette every host shows: the client commands it routes, `/fork`, its page
 * commands, then the agent's server commands. Selecting a server command fills the composer so the
 * user can add arguments and send; the server interprets the slash prefix. A server command named
 * like a command already in the palette would shadow it (commands are keyed by label), so it is dropped.
 */
fun composerHostCommands(sources: ComposerHostCommandSources): List<ChatHostCommand> = buildList {
    ClientSlashCommand.entries.forEach { command ->
        sources.client.route(command)?.let { add(ChatHostCommand(command.id, command.description, run = it)) }
    }
    if (sources.canFork) {
        val fork = "/${BranchSlashCommand.FORK} "
        add(ChatHostCommand(BranchSlashCommand.FORK, FORK_DESCRIPTION, fillsComposer = true) { sources.fillComposer(fork) })
    }
    addAll(sources.pages)
    val taken = mapTo(mutableSetOf()) { it.label }
    sources.server.filter { ClientSlashCommand.fromId(it.command) == null && it.command !in taken }.forEach { command ->
        val description = command.description.ifBlank { command.skillName?.let { "Skill: $it" } ?: "Slash command" }
        add(ChatHostCommand(command.command, description, fillsComposer = true) { sources.fillComposer("/${command.command} ") })
    }
}

private const val FORK_DESCRIPTION = "Fork this chat (/fork [name])"
