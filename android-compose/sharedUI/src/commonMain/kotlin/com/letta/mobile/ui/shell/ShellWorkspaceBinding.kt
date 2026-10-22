package com.letta.mobile.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.agents.RecentAgentsPolicy
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
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
import com.letta.mobile.ui.shell.sidebar.ShellArchiveFilter
import com.letta.mobile.ui.shell.sidebar.ShellChatOptionsState
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
fun ShellWorkspaceState.navDrawerInput(
    agents: List<Pair<String, String>>,
    conversations: ShellDrawerConversations = ShellDrawerConversations(),
    library: ShellDrawerLibrary = ShellDrawerLibrary(),
): ShellNavDrawerInput {
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
        conversations = conversations.conversations,
        archiveFilter = conversations.archiveFilter,
        pinnedConversationIds = conversations.pinnedIds,
        conversationParents = conversations.parentIds,
        settledConversationIds = conversations.settledIds,
        conversationMarkers = conversations.markers,
        chatOptions = library.chatOptions,
        canvases = library.canvases,
        archivedCanvasIds = library.archivedCanvasIds,
        pinnedAgentIds = library.pinnedAgentIds,
        identities = library.identities,
    )
}

/** The drawer's state for the workspace: Home, Settings and the open section draw selected. */
fun ShellWorkspaceState.navDrawerState(
    agents: List<Pair<String, String>>,
    now: Instant,
    conversations: ShellDrawerConversations = ShellDrawerConversations(),
    library: ShellDrawerLibrary = ShellDrawerLibrary(),
): ShellNavDrawerState {
    val base = ShellNavDrawerMapping.state(navDrawerInput(agents, conversations, library), now)
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

/** The chat's conversations as the drawer lists them: the focused agent's, under the archive filter, with pins and forks. */
data class ShellDrawerConversations(
    val conversations: List<Conversation> = emptyList(),
    val archiveFilter: ShellArchiveFilter = ShellArchiveFilter.Active,
    val pinnedIds: Set<String> = emptySet(),
    /** Each fork's source conversation id. */
    val parentIds: Map<String, String> = emptyMap(),
    /** Settled conversations (listed last) and each conversation's marker. */
    val settledIds: Set<String> = emptySet(),
    val markers: Map<String, String> = emptyMap(),
)

/** What else the rail and panel show: mascot identities, the Chat options, the canvas library and the agents pinned to Home. */
data class ShellDrawerLibrary(
    /** Mascot identities by agent id (the rail's orbs and the panel's hero). */
    val identities: Map<String, MascotIdentity> = emptyMap(),
    /** The Chat options dialog's state; null leaves it out of the agent menu. */
    val chatOptions: ShellChatOptionsState? = null,
    val canvases: List<CanvasDocument> = emptyList(),
    val archivedCanvasIds: Set<CanvasId> = emptySet(),
    val pinnedAgentIds: Set<String> = emptySet(),
)
