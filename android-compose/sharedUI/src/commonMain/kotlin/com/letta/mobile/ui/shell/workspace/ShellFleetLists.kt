package com.letta.mobile.ui.shell.workspace

import com.letta.mobile.data.chat.runtime.toChatConversationSummaries
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.ui.components.tabs.LettaTab
import com.letta.mobile.ui.shell.header.UnifiedSearchSources

/** What the header's search looks through for a host holding a conversation fleet and its roster. */
fun fleetSearchSources(agents: List<Agent>, fleet: List<Conversation>, focusedAgentId: AgentId?) = UnifiedSearchSources(
    conversations = fleet.toChatConversationSummaries(agents.associate { it.id.value to it.name }),
    agents = agents,
    agentId = focusedAgentId?.value,
)

/** A tab's title from the fleet: the conversation's summary, else its agent's name. */
fun fleetTabFor(fleet: List<Conversation>): (String) -> LettaTab? = { id ->
    fleet.firstOrNull { it.id.value == id }?.let {
        LettaTab(key = id, title = it.summary?.takeIf(String::isNotBlank) ?: it.agentName.orEmpty().ifBlank { "Chat" })
    }
}
