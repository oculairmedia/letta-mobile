package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.context.limit.AgentContextRoutes
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.ui.context.AgentContextCardBinding
import com.letta.mobile.ui.context.AgentContextSession
import com.letta.mobile.ui.context.ContextConversationFocus
import com.letta.mobile.ui.context.ContextWindowSources
import com.letta.mobile.ui.context.FocusedAgentContextCard
import com.letta.mobile.ui.notify.LocalAttentionPrefs

/**
 * The shared model-and-context card under the agent's name in the hamburger
 * drawer. Android binds the session graph's readings and breakdown, compaction and the context limit
 * over its `admin_rpc` (the embedded runtime answers Unsupported), and the chat's one model picker;
 * a backend switch is a new graph, so the card never shows the previous backend's numbers.
 */
@Composable
internal fun AndroidAgentContextCard(state: AgentScaffoldRuntimeState) {
    val viewModel = state.params.viewModel
    val graph by viewModel.sessionGraph.collectAsStateWithLifecycle()
    val agent by viewModel.activeAgent.collectAsStateWithLifecycle()
    val selections by viewModel.conversationModelSelections.collectAsStateWithLifecycle()
    val attention = LocalAttentionPrefs.current
    val picker = state.params.modelPicker
    val binding = remember(graph, picker, attention) {
        val routes = AgentContextRoutes(AdminRpcInvoker.overTransport { graph.channelTransport }) { null }
        AgentContextCardBinding(AgentContextSession(graph.contextTokenReadings, graph.contextBreakdown, routes).cardDeps(attention), picker)
    }
    val focus = ContextConversationFocus(
        agentId = state.agentIdValue,
        conversationId = state.conversationId,
        turnRunning = state.uiState.isStreaming || state.uiState.isAgentTyping,
        picksTargetConversation = viewModel.modelPicksTargetConversation(),
    )
    val window = ContextWindowSources(listOfNotNull(agent), state.availableModels, selections)
    FocusedAgentContextCard(binding, focus, window, modelLabel = null)
}
