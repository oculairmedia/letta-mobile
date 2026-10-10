package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.compaction.AdminRpcCompactionRepository
import com.letta.mobile.data.compaction.CompactionController
import com.letta.mobile.data.context.ContextBreakdownLoader
import com.letta.mobile.data.context.contextWindowTokensOf
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ModelPickerSource
import com.letta.mobile.data.session.SessionGraph
import com.letta.mobile.feature.chat.coordination.EffortSelection
import com.letta.mobile.ui.context.AgentContextCardActions
import com.letta.mobile.ui.context.AgentContextCardBinding
import com.letta.mobile.ui.context.AgentContextCardDeps
import com.letta.mobile.ui.context.AgentContextCardHost
import com.letta.mobile.ui.context.AgentContextFocus
import com.letta.mobile.ui.context.AgentContextPresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * letta-mobile-3io8k: the session pieces the drawer's context card reads — the streamed readings,
 * the breakdown loader and compaction — rebuilt with each session graph so a backend switch never
 * shows the previous backend's numbers. Compaction goes through the host's `conversation.compact`;
 * a session with no admin_rpc answers Unsupported and the button hides.
 */
internal data class ContextCardSession(
    val readings: com.letta.mobile.data.context.ContextTokenReadings,
    val breakdown: ContextBreakdownLoader,
    val compaction: CompactionController,
)

internal fun SessionGraph.toContextCardSession(): ContextCardSession = ContextCardSession(
    readings = contextTokenReadings,
    breakdown = ContextBreakdownLoader(agentRepository),
    compaction = CompactionController(
        AdminRpcCompactionRepository(AdminRpcInvoker.overTransport { channelTransport }),
        contextTokenReadings,
    ),
)

internal fun contextCardSessions(graphs: StateFlow<SessionGraph>, scope: CoroutineScope): StateFlow<ContextCardSession> =
    graphs.map { it.toContextCardSession() }
        .stateIn(scope, SharingStarted.WhileSubscribed(SESSION_STOP_TIMEOUT_MS), graphs.value.toContextCardSession())

private const val SESSION_STOP_TIMEOUT_MS = 5_000L

/** The card under the agent's name in the hamburger drawer; its sheet is a bottom sheet. */
@Composable
internal fun AndroidAgentContextCard(state: AgentScaffoldRuntimeState) {
    val viewModel = state.params.viewModel
    val session by viewModel.contextCardSession.collectAsStateWithLifecycle()
    val agent by viewModel.activeAgent.collectAsStateWithLifecycle()
    val selections by viewModel.conversationModelSelections.collectAsStateWithLifecycle()
    val pickerSource = remember(viewModel) {
        val fallback = ModelPickerSource.of(viewModel.llmModels) { viewModel.refreshModels() }
        viewModel.modelPickerSource()?.let { ModelPickerSource.withFallback(it, fallback) } ?: fallback
    }
    val deps = remember(session, pickerSource) {
        AgentContextCardDeps(session.readings, session.breakdown, session.compaction, pickerSource)
    }
    val binding = remember(deps, viewModel) {
        AgentContextCardBinding(
            deps = deps,
            actions = AgentContextCardActions(
                onModelSelected = { entry -> viewModel.updateActiveAgentModel(entry.handle.value) },
                onEffortSelected = { entry, effort -> viewModel.updateActiveAgentModel(entry.handle.value, EffortSelection.Set(effort)) },
            ),
            presentation = AgentContextPresentation.Sheet,
        )
    }
    val current = state.availableModels.firstOrNull { it.handle == state.activeAgentModel || it.id == state.activeAgentModel }
    AgentContextCardHost(
        binding = binding,
        focus = AgentContextFocus(
            agentId = state.agentIdValue,
            conversationId = state.conversationId,
            modelLabel = current?.displayName ?: state.activeAgentModel,
            modelValue = state.activeAgentModel,
            effort = current?.reasoningEffort,
            windowTokens = contextWindowTokensOf(agent, state.availableModels, state.conversationId?.let(selections::get)),
            turnRunning = state.uiState.isStreaming || state.uiState.isAgentTyping,
        ),
    )
}
