package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellSkillsSection
import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.feature.chat.route.subagentConversation
import com.letta.mobile.feature.chat.route.ProjectChatStartAction
import com.letta.mobile.ui.shell.sidebar.ShellChatOptionsActions
import com.letta.mobile.ui.shell.sidebar.ShellChatOptionsState

internal object AgentScaffoldTestTags {
    const val AGENT_PICKER_SHEET = "agent_scaffold_agent_picker_sheet"
    const val PROJECT_BUG_FAB = "agent_scaffold_project_bug_fab"
    const val PROJECT_BUG_REPORT_SHEET = "agent_scaffold_project_bug_report_sheet"
    const val CHAT_SCREEN_CONTENT = "agent_scaffold_chat_screen_content"
    const val PROJECT_CONTEXT_CARD = "agent_scaffold_project_context_card"
    const val PROJECT_AGENTS_CARD = "agent_scaffold_project_agents_card"
    const val PROJECT_BRIEF_CARD = "agent_scaffold_project_brief_card"
    const val PROJECT_BUG_SUMMARY_CARD = "agent_scaffold_project_bug_summary_card"
    const val AGENT_PICKER_SEARCH_FIELD = "agent_scaffold_agent_picker_search_field"
}

/**
 * What the shared shell workspace hands Android's chat page (letta-mobile-ckmub.2): the workspace draws the
 * header, rail and agent panel; the page leaves through these verbs, reports the conversation it settles
 * on (a null conversation is a new chat), puts a [draft] in its composer and fills its part of [panel].
 */
class WorkspaceChatHost(
    val open: (ShellDestination) -> Unit,
    val openConversation: (agentId: String, conversationId: String?) -> Unit,
    val openAgentPane: () -> Unit,
    val openCanvas: (agentId: String, conversationId: String?, shareRecipient: String) -> Unit,
    val onConversationShown: (agentId: String, conversationId: String) -> Unit,
    /** Text the shell routed to the composer; the page takes it (sets it back to null). */
    val draft: MutableState<String?>,
    /** `/search`: the workspace's search palette. */
    val openSearch: () -> Unit,
    /** The workspace's drawer binding, which the chat's display options and `/` commands read. */
    val drawer: SharedNavDrawerViewModel,
    val panel: MutableState<WorkspaceChatPanel?>,
)

/** The open chat's part of the agent panel: its model-and-context card and its Chat options. */
class WorkspaceChatPanel(
    val agentCard: @Composable () -> Unit,
    val chatOptions: ShellChatOptionsState,
    val chatOptionsActions: ShellChatOptionsActions,
)

/** Android's chat page in the shared shell workspace: the agent chat for [route], without its own chrome. */
@Composable
fun WorkspaceAgentChat(route: AgentChatRoute, host: WorkspaceChatHost) {
    val navigation = AgentScaffoldNavigationCallbacks(
        onNavigateBack = host.openAgentPane,
        onNavigateToSettings = { agentId -> host.open(ShellDestination.AgentEditor(agentId)) },
        onNavigateToTools = { host.open(ShellDestination.Skills(section = ShellSkillsSection.Tools)) },
        onNavigateToMemory = { agentId -> host.open(ShellDestination.Memory(agentId)) },
        onSwitchConversation = { agentId, conversation, _ -> host.openConversation(agentId, conversation?.takeIf { it.isNotBlank() }) },
        onViewSubagentConversation = { agent, conversation -> host.openConversation(agent, subagentConversation(agent, conversation)) },
        onNavigateToAdmin = { host.open(ShellDestination.Home) },
        onNavigateToProviders = { host.open(ShellDestination.Providers) },
        onNavigateToConversationList = host.openAgentPane,
        onNavigateToSchedules = { agentId -> host.open(ShellDestination.Schedules(agentId)) },
        onNavigateToProjects = { host.open(ShellDestination.Projects) },
        onNavigateToCanvas = host.openCanvas,
        onNavigateToAppSettings = { host.open(ShellDestination.Settings()) },
        onOpenCanvas = { canvasId -> host.open(ShellDestination.Canvas(canvasId = canvasId)) },
        onNavigateToChannels = { host.open(ShellDestination.Channels) },
        onNavigateToSkills = { agentId -> host.open(ShellDestination.Skills(agentId)) },
    )
    CompositionLocalProvider(LocalSharedNavDrawer provides host.drawer) {
        AgentScaffoldContent(
            initialProjectStartAction = route.projectStartAction,
            navigation = navigation,
            viewModel = hiltViewModel(rememberChatStoreOwner(route, host.drawer)),
            workspace = host,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun AgentScaffoldContent(
    initialProjectStartAction: String? = null,
    navigation: AgentScaffoldNavigationCallbacks,
    conversationRepository: IConversationRepository? = null,
    viewModel: AdminChatViewModel,
    workspace: WorkspaceChatHost,
) {
    var showBugReportSheet by rememberSaveable {
        mutableStateOf(initialProjectStartAction == ProjectChatStartAction.BUG_REPORT)
    }
    var showAgentSwitcher by remember { mutableStateOf(false) }
    val projectContext = viewModel.projectContext
    var isProjectInfoExpanded by rememberSaveable(projectContext?.identifier) { mutableStateOf(false) }
    var chatMode by rememberSaveable { mutableStateOf(viewModel.initialChatMode ?: "simple") }

    AgentScaffoldBody(
        params = AgentScaffoldBodyParams(
            navigation = navigation,
            viewModel = viewModel,
            chatMode = chatMode,
            onChatModeChange = { chatMode = it },
            sheetVisibility = AgentScaffoldSheetVisibility(
                showBugReportSheet = showBugReportSheet,
                onShowBugReportSheetChange = { showBugReportSheet = it },
                showAgentSwitcher = showAgentSwitcher,
                onShowAgentSwitcherChange = { showAgentSwitcher = it },
            ),
            projectUi = AgentScaffoldProjectUiState(
                isProjectInfoExpanded = isProjectInfoExpanded,
                onProjectInfoExpandedChange = { isProjectInfoExpanded = it },
            ),
            conversationRepository = conversationRepository,
            modelPicker = remember(viewModel) { viewModel.modelPicker(navigation.onNavigateToProviders) },
            workspace = workspace,
        ),
    )
}
