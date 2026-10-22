package com.letta.mobile.ui.shell.conversations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.chat.conversations.ClientCommandRoutes
import com.letta.mobile.data.chat.conversations.ComposerHostCommandSources
import com.letta.mobile.data.chat.conversations.composerHostCommands
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.ui.chat.session.ChatHostCommand
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.TimelineChatTarget
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.conversations.ConversationManagementPrefs
import com.letta.mobile.data.chat.conversations.ConversationOrigin
import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.chat.runtime.ConversationSummaryUpdate
import com.letta.mobile.data.chat.conversations.TimelineConversations
import com.letta.mobile.data.chat.conversations.TimelineConversationsState
import com.letta.mobile.ui.components.tabs.LettaTab
import com.letta.mobile.ui.shell.ShellChatSelection
import com.letta.mobile.ui.shell.ShellDrawerConversations
import com.letta.mobile.ui.shell.SyncShellWorkspace
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import com.letta.mobile.ui.shell.sidebar.ShellArchiveFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A [TimelineConversations] owner's list as the shared drawer shows and
 * edits it: the focused agent's conversations under the panel's archive filter, pins first, forks
 * marked, and the rows' rename, archive, delete, pin and fork going to the owner.
 */
class ShellTimelineConversationList internal constructor(
    val state: TimelineConversationsState,
    val pinnedIds: Set<String>,
    val parentIds: Map<String, String>,
    val archiveFilter: ShellArchiveFilter,
    private val owner: TimelineConversations?,
    private val onArchiveFilterChange: (ShellArchiveFilter) -> Unit,
) {
    /** [agent]'s conversations as the drawer lists them (the archive filter is the drawer mapping's). */
    fun drawer(agent: AgentId?): ShellDrawerConversations = ShellDrawerConversations(
        conversations = agent?.let { focused -> state.conversations.filter { it.agentId == focused } }.orEmpty(),
        archiveFilter = archiveFilter,
        pinnedIds = pinnedIds,
        parentIds = parentIds,
    )

    /** A conversation's tab: its title, else its agent's name. */
    fun tab(id: String): LettaTab? = state.conversations.firstOrNull { it.id.value == id }?.let { conversation ->
        val title = conversation.summary?.takeIf(String::isNotBlank) ?: conversation.agentName.orEmpty().ifBlank { UNTITLED }
        LettaTab(key = id, title = title)
    }

    /** [base] with the rows' conversation actions bound to the owner. */
    fun panelActions(base: ShellAgentPanelActions): ShellAgentPanelActions {
        val owner = owner ?: return base
        val management = owner.management
        return base.copy(
            onArchiveFilterChange = onArchiveFilterChange,
            onArchiveConversation = { id, archived -> management.setArchived(ConversationId(id), archived) },
            onDeleteConversation = { management.delete(ConversationId(it)) },
            deleteBehavior = management.deleteBehavior,
            onRenameConversation = { id, title ->
                management.rename(ConversationSummaryUpdate(ConversationId(id), ConversationSummary(title)))
            },
            onPinConversation = { id, pinned -> management.setPinned(ConversationId(id), pinned) },
            onForkConversation = management.takeIf { it.supportsBranching }?.let { branching ->
                { id, name -> branching.branches.forkConversation(ConversationOrigin(ConversationId(id), agentOf(id)), name) }
            },
        )
    }

    private fun agentOf(id: String): AgentId? = state.conversations.firstOrNull { it.id.value == id }?.agentId
}

/** The owner's list for the drawer; with no owner (no session), an empty one. */
@Composable
fun rememberShellTimelineConversationList(owner: TimelineConversations?): ShellTimelineConversationList {
    val state = owner?.state.collectOr(NoConversations)
    val pinned = owner?.management?.pinnedConversationIds.collectOr(emptySet())
    val parents = owner?.management?.conversationParents.collectOr(emptyMap())
    var filter by remember { mutableStateOf(ShellArchiveFilter.Active) }
    return ShellTimelineConversationList(
        state = state,
        pinnedIds = pinned,
        parentIds = parents.mapValues { it.value.conversationId },
        archiveFilter = filter,
        owner = owner,
        onArchiveFilterChange = { filter = it },
    )
}

/**
 * Keeps [workspace] and the owner in step, as desktop's controller and the workspace are: the
 * owner's selection is the chat the workspace shows (its agent focused, a tab while the chat is
 * the page), its list is authoritative once loaded, and when nothing is selected the route's
 * conversation (a reload, a link) or else the focused agent's chat opens.
 */
@Composable
fun SyncTimelineConversations(workspace: ShellWorkspaceState, owner: TimelineConversations?, session: String) {
    // Read before the sync below reports the owner's (still empty) selection over it.
    val routed = remember(owner) { workspace.selectedConversationId }
    val state = owner?.state.collectOr(NoConversations)
    val selected = state.conversations.firstOrNull { it.id == state.selected }
    val reported = state.conversations.takeIf { state.loaded }?.mapTo(mutableSetOf()) { it.id }
    SyncShellWorkspace(workspace, ShellChatSelection(session, state.selected, selected?.agentId, reported))
    LaunchedEffect(workspace, selected?.agentId) { selected?.agentId?.let { workspace.focusedAgentId = it } }
    val chatShown = workspace.page is ShellDestination.Chat
    LaunchedEffect(owner, state.loaded, state.selected, workspace.focusedAgentId, chatShown) {
        owner?.openInitial(routed, workspace.focusedAgentId, createIfNone = chatShown)
    }
}

private const val UNTITLED = "Chat"

private val NoConversations = TimelineConversationsState()

/** The flow's current value while composed; [empty] without a flow. */
@Composable
private fun <T> StateFlow<T>?.collectOr(empty: T): T = this?.collectAsState()?.value ?: empty

/**
 * A session's shared conversation owner, rebuilt with each connection (a
 * reconnect is a new session) and running in this producer's scope, so it stops with the session.
 * [backend] is the live session's conversations; null (no session) is no owner.
 */
@Composable
fun rememberTimelineConversations(
    connection: BackendConnectionStatus,
    backend: () -> ChatGateway?,
    agents: List<Agent>,
    prefs: ConversationManagementPrefs,
): TimelineConversations? {
    val roster by rememberUpdatedState(agents)
    val owner by produceState<TimelineConversations?>(null, connection) {
        val live = backend().takeIf { connection is BackendConnectionStatus.Connected }
        value = live?.let {
            TimelineConversations(this, it, agentName = { agent -> roster.firstOrNull { a -> a.id == agent }?.name.orEmpty() }, prefs)
        }
    }
    return owner
}

/** What a host's [rememberShellTimelineChat] binds: its workspace, session and roster, and how its session opens a chat. */
class ShellTimelineChatBinding<P : ChatSessionPort>(
    val workspace: ShellWorkspaceState,
    /** The session the workspace runs over; a new one resets it. */
    val session: String,
    val connection: BackendConnectionStatus,
    val agents: List<Agent>,
    val prefs: ConversationManagementPrefs,
    /** The live session's chat gateway for its conversations; null while there is none. */
    val backend: () -> ChatGateway?,
    /** A port for the owner's selected conversation in the given scope; null while there is no session. */
    val open: (TimelineConversations, TimelineChatTarget, CoroutineScope) -> P?,
    val close: (P) -> Unit,
)

/** The chat a host shows: the session's conversation owner, the drawer's list of it, and the selected conversation's port. */
class ShellTimelineChat<P : ChatSessionPort>(
    val owner: TimelineConversations?,
    val list: ShellTimelineConversationList,
    val port: P?,
)

/**
 * A host's whole chat over the shared [TimelineConversations] owner: the
 * owner per session ([rememberTimelineConversations]), kept in step with the workspace
 * ([SyncTimelineConversations]), its list for the drawer, and a port per selected conversation whose
 * scope ends when the selection or the owner changes; the owner's waiting prompts and failures
 * reach that port's composer while it is open.
 */
@Composable
fun <P : ChatSessionPort> rememberShellTimelineChat(binding: ShellTimelineChatBinding<P>): ShellTimelineChat<P> {
    val owner = rememberTimelineConversations(binding.connection, binding.backend, binding.agents, binding.prefs)
    SyncTimelineConversations(binding.workspace, owner, binding.session)
    val list = rememberShellTimelineConversationList(owner)
    val target = remember(owner, list.state) { owner?.selectedTarget() }
    val open by rememberUpdatedState(binding.open)
    val close by rememberUpdatedState(binding.close)
    val port by produceState<P?>(null, owner, target) {
        val live = owner ?: return@produceState
        val chat = target ?: return@produceState
        val opened = open(live, chat, this) ?: return@produceState
        launch { live.deliverTo(ConversationId(chat.conversationId), opened.actions) }
        value = opened
        awaitDispose { close(opened) }
    }
    return ShellTimelineChat(owner, list, port)
}

/**
 * The composer's `/` palette for a chat on the shared shell: the shared [composerHostCommands]
 * desktop shows, routed to the shell's own pages and to [owner] (`/recompile`, `/fork`, and the
 * composer a server command fills). The shell has no model picker or rename overlay yet, so
 * `/models` and `/rename` stay out.
 */
fun ShellWorkspaceNavigator.shellComposerCommands(owner: TimelineConversations): List<ChatHostCommand> {
    val workspace = state
    val agent = { workspace.focusedAgentId?.value }
    return composerHostCommands(
        ComposerHostCommandSources(
            client = ClientCommandRoutes(
                search = { workspace.overlays.commandPalette = true },
                memory = { navigate(ShellDestination.Memory(agent())) },
                mcp = { navigate(ShellDestination.Skills(agent())) },
                newChat = { openNewChat(workspace.focusedAgentId) },
                connect = { navigate(ShellDestination.Providers) },
                recompile = owner.management::recompileOpenConversation,
            ),
            fillComposer = owner::stageComposerText,
            canFork = owner.management.supportsBranching,
            pages = listOf(
                ChatHostCommand("agent", "Create a new agent") { workspace.openNewAgent() },
                ChatHostCommand("settings", "Open settings") { navigate(ShellDestination.Settings()) },
            ),
        ),
    )
}
