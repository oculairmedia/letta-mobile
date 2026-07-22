package com.letta.mobile.feature.chat.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.icons.LettaIcons
import kotlinx.coroutines.launch

@Composable
internal fun AgentScaffoldInteractionEffects(state: AgentScaffoldRuntimeState) {
    val params = state.params
    val viewModel = params.viewModel
    val searchUi = params.searchUi

    LaunchedEffect(viewModel.agentId) {
        runCatching {
            state.drawerConversationRepo.refreshConversationsIfStale(viewModel.agentId, maxAgeMs = 30_000L)
        }
    }

    BackHandler(enabled = state.drawerState.isOpen) {
        state.scope.launch { state.drawerState.close() }
    }

    BackHandler(enabled = !state.drawerState.isOpen && (searchUi.isChatSearchExpanded || state.uiState.isSearchActive)) {
        searchUi.onChatSearchExpandedChange(false)
        viewModel.clearChatSearch()
    }

    LaunchedEffect(searchUi.isChatSearchExpanded) {
        if (searchUi.isChatSearchExpanded) {
            searchUi.chatSearchFocusRequester.requestFocus()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> viewModel.onScreenPaused()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> viewModel.onScreenResumed()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentScaffoldDrawerScaffold(state: AgentScaffoldRuntimeState) {
    ModalNavigationDrawer(
        drawerState = state.drawerState,
        drawerContent = { AgentScaffoldDrawerSheet(state) },
    ) {
        AgentScaffoldChromeScaffold(state)
    }
}

@Composable
private fun AgentScaffoldDrawerSheet(state: AgentScaffoldRuntimeState) {
    val params = state.params
    ModalDrawerSheet {
        DrawerContent(
            agentName = state.agentName,
            agentId = state.agentIdValue,
            activeBackendLabel = state.activeBackendLabel,
            currentModel = state.activeAgentModel,
            contextWindow = state.uiState.contextWindow,
            chatMode = params.chatMode,
            onChatModeSelected = params.onChatModeChange,
            onModelTap = { params.sheetVisibility.onShowModelPickerChange(true) },
            conversations = state.drawerConversations,
            currentConversationId = state.conversationId,
            onNewConversation = {
                closeDrawerAndRun(state) {
                    params.navigation.onSwitchConversation?.invoke(
                        state.agentIdValue,
                        null,
                        state.agentName.takeIf { it.isNotBlank() },
                    )
                }
            },
            onConversationSelected = { selectedConversationId ->
                closeDrawerAndRun(state) {
                    params.navigation.onSwitchConversation?.invoke(
                        state.agentIdValue,
                        selectedConversationId,
                        state.agentName.takeIf { it.isNotBlank() },
                    )
                }
            },
            onEditAgent = {
                closeDrawerAndRun(state) { params.navigation.onNavigateToSettings(state.agentIdValue) }
            },
            onResetMessages = {
                closeDrawerAndRun(state) { params.viewModel.resetMessages() }
            },
            onRefreshContextWindow = state.projectBindings::refreshContextWindow,
            onNavigateToAdmin = {
                closeDrawerAndRun(state) { params.navigation.onNavigateToAdmin?.invoke() }
            },
            onNavigateToConversations = {
                closeDrawerAndRun(state) { params.navigation.onNavigateToConversationList?.invoke() }
            },
            onNavigateToMemory = {
                closeDrawerAndRun(state) { params.navigation.onNavigateToMemory?.invoke(state.agentIdValue) }
            },
            onNavigateToSchedules = {
                closeDrawerAndRun(state) { params.navigation.onNavigateToSchedules?.invoke(state.agentIdValue) }
            },
            onClose = { state.scope.launch { state.drawerState.close() } },
            modifier = Modifier.testTag(AgentScaffoldTestTags.DRAWER_CONTENT),
        )
    }
}

private fun closeDrawerAndRun(state: AgentScaffoldRuntimeState, action: () -> Unit) {
    state.scope.launch { state.drawerState.close() }
    action()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AgentScaffoldChromeScaffold(state: AgentScaffoldRuntimeState) {
    Scaffold(
        modifier = Modifier.nestedScroll(state.scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { AgentScaffoldTopBar(state) },
        floatingActionButton = { AgentScaffoldProjectBugFab(state) },
    ) { paddingValues ->
        AgentScaffoldMainContent(
            state = state,
            paddingValues = paddingValues,
        )
    }
}

@Composable
private fun AgentScaffoldProjectBugFab(state: AgentScaffoldRuntimeState) {
    if (state.projectContext == null) return
    FloatingActionButton(
        onClick = {
            HapticEffects.contextClick(state.haptic, state.view)
            state.params.sheetVisibility.onShowBugReportSheetChange(true)
        },
        modifier = Modifier.testTag(AgentScaffoldTestTags.PROJECT_BUG_FAB),
    ) {
        Icon(LettaIcons.Error, contentDescription = stringResource(R.string.screen_project_bug_report_open))
    }
}

@Composable
internal fun AgentScaffoldSheets(state: AgentScaffoldRuntimeState) {
    val params = state.params
    val sheetVisibility = params.sheetVisibility
    if (sheetVisibility.showAgentSwitcher) {
        AgentPickerSheet(
            agents = state.switchableAgents,
            currentAgentId = state.agentIdValue,
            favoriteAgentId = state.favoriteAgentId,
            pinnedAgentIds = state.pinnedAgentIds,
            onDismiss = { sheetVisibility.onShowAgentSwitcherChange(false) },
            onTogglePinned = { selectedAgent -> params.viewModel.toggleAgentPinned(selectedAgent.id.value) },
            onAgentSelected = { selectedAgent ->
                sheetVisibility.onShowAgentSwitcherChange(false)
                if (selectedAgent.id.value != state.agentIdValue) {
                    params.navigation.onSwitchConversation?.invoke(
                        selectedAgent.id.value,
                        null,
                        selectedAgent.name.takeIf { it.isNotBlank() },
                    )
                }
            },
        )
    }

    if (sheetVisibility.showBugReportSheet && state.projectContext != null) {
        ProjectBugReportSheet(
            state = state.uiState.bugReports,
            onDismiss = { sheetVisibility.onShowBugReportSheetChange(false) },
            onSubmit = {
                state.projectBindings.submitStructuredBugReport(it)
                sheetVisibility.onShowBugReportSheetChange(false)
            },
        )
    }

    if (sheetVisibility.showModelPicker) {
        ModelPickerSheet(
            models = state.availableModels,
            currentModel = state.activeAgentModel,
            onDismiss = { sheetVisibility.onShowModelPickerChange(false) },
            onModelSelected = { handle ->
                params.viewModel.updateActiveAgentModel(handle)
                sheetVisibility.onShowModelPickerChange(false)
            },
            onRefresh = params.viewModel::refreshModels,
        )
    }
}
