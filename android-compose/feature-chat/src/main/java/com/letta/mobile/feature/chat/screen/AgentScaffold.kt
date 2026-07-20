package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.hilt.navigation.compose.hiltViewModel
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.feature.chat.route.ProjectChatStartAction

internal object AgentScaffoldTestTags {
    const val MENU_BUTTON = "agent_scaffold_menu_button"
    const val DRAWER_CONTENT = "agent_scaffold_drawer_content"
    const val CONVERSATION_PICKER_TRIGGER = "agent_scaffold_conversation_picker_trigger"
    const val CONVERSATION_PICKER_SHEET = "agent_scaffold_conversation_picker_sheet"
    const val PROJECT_BUG_FAB = "agent_scaffold_project_bug_fab"
    const val PROJECT_BUG_REPORT_SHEET = "agent_scaffold_project_bug_report_sheet"
    const val CHAT_SCREEN_CONTENT = "agent_scaffold_chat_screen_content"
    const val PROJECT_CONTEXT_CARD = "agent_scaffold_project_context_card"
    const val PROJECT_AGENTS_CARD = "agent_scaffold_project_agents_card"
    const val PROJECT_BRIEF_CARD = "agent_scaffold_project_brief_card"
    const val PROJECT_BUG_SUMMARY_CARD = "agent_scaffold_project_bug_summary_card"
    const val CHAT_SEARCH_FIELD = "agent_scaffold_chat_search_field"
    const val AGENT_PICKER_SEARCH_FIELD = "agent_scaffold_agent_picker_search_field"
    const val DRAWER_EDIT_AGENT = "agent_scaffold_drawer_edit_agent"
    const val MODEL_PICKER_SHEET = "agent_scaffold_model_picker_sheet"
    const val DRAWER_MODEL_CARD = "agent_scaffold_drawer_model_card"
    fun drawerChatMode(mode: String) = "agent_scaffold_drawer_chat_mode_$mode"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AgentScaffold(
    initialProjectStartAction: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: (String) -> Unit,
    onNavigateToArchival: ((String) -> Unit)? = null,
    onNavigateToTools: (() -> Unit)? = null,
    onNavigateToMemory: ((String) -> Unit)? = null,
    onSwitchConversation: ((String, String?, String?) -> Unit)? = null,
    onViewSubagentConversation: ((String, String) -> Unit)? = null,
    onNavigateToAdmin: (() -> Unit)? = null,
    onNavigateToConversationList: (() -> Unit)? = null,
    onNavigateToSchedules: ((String) -> Unit)? = null,
    viewModelKey: String? = null,
) {
    AgentScaffoldContent(
        initialProjectStartAction = initialProjectStartAction,
        navigation = AgentScaffoldNavigationCallbacks(
            onNavigateBack = onNavigateBack,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToArchival = onNavigateToArchival,
            onNavigateToTools = onNavigateToTools,
            onNavigateToMemory = onNavigateToMemory,
            onSwitchConversation = onSwitchConversation,
            onViewSubagentConversation = onViewSubagentConversation,
            onNavigateToAdmin = onNavigateToAdmin,
            onNavigateToConversationList = onNavigateToConversationList,
            onNavigateToSchedules = onNavigateToSchedules,
        ),
        conversationRepository = null,
        viewModel = hiltViewModel(key = viewModelKey),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun AgentScaffoldContent(
    initialProjectStartAction: String? = null,
    navigation: AgentScaffoldNavigationCallbacks,
    conversationRepository: IConversationRepository? = null,
    viewModel: AdminChatViewModel,
) {
    var showBugReportSheet by rememberSaveable {
        mutableStateOf(initialProjectStartAction == ProjectChatStartAction.BUG_REPORT)
    }
    var showAgentSwitcher by remember { mutableStateOf(false) }
    var isChatSearchExpanded by rememberSaveable { mutableStateOf(false) }
    val chatSearchFocusRequester = remember { FocusRequester() }
    var showModelPicker by remember { mutableStateOf(false) }
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
                showModelPicker = showModelPicker,
                onShowModelPickerChange = { showModelPicker = it },
            ),
            searchUi = AgentScaffoldSearchUiState(
                isChatSearchExpanded = isChatSearchExpanded,
                onChatSearchExpandedChange = { isChatSearchExpanded = it },
                chatSearchFocusRequester = chatSearchFocusRequester,
            ),
            projectUi = AgentScaffoldProjectUiState(
                isProjectInfoExpanded = isProjectInfoExpanded,
                onProjectInfoExpandedChange = { isProjectInfoExpanded = it },
            ),
            conversationRepository = conversationRepository,
        ),
    )
}
