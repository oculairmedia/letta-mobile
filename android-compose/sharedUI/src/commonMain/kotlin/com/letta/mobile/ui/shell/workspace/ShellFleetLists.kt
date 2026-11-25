package com.letta.mobile.ui.shell.workspace

import com.letta.mobile.data.chat.runtime.toChatConversationSummaries
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.shell.ShellRoute
import com.letta.mobile.data.shell.toShellDestination
import com.letta.mobile.ui.components.tabs.LettaTab
import com.letta.mobile.ui.shell.header.UnifiedSearchDestination
import com.letta.mobile.ui.shell.header.UnifiedSearchSources

/**
 * The search over a host holding a conversation fleet and its roster: the conversations, the agents
 * and the focused agent fill [options]'s own empty seats, so the header and the palette share one
 * sources object. [options] takes the palette to the fleet's own pages by default
 * ([fleetPaletteDestinations]); a host narrows it with its own seats (`copy`), or widens it.
 */
fun fleetSearchSources(
    agents: List<Agent>,
    fleet: List<Conversation>,
    focusedAgentId: AgentId?,
    options: UnifiedSearchSources = UnifiedSearchSources(destinations = fleetPaletteDestinations()),
): UnifiedSearchSources = options.copy(
    conversations = fleet.toChatConversationSummaries(agents.associate { it.id.value to it.name }),
    agents = agents,
    agentId = focusedAgentId?.value,
)

/**
 * The palette's "Go to" rows for a fleet host: the lens's sections (Memory, Schedules, Channels and
 * Skills — [WorkPlayMode.Work]'s vocabulary by default), each a shell route the shared opener
 * decodes into the host's own page.
 */
fun fleetPaletteDestinations(mode: WorkPlayMode = WorkPlayMode.Work): List<UnifiedSearchDestination> = WorkPlayLens.navDestinations(mode)
    .map { section ->
        UnifiedSearchDestination(
            id = ShellRoute.encode(section.toShellDestination(null)),
            label = WorkPlayLens.destinationLabel(mode, section),
        )
    }
    // Picker rows are keyed by id: a section reached twice is listed once.
    .distinctBy { it.id }

/** A tab's title from the fleet: the conversation's summary, else its agent's name. */
fun fleetTabFor(fleet: List<Conversation>): (String) -> LettaTab? = { id ->
    fleet.firstOrNull { it.id.value == id }?.let {
        LettaTab(key = id, title = it.summary?.takeIf(String::isNotBlank) ?: it.agentName.orEmpty().ifBlank { "Chat" })
    }
}
