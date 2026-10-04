package com.letta.mobile.desktop.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.letta.mobile.data.context.ContextReadingInputs
import com.letta.mobile.data.context.ContextTokenReadings
import com.letta.mobile.data.context.ContextWindowUsageKey
import com.letta.mobile.data.context.ContextWindowUsagePolicy
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.context.contextUsageStates
import com.letta.mobile.data.context.contextWindowTokensOf
import com.letta.mobile.data.context.readingFor
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.LlmModel

/** The conversation the composer's context chip describes. */
internal data class DesktopContextFocus(
    val agentId: String?,
    val conversationId: String?,
    /** False while a turn is in flight. */
    val settled: Boolean,
)

/**
 * letta-mobile-r2zo8: the focused conversation's context reading — the latest
 * `usage_statistics.context_tokens` the session's transport streamed for it, against the
 * focused agent's model window. Desktop only binds inputs; when to take a reading, what to
 * keep and what to drop on a focus change live in the shared [contextUsageStates] fold, the
 * same one Android runs.
 */
@Composable
internal fun rememberFocusedContextUsage(
    focus: DesktopContextFocus,
    readings: ContextTokenReadings,
    agents: List<Agent>,
    models: List<LlmModel>,
): ContextWindowUsageState {
    val byConversation by readings.readings.collectAsState()
    val agent = agents.firstOrNull { it.id.value == focus.agentId }
    val inputs = ContextReadingInputs(
        key = ContextWindowUsageKey(focus.agentId, focus.conversationId, focus.settled),
        contextTokens = byConversation.readingFor(focus.agentId, focus.conversationId),
        windowTokens = contextWindowTokensOf(agent, models),
    )
    val latest by rememberUpdatedState(inputs)
    val states = remember { snapshotFlow { latest }.contextUsageStates() }
    val state by states.collectAsState(ContextWindowUsagePolicy.cleared())
    return state
}
