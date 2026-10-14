package com.letta.mobile.ui.screens.conversations

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.chat.runtime.toChatConversationSummary
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.picker.LettaPicker
import com.letta.mobile.ui.components.picker.LettaPickerSpec
import com.letta.mobile.ui.components.picker.PickerCallbacks
import com.letta.mobile.ui.components.picker.PickerItem
import com.letta.mobile.ui.components.picker.PickerPane
import com.letta.mobile.ui.components.picker.PickerSection
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.shell.ShellPage
import com.letta.mobile.ui.shell.ShellPageDeps
import com.letta.mobile.ui.shell.header.HeaderAction
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.pages.conversations.ConversationsPageActions
import com.letta.mobile.ui.shell.pages.conversations.ConversationsPageInput
import com.letta.mobile.ui.shell.pages.conversations.ConversationsPageMapping
import com.letta.mobile.ui.shell.pages.conversations.ConversationsPageState
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.shell.scaffold.ShellListDetail
import com.letta.mobile.ui.shell.shellConversationsPage
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import com.letta.mobile.ui.shell.sidebar.ShellArchiveFilter
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaTopBarDefaults
import kotlin.time.Clock

/** Where the conversations page sends the user. */
data class ConversationsScreenNavigation(
    /** Opens a chat full screen (a narrow window); a null conversation starts a new one. */
    val onOpenChat: (agentId: String, conversationId: String?, agentName: String?) -> Unit,
    val onNavigateToSettings: () -> Unit,
    val onNavigateToAgentList: () -> Unit,
    val onCreateFirstAgent: () -> Unit,
    val activeBackendLabel: String? = null,
    val onNavigateToBackendSwitcher: (() -> Unit)? = null,
)

/** The chat a wide window shows beside the list; a null [conversationId] is a new chat. */
@Immutable
data class ConversationsDetail(val agentId: String, val conversationId: String?, val agentName: String?)

/** Draws [ConversationsDetail] (or the empty pane for null) beside the list; [onClose] empties it, [onOpen] switches it. */
fun interface ConversationsDetailPane {
    @Composable
    fun Content(detail: ConversationsDetail?, onClose: () -> Unit, onOpen: (ConversationsDetail) -> Unit)
}

object ConversationsScreenTags {
    const val NEW_CHAT = "conversations-new-chat"
    const val AGENT_PICKER = "conversations-agent-picker"
}

private val DetailSaver = listSaver<ConversationsDetail?, String?>(
    save = { detail -> detail?.let { listOf(it.agentId, it.conversationId, it.agentName) } ?: emptyList() },
    restore = { values -> values.firstOrNull()?.let { ConversationsDetail(it, values[1], values[2]) } },
)

/**
 * letta-mobile-ckmub.2.4 (hard cut S16): the Chat tab's root. A container only: the shared shell
 * header, the shared conversations page ([ShellPage] on a chat destination without an agent) and the
 * shell's wide layout ([ShellListDetail]), bound to [ConversationsViewModel]. In a wide window a chat
 * opens beside the list in [detailPane]; in a narrow one it opens full screen.
 */
@Composable
fun ConversationsScreen(
    navigation: ConversationsScreenNavigation,
    detailPane: ConversationsDetailPane,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(ShellArchiveFilter.Active) }
    var detail by rememberSaveable(stateSaver = DetailSaver) { mutableStateOf<ConversationsDetail?>(null) }
    var pickingAgent by rememberSaveable { mutableStateOf(false) }
    val layout = remember { ConversationsLayout() }
    val open: (ConversationsDetail) -> Unit = { target ->
        if (layout.wide) detail = target else navigation.onOpenChat(target.agentId, target.conversationId, target.agentName)
    }
    val binding = ConversationsBinding(viewModel, uiState, open)
    val pageState = remember(uiState, filter, detail) { binding.pageState(filter, detail?.conversationId) }
    ConversationsRouteContent(
        page = ConversationsRoutePage(
            state = pageState,
            actions = binding.pageActions(onFilter = { filter = it }, onCreateAgent = navigation.onCreateFirstAgent),
            chrome = ConversationsChrome(
                backendLabel = navigation.activeBackendLabel,
                onBackendClick = navigation.onNavigateToBackendSwitcher,
                onAgents = navigation.onNavigateToAgentList,
                onSettings = navigation.onNavigateToSettings,
                onNewChat = { pickingAgent = true },
            ),
        ),
        onWideChange = { layout.wide = it },
        detail = { detailPane.Content(detail, onClose = { detail = null }, onOpen = { detail = it }) },
    )
    if (pickingAgent) {
        NewChatAgentPicker(uiState.agents, onDismiss = { pickingAgent = false }) { agent ->
            pickingAgent = false
            open(ConversationsDetail(agent.id.value, conversationId = null, agentName = agent.name.takeIf(String::isNotBlank)))
        }
    }
    ConversationInspector(viewModel, uiState)
}

/** Whether the list is docked beside a detail pane right now (read when a row opens). */
private class ConversationsLayout {
    var wide = false
}

/** The view model's state and calls, as the shared page's state and actions. */
private class ConversationsBinding(
    private val viewModel: ConversationsViewModel,
    private val uiState: ConversationsUiState,
    private val open: (ConversationsDetail) -> Unit,
) {
    private fun display(id: String): ConversationDisplay? = uiState.conversations.firstOrNull { it.conversation.id.value == id }

    fun pageState(filter: ShellArchiveFilter, openConversationId: String?): ConversationsPageState {
        val state = ConversationsPageMapping.state(
            ConversationsPageInput(
                conversations = uiState.conversations.map { it.conversation.toChatConversationSummary(mapOf(it.conversation.agentId.value to it.agentName)) },
                archiveFilter = filter,
                pinnedIds = uiState.conversations.filter { it.isPinned }.map { it.conversation.id.value }.toSet(),
                workingIds = uiState.conversations.filter { it.isWorking }.map { it.conversation.id.value }.toSet(),
                loading = uiState.isLoading,
                error = uiState.error,
                noAgents = uiState.shouldShowFirstRunOnboarding(),
            ),
            Clock.System.now(),
        )
        return state.copy(
            pinned = state.pinned.map { it.copy(selected = it.id == openConversationId) },
            groups = state.groups.map { group -> group.copy(rows = group.rows.map { it.copy(selected = it.id == openConversationId) }) },
        )
    }

    fun pageActions(onFilter: (ShellArchiveFilter) -> Unit, onCreateAgent: () -> Unit) = ConversationsPageActions(
        conversations = ShellAgentPanelActions(
            onArchiveFilterChange = onFilter,
            onConversationSelected = { id -> display(id)?.let { open(it.toDetail()) } },
            onArchiveConversation = { id, archived -> display(id)?.let { viewModel.setConversationArchived(it, archived) } },
            onDeleteConversation = { id -> display(id)?.let { viewModel.deleteConversation(it.conversation.id) } },
            deleteBehavior = viewModel.deleteBehavior,
            onRenameConversation = { id, title -> display(id)?.let { viewModel.renameConversation(it.conversation.id, it.conversation.agentId, title) } },
            onPinConversation = { id, pinned -> display(id)?.takeIf { it.isPinned != pinned }?.let(viewModel::toggleConversationPinned) },
            onForkConversation = { id, name -> display(id)?.let { fork(it, name) } },
        ),
        onReload = viewModel::loadConversations,
        onCreateAgent = onCreateAgent,
        rowMenu = { model -> listOf(LettaMenuItem("Inspect…") { display(model.id)?.let(viewModel::openConversationAdmin) }) },
    )

    /** Forks, names the fork when asked to, and opens it. */
    private fun fork(source: ConversationDisplay, name: String?) {
        val agentId = source.conversation.agentId
        viewModel.forkConversation(source.conversation.id, agentId) { forkId ->
            name?.takeIf { it.isNotBlank() }?.let { viewModel.renameConversation(forkId, agentId, it) }
            open(ConversationsDetail(agentId.value, forkId.value, source.agentName))
        }
    }
}

private fun ConversationDisplay.toDetail() = ConversationsDetail(
    agentId = conversation.agentId.value,
    conversationId = conversation.id.value,
    agentName = agentName.takeIf { it.isNotBlank() },
)

/** The header's controls and the new-chat action. */
internal data class ConversationsChrome(
    val backendLabel: String? = null,
    val onBackendClick: (() -> Unit)? = null,
    val onAgents: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onNewChat: () -> Unit = {},
)

internal data class ConversationsRoutePage(
    val state: ConversationsPageState,
    val actions: ConversationsPageActions,
    val chrome: ConversationsChrome,
)

/**
 * The route's layout, without a view model: the shell's list-and-detail layout with the shared
 * header, the shared conversations page and the new-chat button in the list pane.
 */
@Composable
internal fun ConversationsRouteContent(
    page: ConversationsRoutePage,
    onWideChange: (Boolean) -> Unit = {},
    detail: @Composable () -> Unit = {},
) {
    ShellListDetail(
        list = { wide ->
            SideEffect { onWideChange(wide) }
            LettaPageScaffold(
                containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
                header = { ConversationsHeader(page.chrome) },
                floatingAction = { NewChatButton(page.chrome.onNewChat) },
            ) { padding ->
                ShellPage(
                    destination = ShellDestination.Chat(),
                    deps = ShellPageDeps(chat = shellConversationsPage(page.state, page.actions)),
                    modifier = Modifier.padding(padding),
                )
            }
        },
        detail = detail,
    )
}

@Composable
private fun ConversationsHeader(chrome: ConversationsChrome) {
    ShellHeader(
        // The backend label under the title opens the switcher, when this host can switch.
        title = HeaderTitle.Text("Conversations", chrome.backendLabel, onSubtitleClick = chrome.onBackendClick),
        controls = HeaderControls(
            actions = listOf(
                HeaderAction.Icon(LettaIcons.People, "Agents", chrome.onAgents),
                HeaderAction.Icon(LettaIcons.Settings, "Settings", chrome.onSettings),
            ),
        ),
    )
}

@Composable
private fun NewChatButton(onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(LettaIcons.Add, contentDescription = null) },
        text = { Text("New chat") },
        modifier = Modifier.padding(LettaDimens.Space.lg).testTag(ConversationsScreenTags.NEW_CHAT),
    )
}

/** New chat: pick the agent it is with (the shared picker), then the chat opens fresh. */
@Composable
private fun NewChatAgentPicker(agents: List<Agent>, onDismiss: () -> Unit, onPick: (Agent) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val items = remember(agents) {
        agents.map { agent -> PickerItem(key = agent.id.value, value = agent, title = agent.name.ifBlank { agent.id.value }, subtitle = agent.model) }
    }
    LettaPicker(
        spec = LettaPickerSpec(sections = listOf(PickerSection(key = "agents", title = null, items = items)), query = query),
        callbacks = PickerCallbacks(onQuery = { query = it }, onPick = { onPick(it.value) }, onDismiss = onDismiss),
        pane = PickerPane(title = "New chat with…", testTag = ConversationsScreenTags.AGENT_PICKER),
    )
}

/** The conversation inspector (details, recompile, cancel runs), opened from a row's "Inspect…". */
@Composable
private fun ConversationInspector(viewModel: ConversationsViewModel, uiState: ConversationsUiState) {
    val display = uiState.selectedConversation ?: return
    ConversationAdminDialog(
        state = ConversationAdminDialogState(
            display = display,
            recompilePreview = uiState.recompilePreview,
            inspectorMessages = uiState.inspectorMessages,
            isInspectorLoading = uiState.isInspectorLoading,
            inspectorError = uiState.inspectorError,
            routes = viewModel.adminRoutes,
            adminActionError = uiState.adminActionError,
        ),
        callbacks = ConversationAdminDialogCallbacks(
            onDismiss = viewModel::closeConversationAdmin,
            onRename = { viewModel.renameConversation(display.conversation.id, display.conversation.agentId, it) },
            onToggleArchived = { viewModel.setConversationArchived(display, it) },
            onFork = { viewModel.forkConversation(display.conversation.id, display.conversation.agentId) { } },
            onCancelRuns = { viewModel.cancelConversationRuns(display) },
            onRecompile = { viewModel.recompileConversation(display) },
            onDelete = { viewModel.deleteConversation(display.conversation.id) },
        ),
    )
}
