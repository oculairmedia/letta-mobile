package com.letta.mobile.data.commands

import com.letta.mobile.ui.chat.session.ChatComposerCommand

/**
 * letta-mobile-zxk2n.5 (G92): the `/` commands the app answers itself instead of sending to the
 * agent. Each opens a screen or dialog the platform already has, or (for [Recompile]) runs an App
 * Server command, so they appear in the composer's autocomplete next to the server's own commands.
 *
 * A host offers only the ones it can route; [ClientSlashCommands.composerCommands] lists them.
 */
enum class ClientSlashCommand(val id: String, val description: String) {
    Search("search", "Search this agent's messages"),
    Memory("memory", "Open the agent's memory"),
    Mcp("mcp", "Manage MCP servers and tools"),
    Models("models", "Choose the model for this chat"),
    New("new", "Start a new chat"),
    Rename("rename", "Rename this chat"),
    Connect("connect", "Connect a model provider"),
    Recompile("recompile", "Rebuild the system prompt from the agent's memory"),
    ;

    companion object {
        /** The client command named by [id] (with or without its leading `/`), or null. */
        fun fromId(id: String): ClientSlashCommand? {
            val bare = id.trim().removePrefix("/").lowercase()
            return entries.firstOrNull { it.id == bare }
        }
    }
}

object ClientSlashCommands {
    /** [command] as a composer entry: an action command, so choosing it runs instead of sending. */
    fun toComposerCommand(command: ClientSlashCommand): ChatComposerCommand =
        ChatComposerCommand(id = command.id, label = command.id, description = command.description, fillsComposer = false)

    /**
     * The composer's `/` list: the [offered] client commands, then the server's [server] commands.
     * A server command named like an offered client command is dropped, so the name means one thing.
     */
    fun composerCommands(offered: Collection<ClientSlashCommand>, server: List<ChatComposerCommand>): List<ChatComposerCommand> {
        val clientIds = offered.map { it.id }.toSet()
        val ordered = ClientSlashCommand.entries.filter { it in offered }
        return ordered.map(::toComposerCommand) + server.filterNot { it.id.removePrefix("/").lowercase() in clientIds }
    }

    /** The client command a chosen composer entry stands for, or null for a server command. */
    fun resolve(command: ChatComposerCommand): ClientSlashCommand? =
        if (command.fillsComposer) null else ClientSlashCommand.fromId(command.id)
}
