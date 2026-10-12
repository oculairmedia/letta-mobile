package com.letta.mobile.feature.chat.screen.shared

import com.letta.mobile.data.context.ContextReadingInputs
import com.letta.mobile.data.context.ContextReadingKey
import com.letta.mobile.data.context.ContextWindowUsageKey
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.context.contextUsageStates
import com.letta.mobile.data.context.readingFor
import com.letta.mobile.data.context.windowTokensFor
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChoices
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * letta-mobile-0ofhc: everything Android's context chip is derived from. All of it is already
 * held by the chat ViewModel; nothing here triggers a read.
 */
internal data class AdminChatContextSources(
    val agentId: String,
    val ui: Flow<ChatUiState>,
    /** The session's latest `context_tokens` per conversation (letta-mobile-r2zo8). */
    val readings: Flow<Map<ContextReadingKey, Int>>,
    val agent: Flow<Agent?>,
    val models: Flow<List<LlmModel>>,
    /** Per-conversation models and windows (host override or this session's switch). */
    val modelSelections: Flow<ConversationModelChoices>,
)

/** What the model window is drawn from at one instant. */
private data class WindowInputs(
    val agent: Agent?,
    val models: List<LlmModel>,
    val selections: ConversationModelChoices,
)

/**
 * The chip state for the conversation on screen: loaded as soon as the conversation has a
 * reading and its turn has settled, through the shared [contextUsageStates] fold — the same
 * rules desktop runs — rather than on a drawer tap.
 */
internal fun AdminChatContextSources.contextUsage(): Flow<ContextWindowUsageState> =
    combine(ui, readings, combine(agent, models, modelSelections, ::WindowInputs)) { state, byConversation, window ->
        inputsFor(state, byConversation, window)
    }.contextUsageStates()

private fun AdminChatContextSources.inputsFor(
    state: ChatUiState,
    byConversation: Map<ContextReadingKey, Int>,
    window: WindowInputs,
): ContextReadingInputs {
    val conversationId = (state.conversationState as? ConversationState.Ready)?.conversationId
    return ContextReadingInputs(
        key = ContextWindowUsageKey(
            agentId = agentId.takeIf { it.isNotBlank() },
            conversationId = conversationId,
            settled = !state.isStreaming && !state.isAgentTyping,
        ),
        contextTokens = byConversation.readingFor(agentId, conversationId),
        windowTokens = window.selections.windowTokensFor(conversationId, window.agent, window.models),
    )
}
