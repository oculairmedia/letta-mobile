package com.letta.mobile.feature.chat.screen

import com.letta.mobile.data.repository.api.IConversationRepository
internal data class AgentScaffoldNavigationCallbacks(
    val onNavigateBack: () -> Unit,
    val onNavigateToSettings: (String) -> Unit,
    val onNavigateToArchival: ((String) -> Unit)? = null,
    val onNavigateToTools: (() -> Unit)? = null,
    val onNavigateToMemory: ((String) -> Unit)? = null,
    val onSwitchConversation: ((String, String?, String?) -> Unit)? = null,
    val onViewSubagentConversation: ((String, String) -> Unit)? = null,
    val onNavigateToAdmin: (() -> Unit)? = null,
    val onNavigateToConversationList: (() -> Unit)? = null,
    val onNavigateToSchedules: ((String) -> Unit)? = null,
)

internal data class AgentScaffoldSheetVisibility(
    val showBugReportSheet: Boolean,
    val onShowBugReportSheetChange: (Boolean) -> Unit,
    val showAgentSwitcher: Boolean,
    val onShowAgentSwitcherChange: (Boolean) -> Unit,
    val showModelPicker: Boolean,
    val onShowModelPickerChange: (Boolean) -> Unit,
)

internal data class AgentScaffoldSearchUiState(
    val isChatSearchExpanded: Boolean,
    val onChatSearchExpandedChange: (Boolean) -> Unit,
    val chatSearchFocusRequester: androidx.compose.ui.focus.FocusRequester,
)

internal data class AgentScaffoldProjectUiState(
    val isProjectInfoExpanded: Boolean,
    val onProjectInfoExpandedChange: (Boolean) -> Unit,
)

internal data class AgentScaffoldBodyParams(
    val navigation: AgentScaffoldNavigationCallbacks,
    val viewModel: AdminChatViewModel,
    val chatMode: String,
    val onChatModeChange: (String) -> Unit,
    val sheetVisibility: AgentScaffoldSheetVisibility,
    val searchUi: AgentScaffoldSearchUiState,
    val projectUi: AgentScaffoldProjectUiState,
    val conversationRepository: IConversationRepository?,
)
