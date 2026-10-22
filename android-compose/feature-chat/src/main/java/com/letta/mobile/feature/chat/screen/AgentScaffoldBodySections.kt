package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.letta.mobile.feature.chat.R
import com.letta.mobile.data.chat.routing.pickOtherAgentConversation
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.icons.LettaIcons
import kotlinx.coroutines.launch

@Composable
internal fun AgentScaffoldInteractionEffects(state: AgentScaffoldRuntimeState) {
    val params = state.params
    val viewModel = params.viewModel

    LaunchedEffect(viewModel.agentId) {
        runCatching {
            state.drawerConversationRepo.refreshConversationsIfStale(viewModel.agentId, maxAgeMs = 30_000L)
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

/**
 * The page's part of the workspace (letta-mobile-ckmub.2): the conversation it settled on, a draft the
 * shell routed to its composer, and its model card and Chat options in the agent panel while it shows.
 */
@Composable
internal fun AgentScaffoldWorkspaceEffects(state: AgentScaffoldRuntimeState) {
    val workspace = state.params.workspace
    val viewModel = state.params.viewModel
    val conversationId = state.conversationId
    LaunchedEffect(conversationId) { conversationId?.let { workspace.onConversationShown(state.agentIdValue, it) } }
    val draft = workspace.draft.value
    LaunchedEffect(draft) {
        draft ?: return@LaunchedEffect
        viewModel.handleComposerTextChanged(draft)
        workspace.draft.value = null
    }
    val latest by rememberUpdatedState(state)
    val chatOptions = rememberAndroidChatOptionsState(state, workspace.drawer)
    val chatOptionsActions = rememberAndroidChatOptionsActions(state, workspace.drawer)
    val panel = remember(chatOptions, chatOptionsActions) {
        WorkspaceChatPanel(agentCard = { AndroidAgentContextCard(latest) }, chatOptions, chatOptionsActions)
    }
    DisposableEffect(panel) {
        workspace.panel.value = panel
        onDispose { if (workspace.panel.value === panel) workspace.panel.value = null }
    }
}

/**
 * The chat inside the shared shell workspace (letta-mobile-ckmub.2): the workspace draws the header,
 * the agent rail and panel, so the page is the chat and its project tray, edge to edge under them.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun AgentScaffoldChatPane(state: AgentScaffoldRuntimeState) {
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = { AgentScaffoldProjectBugFab(state) },
    ) { paddingValues ->
        // letta-mobile-bccty: thread the agentId -> display-name resolver
        // AND the agent-switch navigation callback down into the chat
        // body. The shared AgentMessageProvenanceLabel reads both via
        // the shell-level CompositionLocal rather than each bubble
        // looking up state directly — keeps the render site
        // (ChatMessageItem) free of state plumbing. switchableAgents is
        // the full agents list the runtime state already exposes (with the
        // current agent prepended for picker UX); tapping the
        // provenance-label link fires the existing chat navigation
        // (AgentChatRoute with the other agent's id + the resolved name),
        // reusing the same path the agent-switcher sheet takes.
        val agentNamesById = remember(state.switchableAgents) {
            state.switchableAgents.associate { it.id.value to it.name }
        }
        // letta-mobile-i9h61.3.4: use drawerConversationRepo (params OR Hilt
        // ConversationPickerViewModel fallback) so the picker always has a
        // live IConversationRepository. Using params.conversationRepository
        // alone silently fell through to null — ChatGraph never passes it.
        val conversationRepo = state.drawerConversationRepo
        val scope = rememberCoroutineScope()
        val switchConversation by androidx.compose.runtime.rememberUpdatedState(state.params.navigation.onSwitchConversation)
        // Remembered: the shared page keys its surface host on it, and a fresh one would reach every row.
        val agentMessages = remember(agentNamesById, conversationRepo, scope) {
            AndroidAgentMessageContext(
                resolveName = { id -> agentNamesById[id] },
                onAgentClick = { id ->
                    scope.launch {
                        val resolvedConversationId = pickOtherAgentConversation(
                            repo = conversationRepo,
                            agentId = com.letta.mobile.data.model.AgentId(id),
                        )
                        switchConversation?.invoke(id, resolvedConversationId, agentNamesById[id])
                    }
                },
            )
        }
        CompositionLocalProvider(LocalAndroidAgentMessageContext provides agentMessages) {
            AgentScaffoldMainContent(state = state, paddingValues = paddingValues)
        }
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

}
