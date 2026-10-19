package com.letta.mobile.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.letta.mobile.data.agents.RecentAgentsPolicy
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellRoute
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.data.shell.lensSection
import com.letta.mobile.data.shell.toShellDestination
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import kotlin.time.Instant

/**
 * The shell's one [ShellWorkspaceState], kept across a saved-state round
 * trip as its page's canonical route and the rail's width (never the pairing secret or the tabs).
 */
@Composable
fun rememberShellWorkspaceState(initialPage: ShellDestination): ShellWorkspaceState =
    rememberSaveable(saver = ShellWorkspaceSaver) { ShellWorkspaceState(initialPage) }

/** Saved as the rail's width mark, then the page's route: `+home`, `-chat?agent=a`. */
private val ShellWorkspaceSaver = Saver<ShellWorkspaceState, String>(
    save = { (if (it.railExpanded) RAIL_EXPANDED else RAIL_COLLAPSED) + ShellRoute.encode(it.page) },
    restore = { saved ->
        val page = ShellRoute.decode(saved.drop(1)) ?: ShellDestination.Home
        ShellWorkspaceState(page).apply { railExpanded = saved.startsWith(RAIL_EXPANDED) }
    },
)

private const val RAIL_EXPANDED = "+"
private const val RAIL_COLLAPSED = "-"

/** What a host's chat shows, as [SyncShellWorkspace] reports it to the workspace. */
data class ShellChatSelection(
    /** The session the chat runs over; a new one resets the workspace ([ShellWorkspaceState.sessionChanged]). */
    val session: String,
    val conversation: ConversationId?,
    val agent: AgentId?,
    /** The chat's conversations once its backend has reported them authoritatively; null until then. */
    val reported: Set<ConversationId>?,
)

/**
 * Keeps [workspace] in step with the host's chat: a new session resets it, the shown conversation is
 * its agent's last active one and gets a tab while the chat is the page, and once the conversations
 * are reported, gone ones lose their tabs.
 */
@Composable
fun SyncShellWorkspace(workspace: ShellWorkspaceState, selection: ShellChatSelection) {
    LaunchedEffect(workspace, selection.session) { workspace.sessionChanged(selection.session) }
    LaunchedEffect(workspace, workspace.page.kind, selection.conversation, selection.agent) {
        workspace.conversationShown(selection.conversation, selection.agent)
    }
    LaunchedEffect(workspace, selection.reported) { selection.reported?.let(workspace::conversationsReported) }
}

/**
 * The shared navigation drawer's input over the workspace and its roster [agents] as (id, name):
 * the focused agent heads the panel, the rest are on the rail. With no activity signal to cut a
 * recents strip by, the whole roster shows, scrolling.
 */
fun ShellWorkspaceState.navDrawerInput(agents: List<Pair<String, String>>): ShellNavDrawerInput {
    val focusedIndex = agents.indexOfFirst { it.first == focusedAgentId?.value }
    return ShellNavDrawerInput(
        agent = ShellPanelAgent(
            name = agents.getOrNull(focusedIndex)?.second ?: "Letta",
            orbIndex = focusedIndex.coerceAtLeast(0),
            agentId = focusedAgentId?.value?.takeIf { focusedIndex >= 0 },
        ),
        agents = agents,
        recentAgentsPolicy = RecentAgentsPolicy(maxAgents = agents.size.coerceAtLeast(1), fillToCap = true),
        openConversationId = selectedConversationId?.value,
    )
}

/** The drawer's state for the workspace: Home, Settings and the open section draw selected. */
fun ShellWorkspaceState.navDrawerState(agents: List<Pair<String, String>>, now: Instant): ShellNavDrawerState {
    val base = ShellNavDrawerMapping.state(navDrawerInput(agents), now)
    val home = page == ShellDestination.Home
    return base.copy(
        rail = base.rail.copy(homeSelected = home, expanded = railExpanded),
        panel = base.panel.copy(
            mode = workPlayMode,
            home = home,
            selectedSection = page.lensSection,
            settingsSelected = page is ShellDestination.Settings,
        ),
    )
}

/** Where the drawer's controls go; [afterNavigate] closes a modal drawer. */
fun ShellWorkspaceNavigator.navDrawerActions(afterNavigate: () -> Unit): ShellNavDrawerActions {
    fun go(destination: ShellDestination) {
        navigate(destination)
        afterNavigate()
    }
    fun newChat() {
        openNewChat(state.focusedAgentId)
        afterNavigate()
    }
    return ShellNavDrawerActions(
        rail = ShellAgentRailActions(
            onAgentSelected = { agentId ->
                openAgent(AgentId(agentId))
                afterNavigate()
            },
            onHome = { go(ShellDestination.Home) },
            onNewSession = ::newChat,
            onAgentSettings = { agentId -> go(ShellDestination.AgentEditor(agentId)) },
        ),
        panel = ShellAgentPanelActions(
            onOpenSection = { section -> go(section.toShellDestination(state.focusedAgentId?.value)) },
            onOpenSettings = { go(ShellDestination.Settings()) },
            onNewChat = ::newChat,
            onEditAgent = { state.focusedAgentId?.let { go(ShellDestination.AgentEditor(it.value)) } },
            onConversationSelected = { id ->
                openConversation(ConversationId(id))
                afterNavigate()
            },
        ),
    )
}
