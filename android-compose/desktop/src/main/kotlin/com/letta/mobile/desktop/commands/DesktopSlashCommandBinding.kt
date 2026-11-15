package com.letta.mobile.desktop.commands

import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.graph.AppServerSlashCommandApi
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.ui.chat.commands.SlashCommandBinding

/** The current desktop graph and focused conversation, captured again for each command. */
internal class DesktopSlashCommandBinding(
    private val graph: () -> SessionRepositoryGraph,
    private val conversation: () -> ChatConversationSummary?,
    private val isCurrent: () -> Boolean,
) : SlashCommandBinding {
    override fun capture(): Pair<AppServerSlashCommandApi, AppServerRuntimeScope>? {
        if (!isCurrent()) return null
        val chat = conversation() ?: return null
        val api = graph().slashCommands
        val agent = chat.agentId
        return if (api != null && agent != null) api to AppServerRuntimeScope(agent, chat.id) else null
    }
}
