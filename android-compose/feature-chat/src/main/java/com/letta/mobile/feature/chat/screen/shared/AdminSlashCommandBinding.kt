package com.letta.mobile.feature.chat.screen.shared

import com.letta.mobile.data.session.graph.AppServerSlashCommandApi
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.ui.chat.commands.SlashCommandBinding

/** The graph and conversation currently displayed by Android's chat host. */
internal class AdminSlashCommandBinding(private val viewModel: AdminChatViewModel) : SlashCommandBinding {
    override fun capture(): Pair<AppServerSlashCommandApi, AppServerRuntimeScope>? {
        if (!viewModel.focusCanSend) return null
        val api = viewModel.sessionGraph.value.slashCommands ?: return null
        val conversation = viewModel.conversationId?.value ?: return null
        return api to AppServerRuntimeScope(viewModel.agentId.value, conversation)
    }
}
