package com.letta.mobile.ui.shell.workspace

import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.agents.RecentAgents
import com.letta.mobile.data.agents.RecentAgentsInput
import com.letta.mobile.data.agents.RecentAgentsPolicy
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellRoute
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.ui.shell.header.UnifiedSearchHit
import com.letta.mobile.ui.shell.header.UnifiedSearchKind
import com.letta.mobile.ui.shell.navDrawerActions
import com.letta.mobile.ui.shell.navDrawerState
import com.letta.mobile.ui.shell.ShellDrawerConversations
import com.letta.mobile.ui.shell.ShellDrawerLibrary
import com.letta.mobile.ui.shell.conversations.ShellTimelineConversationList
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import kotlin.time.Instant
/**
 * The workspace's navigation over the workspace and its roster [agents] as
 * (id, name): the recents cut on the rail, most recently used first
 * by the [conversations] list's activity, and the focused agent's panel.
 */
fun ShellWorkspaceState.workspaceNavigation(
    agents: List<Pair<String, String>>,
    now: Instant,
    identities: Map<String, MascotIdentity> = emptyMap(),
    conversations: ShellTimelineConversationList? = null,
): ShellWorkspaceNavigation =
    workspaceNavigation(
        agents = agents,
        now = now,
        conversations = conversations?.drawer(focusedAgentId) ?: ShellDrawerConversations(),
        library = ShellDrawerLibrary(
            identities = identities,
            agentLastActiveAt = conversations?.state?.conversations?.let(RecentAgents::lastActiveAtFromConversations).orEmpty(),
        ),
    )

/**
 * [workspaceNavigation] over a host's own list of the focused agent's [conversations] (Android's
 * conversation repository), with its marks and its [library] (identities, Chat options, canvases) in the panel.
 */
fun ShellWorkspaceState.workspaceNavigation(
    agents: List<Pair<String, String>>,
    now: Instant,
    conversations: ShellDrawerConversations,
    library: ShellDrawerLibrary = ShellDrawerLibrary(),
): ShellWorkspaceNavigation {
    val ordered = recentFirst(agents, library, now, focusedAgentId?.value)
    val drawer = navDrawerState(ordered, now, conversations, library)
    val identities = library.identities
    return ShellWorkspaceNavigation(
        rail = ShellLibraryRailState(
            agents = ordered,
            focus = ShellRailFocus(
                selectedAgentId = focusedAgentId?.value,
                identityByAgentId = identities,
                pinnedAgentIds = library.pinnedAgentIds,
            ),
            expanded = railExpanded,
            homeSelected = drawer.rail.homeSelected,
        ),
        panel = drawer.panel.copy(agent = drawer.panel.agent.copy(identity = drawer.panel.agent.agentId?.let(identities::get))),
    )
}

/**
 * The rail's recents strip, by the shared recents rule ([RecentAgents.cut]) capped at
 * [RecentAgentsPolicy.DEFAULT_MAX_AGENTS]: pinned agents first, then the most recently active,
 * topped up from roster order, plus the focused agent when it fell past the cap.
 */
internal fun recentFirst(
    agents: List<Pair<String, String>>,
    library: ShellDrawerLibrary,
    now: Instant,
    selectedAgentId: String? = null,
): List<Pair<String, String>> =
    RecentAgents.cut(
        RecentAgentsInput(
            directory = agents,
            lastActiveAt = library.agentLastActiveAt,
            selectedAgentId = selectedAgentId,
            pinnedAgentIds = library.pinnedAgentIds,
        ),
        now,
        RecentAgentsPolicy(fillToCap = true),
    ).agents

/**
 * The workspace's navigation verbs: the drawer's, with the rail's "+" opening the New conversation
 * picker and a pick from the expanded rail (the library) closing it, as on the desktop.
 */
fun ShellWorkspaceNavigator.workspaceNavigationActions(
    conversations: ShellTimelineConversationList? = null,
): ShellWorkspaceNavigationActions {
    val drawer = navDrawerActions(afterNavigate = {})
    return ShellWorkspaceNavigationActions(
        rail = drawer.rail.copy(
            onAgentSelected = { agentId ->
                state.railExpanded = false
                drawer.rail.onAgentSelected(agentId)
            },
            onNewSession = { state.overlays.newConversation = true },
        ),
        panel = conversations?.panelActions(drawer.panel) ?: drawer.panel,
        onRailDismiss = { state.railExpanded = false },
    )
}

/**
 * Opens a unified-search pick, from the header search, the command palette or a quick-query bar
 * alike: a message opens its conversation, a canvas goes to [openCanvas], and a destination hit
 * names a page through [destinationOf] (by default its shell route).
 */
fun ShellWorkspaceNavigator.openSearchHit(
    hit: UnifiedSearchHit,
    openCanvas: (CanvasId) -> Unit = {},
    destinationOf: (String) -> ShellDestination? = ShellRoute::decode,
) {
    when (hit.kind) {
        UnifiedSearchKind.Conversations, UnifiedSearchKind.Messages -> openConversation(ConversationId(hit.id))
        UnifiedSearchKind.Agents -> openAgent(AgentId(hit.id))
        UnifiedSearchKind.Canvases -> openCanvas(CanvasId(hit.id))
        UnifiedSearchKind.Destinations -> destinationOf(hit.id)?.let { navigate(it) }
    }
}
