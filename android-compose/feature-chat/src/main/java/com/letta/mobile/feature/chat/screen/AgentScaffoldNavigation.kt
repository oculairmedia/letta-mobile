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
    /** letta-mobile-w4q4p.6.1: the Providers screen, from the Models sheet's "Add provider…". */
    val onNavigateToProviders: (() -> Unit)? = null,
    val onNavigateToConversationList: (() -> Unit)? = null,
    val onNavigateToSchedules: ((String) -> Unit)? = null,
    val onNavigateToProjects: (() -> Unit)? = null,
    val onNavigateToCanvas: ((agentId: String, conversationId: String?, shareRecipient: String) -> Unit)? = null,
    /** letta-mobile-c3np7.5.5: the app settings page, from the shared drawer's Settings row. */
    val onNavigateToAppSettings: (() -> Unit)? = null,
    /** letta-mobile-c3np7.5.5: opens one canvas by id, from the shared drawer's Canvases list. */
    val onOpenCanvas: ((canvasId: String) -> Unit)? = null,
    /** letta-mobile-c3np7.5.7: the shared Channels page, from the shared drawer's Channels row. */
    val onNavigateToChannels: (() -> Unit)? = null,
)

/** What the chat's model picker sheet reports back (letta-mobile-w4q4p.6.1). */
internal data class ModelPickerSheetCallbacks(
    val onDismiss: () -> Unit,
    /** The picked model's handle. */
    val onModelSelected: (String) -> Unit,
    /** Re-reads the chat's own model list (the picker's fallback when the host has no admin catalog). */
    val onRefresh: () -> Unit,
    /** Opens the Models sheet; null hides "Edit Models…". */
    val onEditModels: (() -> Unit)? = null,
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
