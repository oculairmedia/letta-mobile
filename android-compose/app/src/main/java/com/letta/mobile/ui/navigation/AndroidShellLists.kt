package com.letta.mobile.ui.navigation

import com.letta.mobile.data.chat.runtime.toChatConversationSummaries
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.ui.components.tabs.LettaTab
import com.letta.mobile.ui.shell.header.UnifiedSearchSources

/** What the header's search looks through on Android: the fleet's conversations and the roster. */
internal fun searchSources(shell: WorkspaceShell, agents: List<Agent>, fleet: List<Conversation>) = UnifiedSearchSources(
    conversations = fleet.toChatConversationSummaries(agents.associate { it.id.value to it.name }),
    agents = agents,
    agentId = shell.workspace.focusedAgentId?.value,
)

/** A tab's title: the conversation's summary, else its agent's name. */
internal fun tabFor(fleet: List<Conversation>): (String) -> LettaTab? = { id ->
    fleet.firstOrNull { it.id.value == id }?.let {
        LettaTab(key = id, title = it.summary?.takeIf(String::isNotBlank) ?: it.agentName.orEmpty().ifBlank { "Chat" })
    }
}
