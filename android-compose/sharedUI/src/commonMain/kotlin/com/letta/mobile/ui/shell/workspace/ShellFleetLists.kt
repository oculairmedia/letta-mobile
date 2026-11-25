package com.letta.mobile.ui.shell.workspace

import com.letta.mobile.data.chat.runtime.toChatConversationSummaries
import com.letta.mobile.data.canvas.CanvasDocument
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
import com.letta.mobile.ui.shell.header.UnifiedSettingsOption

/**
 * The search over a host holding a conversation fleet and its roster: the header covers the
 * [settings] rows and canvases too, and the palette's "Go to" pages default to this fleet's own
 * ([fleetPaletteDestinations] of the lens's sections) unless the host has its own.
 */
fun fleetSearchSources(
    agents: List<Agent>,
    fleet: List<Conversation>,
    focusedAgentId: AgentId?,
    settings: List<UnifiedSettingsOption> = emptyList(),
    canvases: List<CanvasDocument> = emptyList(),
    destinations: List<UnifiedSearchDestination> = fleetPaletteDestinations(WorkPlayMode.Work),
) = UnifiedSearchSources(
    conversations = fleet.toChatConversationSummaries(agents.associate { it.id.value to it.name }),
    agents = agents,
    agentId = focusedAgentId?.value,
    canvases = canvases,
    destinations = destinations,
    settings = settings,
)

/**
 * The palette's "Go to" rows for a fleet host: the lens's sections (Memory, Schedules, Channels and
 * Skills in Work vocabulary), each a shell route the shared opener decodes into the host's own page.
 */
fun fleetPaletteDestinations(mode: WorkPlayMode): List<UnifiedSearchDestination> = WorkPlayLens.navDestinations(mode)
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
