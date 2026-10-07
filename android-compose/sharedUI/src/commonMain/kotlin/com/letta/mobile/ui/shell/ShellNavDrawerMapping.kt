package com.letta.mobile.ui.shell

import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.chat.runtime.toChatConversationSummaries
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.ui.shell.rail.ShellAgentRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.rail.ShellRailMapping
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellArchiveFilter
import com.letta.mobile.ui.shell.sidebar.ShellCanvasRowModel
import com.letta.mobile.ui.shell.sidebar.ShellConversationMarks
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import com.letta.mobile.ui.shell.sidebar.ShellRelativeTime
import com.letta.mobile.ui.shell.sidebar.ShellSidebarMapping
import kotlin.time.Instant

/** What a host knows about navigation, in platform-neutral terms: the drawer's input. */
data class ShellNavDrawerInput(
    /** The focused agent; it is the panel's subject and leaves the rail. */
    val agent: ShellPanelAgent,
    /** Every agent as (id, name), in the rail's order. */
    val agents: List<Pair<String, String>> = emptyList(),
    /** Mascot identities by agent id (the rail's orbs and the panel's hero). */
    val identities: Map<String, MascotIdentity> = emptyMap(),
    /** The focused agent's conversations; the filter is applied here, not by the host. */
    val conversations: List<Conversation> = emptyList(),
    val openConversationId: String? = null,
    val archiveFilter: ShellArchiveFilter = ShellArchiveFilter.Active,
    /** Canvases are shared across agents, so these are all of them. */
    val canvases: List<CanvasDocument> = emptyList(),
    val archivedCanvasIds: Set<CanvasId> = emptySet(),
    val hiddenSections: Set<LensDestination> = emptySet(),
    /** Agents the user pinned, for the rail orbs' Pin / Unpin. */
    val pinnedAgentIds: Set<String> = emptySet(),
)

/** Pure mapping from [ShellNavDrawerInput] to what the drawer draws. */
object ShellNavDrawerMapping {
    fun state(input: ShellNavDrawerInput, now: Instant): ShellNavDrawerState {
        val agentId = input.agent.agentId
        val focus = ShellRailFocus(
            selectedAgentId = agentId,
            identityByAgentId = input.identities,
            pinnedAgentIds = input.pinnedAgentIds,
        )
        val rail = ShellAgentRailState(entries = ShellRailMapping.entries(ShellRailMapping.groups(input.agents, agentId), focus))
        val panel = ShellAgentPanelState(
            agent = input.agent.copy(identity = input.agent.identity ?: agentId?.let { input.identities[it] }),
            hiddenSections = input.hiddenSections,
            archiveFilter = input.archiveFilter,
            conversations = ShellSidebarMapping.conversationRows(
                conversations = input.conversations.toChatConversationSummaries()
                    .filter { input.archiveFilter.admits(it.archived) },
                marks = ShellConversationMarks(selectedId = input.openConversationId),
                timeLabel = { ShellRelativeTime.compact(it, now) },
            ),
            canvases = canvasRows(input, now),
        )
        return ShellNavDrawerState(rail = rail, panel = panel)
    }

    /** The canvases under the filter, most recently edited first. */
    fun canvasRows(input: ShellNavDrawerInput, now: Instant): List<ShellCanvasRowModel> =
        input.canvases
            .filter { input.archiveFilter.admits(it.id in input.archivedCanvasIds) }
            .sortedByDescending { it.updatedAtEpochMs }
            .map { canvas ->
                ShellCanvasRowModel(
                    id = canvas.id,
                    title = canvas.title,
                    timeLabel = ShellRelativeTime.compact(Instant.fromEpochMilliseconds(canvas.updatedAtEpochMs), now),
                    archived = canvas.id in input.archivedCanvasIds,
                )
            }
}
