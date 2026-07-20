package com.letta.mobile.feature.chat.screen

import androidx.compose.material3.DrawerState
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.feature.chat.R
import com.letta.mobile.feature.chat.coordination.ChatProjectBindings
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ProjectChatContext
import com.letta.mobile.ui.theme.ChatBackground
import kotlinx.coroutines.CoroutineScope

internal data class AgentScaffoldRuntimeState(
    val params: AgentScaffoldBodyParams,
    val drawerState: DrawerState,
    val scope: CoroutineScope,
    val haptic: HapticFeedback,
    val view: android.view.View,
    val uiState: ChatUiState,
    val chatBackground: ChatBackground,
    val availableModels: List<LlmModel>,
    val activeAgentModel: String?,
    val projectBindings: ChatProjectBindings,
    val drawerConversationRepo: IConversationRepository,
    val drawerConversations: List<Conversation>,
    val agentName: String,
    val agentIdValue: String,
    val conversationId: String?,
    val projectContext: ProjectChatContext?,
    val screenTitle: String,
    val currentAgentIsFavorite: Boolean,
    val currentAgentIsPinned: Boolean,
    val switchableAgents: List<Agent>,
    val scrollBehavior: TopAppBarScrollBehavior,
    val favoriteAgentId: String?,
    val pinnedAgentIds: Set<String>,
    val activeBackendLabel: String?,
)

@Composable
internal fun rememberAgentScaffoldRuntimeState(params: AgentScaffoldBodyParams): AgentScaffoldRuntimeState {
    val viewModel = params.viewModel
    val drawerState = rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val chatBackground by viewModel.chatBackground.collectAsStateWithLifecycle()
    val availableAgents by viewModel.availableAgents.collectAsStateWithLifecycle()
    val favoriteAgentId by viewModel.favoriteAgentId.collectAsStateWithLifecycle()
    val activeBackendLabel by viewModel.activeBackendLabel.collectAsStateWithLifecycle()
    val availableModels by viewModel.llmModels.collectAsStateWithLifecycle()
    val activeAgent by viewModel.activeAgent.collectAsStateWithLifecycle()
    val activeAgentModel = remember(activeAgent) { activeAgent?.model }
    val projectBindings = viewModel.projectBindings
    val pinnedAgentIds by viewModel.pinnedAgentIds.collectAsStateWithLifecycle()
    val drawerConversationRepo = params.conversationRepository
        ?: hiltViewModel<ConversationPickerViewModel>().conversationRepository
    val drawerConversations by drawerConversationRepo.getConversations(viewModel.agentId)
        .collectAsStateWithLifecycle(emptyList())

    val agentName = uiState.agentName
    val agentId = viewModel.agentId
    val agentIdValue = agentId.value
    val conversationId = viewModel.conversationId?.value
    val projectContext = viewModel.projectContext
    val screenTitle = projectContext?.name ?: agentName.ifBlank { stringResource(R.string.screen_chat_title) }
    val switchableAgents = remember(availableAgents, agentId, agentName, favoriteAgentId, pinnedAgentIds) {
        val agents = if (availableAgents.any { it.id == agentId }) {
            availableAgents
        } else {
            listOf(Agent(id = agentId, name = agentName.ifBlank { "Agent" })) + availableAgents
        }
        sortAgentsForPicker(
            agents = agents,
            favoriteAgentId = favoriteAgentId,
            pinnedAgentIds = pinnedAgentIds,
        )
    }
    val scrollBehavior = androidx.compose.material3.TopAppBarDefaults.pinnedScrollBehavior()

    return AgentScaffoldRuntimeState(
        params = params,
        drawerState = drawerState,
        scope = scope,
        haptic = haptic,
        view = view,
        uiState = uiState,
        chatBackground = chatBackground,
        availableModels = availableModels,
        activeAgentModel = activeAgentModel,
        projectBindings = projectBindings,
        drawerConversationRepo = drawerConversationRepo,
        drawerConversations = drawerConversations,
        agentName = agentName,
        agentIdValue = agentIdValue,
        conversationId = conversationId,
        projectContext = projectContext,
        screenTitle = screenTitle,
        currentAgentIsFavorite = agentIdValue == favoriteAgentId,
        currentAgentIsPinned = agentIdValue in pinnedAgentIds,
        switchableAgents = switchableAgents,
        scrollBehavior = scrollBehavior,
        favoriteAgentId = favoriteAgentId,
        pinnedAgentIds = pinnedAgentIds,
        activeBackendLabel = activeBackendLabel,
    )
}
