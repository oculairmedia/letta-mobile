package com.letta.mobile.ui.shell.workspace

import com.letta.mobile.avatar.core.MascotIdentity
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
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import kotlin.time.Instant
/**
 * The workspace's navigation over the workspace and its roster [agents] as
 * (id, name): the whole roster on the rail (its library lists them all), the focused agent's panel.
 */
fun ShellWorkspaceState.workspaceNavigation(
    agents: List<Pair<String, String>>,
    now: Instant,
    identities: Map<String, MascotIdentity> = emptyMap(),
): ShellWorkspaceNavigation {
    val drawer = navDrawerState(agents, now)
    return ShellWorkspaceNavigation(
        rail = ShellLibraryRailState(
            agents = agents,
            focus = ShellRailFocus(selectedAgentId = focusedAgentId?.value, identityByAgentId = identities),
            expanded = railExpanded,
            homeSelected = drawer.rail.homeSelected,
        ),
        panel = drawer.panel.copy(agent = drawer.panel.agent.copy(identity = drawer.panel.agent.agentId?.let(identities::get))),
    )
}

/**
 * The workspace's navigation verbs: the drawer's, with the rail's "+" opening the New conversation
 * picker and a pick from the expanded rail (the library) closing it, as on the desktop.
 */
fun ShellWorkspaceNavigator.workspaceNavigationActions(): ShellWorkspaceNavigationActions {
    val drawer = navDrawerActions(afterNavigate = {})
    return ShellWorkspaceNavigationActions(
        rail = drawer.rail.copy(
            onAgentSelected = { agentId ->
                state.railExpanded = false
                drawer.rail.onAgentSelected(agentId)
            },
            onNewSession = { state.overlays.newConversation = true },
        ),
        panel = drawer.panel,
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
