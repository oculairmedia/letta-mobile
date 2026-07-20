package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun AgentScaffoldMainContent(
    state: AgentScaffoldRuntimeState,
    paddingValues: PaddingValues,
) {
    val topPadding = paddingValues.calculateTopPadding()
    Column(modifier = Modifier.fillMaxSize()) {
        AgentScaffoldProjectInfoSection(state, topPadding)
        AgentScaffoldChatSection(state, paddingValues, topPadding)
    }
}

@Composable
private fun AgentScaffoldProjectInfoSection(
    state: AgentScaffoldRuntimeState,
    topPadding: androidx.compose.ui.unit.Dp,
) {
    val project = state.projectContext ?: return
    val params = state.params
    ProjectInfoTray(
        project = project,
        agentsState = state.uiState.projectAgents,
        brief = state.uiState.projectBrief,
        bugReports = state.uiState.bugReports,
        expanded = params.projectUi.isProjectInfoExpanded,
        onExpandedChange = params.projectUi.onProjectInfoExpandedChange,
        onRetryAgents = state.projectBindings::loadProjectAgents,
        onRetryBrief = state.projectBindings::loadProjectBrief,
        onSaveBriefSection = state.projectBindings::saveProjectBriefSection,
        onCreateBugReport = { params.sheetVisibility.onShowBugReportSheetChange(true) },
        modifier = Modifier
            .padding(top = topPadding)
            .testTag(AgentScaffoldTestTags.PROJECT_CONTEXT_CARD),
    )
}

@Composable
private fun ColumnScope.AgentScaffoldChatSection(
    state: AgentScaffoldRuntimeState,
    paddingValues: PaddingValues,
    topPadding: androidx.compose.ui.unit.Dp,
) {
    val chatModifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .testTag(AgentScaffoldTestTags.CHAT_SCREEN_CONTENT)
    val contentTopPadding = if (state.projectContext == null) topPadding else 0.dp

    if (state.uiState.isSearchActive) {
        AgentScaffoldSearchResultsPane(
            state = state,
            chatModifier = chatModifier,
            contentTopPadding = contentTopPadding,
            bottomPadding = paddingValues.calculateBottomPadding(),
        )
    } else {
        AgentScaffoldChatScreenPane(
            state = state,
            chatModifier = chatModifier,
            contentTopPadding = contentTopPadding,
        )
    }
}

@Composable
private fun AgentScaffoldSearchResultsPane(
    state: AgentScaffoldRuntimeState,
    chatModifier: Modifier,
    contentTopPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    val params = state.params
    ChatSearchResultsContent(
        searchQuery = state.uiState.searchQuery,
        results = state.uiState.searchResults,
        isSearching = state.uiState.isSearching,
        conversations = state.drawerConversations,
        currentConversationId = state.conversationId,
        onResultClick = { result ->
            params.searchUi.onChatSearchExpandedChange(false)
            params.viewModel.clearChatSearch()
            result.conversationId?.let { targetConversationId ->
                params.navigation.onSwitchConversation?.invoke(
                    state.agentIdValue,
                    targetConversationId,
                    state.agentName.takeIf { it.isNotBlank() },
                )
            }
        },
        modifier = chatModifier.padding(top = contentTopPadding, bottom = bottomPadding),
    )
}

@Composable
private fun AgentScaffoldChatScreenPane(
    state: AgentScaffoldRuntimeState,
    chatModifier: Modifier,
    contentTopPadding: androidx.compose.ui.unit.Dp,
) {
    val params = state.params
    ChatScreen(
        modifier = chatModifier,
        contentPadding = PaddingValues(top = contentTopPadding, bottom = 0.dp),
        chatBackground = state.chatBackground,
        chatMode = params.chatMode,
        onBugCommand = { params.sheetVisibility.onShowBugReportSheetChange(true) },
        onViewSubagentConversation = params.navigation.onViewSubagentConversation
            ?: params.navigation.onSwitchConversation?.let { switch ->
                { subagentAgentId, subagentConversationId ->
                    switch(subagentAgentId, subagentConversationId, null)
                }
            },
        viewModel = params.viewModel,
    )
}
