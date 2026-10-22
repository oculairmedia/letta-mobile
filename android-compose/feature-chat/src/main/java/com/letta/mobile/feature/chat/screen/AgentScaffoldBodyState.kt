package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.repository.modelcontrol.ConversationModelSelections
import com.letta.mobile.feature.chat.coordination.ChatProjectBindings
import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.feature.chat.route.toArgs
import com.letta.mobile.feature.chat.route.toViewModelKey
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ProjectChatContext
import com.letta.mobile.ui.theme.ChatBackground
import kotlinx.coroutines.CoroutineScope

internal data class AgentScaffoldRuntimeState(
    val params: AgentScaffoldBodyParams,
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
    val switchableAgents: List<Agent>,
    val favoriteAgentId: String?,
    val pinnedAgentIds: Set<String>,
)

@Composable
internal fun rememberAgentScaffoldRuntimeState(params: AgentScaffoldBodyParams): AgentScaffoldRuntimeState {
    val viewModel = params.viewModel
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val chatBackground by viewModel.chatBackground.collectAsStateWithLifecycle()
    val availableAgents by viewModel.availableAgents.collectAsStateWithLifecycle()
    val favoriteAgentId by viewModel.favoriteAgentId.collectAsStateWithLifecycle()
    val availableModels by viewModel.llmModels.collectAsStateWithLifecycle()
    val activeAgentModel = rememberSelectedConversationModel(viewModel)
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

    return AgentScaffoldRuntimeState(
        params = params,
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
        switchableAgents = switchableAgents,
        favoriteAgentId = favoriteAgentId,
        pinnedAgentIds = pinnedAgentIds,
    )
}

@Composable
private fun rememberSelectedConversationModel(viewModel: AdminChatViewModel): String? {
    val activeAgent by viewModel.activeAgent.collectAsStateWithLifecycle()
    val selections by viewModel.conversationModelSelections.collectAsStateWithLifecycle()
    val conversationId = viewModel.conversationId?.value
    return remember(activeAgent, selections, conversationId) {
        ConversationModelSelections.resolve(
            conversationOverride = conversationId?.let(selections.models::get),
            agentModel = activeAgent?.model,
        )
    }
}

/** The chat's view model store inside the workspace, keyed by its route ([WorkspaceChatStoreOwner]). */
@Composable
internal fun rememberChatStoreOwner(route: AgentChatRoute, drawer: SharedNavDrawerViewModel): WorkspaceChatStoreOwner {
    val parent = LocalViewModelStoreOwner.current as NavBackStackEntry
    val context = LocalContext.current
    val key = route.toViewModelKey()
    return remember(key, parent) { WorkspaceChatStoreOwner(key, drawer, parent, context, route.toArgs()) }
}
