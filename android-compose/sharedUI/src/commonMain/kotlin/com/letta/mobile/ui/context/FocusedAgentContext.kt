package com.letta.mobile.ui.context

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.letta.mobile.data.compaction.CompactionController
import com.letta.mobile.data.context.ContextBreakdownLoader
import com.letta.mobile.data.context.ContextReadingInputs
import com.letta.mobile.data.context.ContextTokenReadings
import com.letta.mobile.data.context.ContextWindowUsageKey
import com.letta.mobile.data.context.ContextWindowUsagePolicy
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.context.ConversationModelView
import com.letta.mobile.data.context.contextUsageStates
import com.letta.mobile.data.context.limit.AgentContextRoutes
import com.letta.mobile.data.context.limit.ContextLimitController
import com.letta.mobile.data.context.readingFor
import com.letta.mobile.data.context.windowTokensFor
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.notify.AttentionPrefsRepository
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChoices
import kotlinx.coroutines.flow.StateFlow

/*
 * The composer's context meter and the panel's agent
 * context card for the conversation a shell has open. A host binds its session pieces; desktop and
 * the web run these same composables.
 */

/** The conversation the composer meter and the agent context card describe. */
@Immutable
data class ContextConversationFocus(
    val agentId: String?,
    val conversationId: String?,
    /** A turn is in flight: the meter holds its reading, the card fetches no breakdown. */
    val turnRunning: Boolean,
    /** False where this host's model pick always changes the agent (see [AgentContextFocus.picksTargetConversation]). */
    val picksTargetConversation: Boolean = true,
)

/** What the model window is sized from: all cached, no extra call. */
@Immutable
data class ContextWindowSources(
    val agents: List<Agent>,
    val models: List<LlmModel>,
    /** Each conversation's own model and window (host override or this session's switch). */
    val modelSelections: ConversationModelChoices,
) {
    /** The roster's entry for [focus]'s agent. */
    fun agentOf(focus: ContextConversationFocus): Agent? = agents.firstOrNull { it.id.value == focus.agentId }
}

/**
 * The [agents], the session's [models] and the chat's per-conversation [selections]: the windows of a
 * host whose chat lists the session's models (desktop).
 */
@Composable
fun rememberContextWindowSources(
    agents: List<Agent>,
    models: StateFlow<List<LlmModel>>,
    selections: StateFlow<ConversationModelChoices>,
): ContextWindowSources {
    val list by models.collectAsState()
    val choices by selections.collectAsState()
    return ContextWindowSources(agents, list, choices)
}

/**
 * The focused conversation's context reading, the latest
 * `usage_statistics.context_tokens` the session's transport streamed for it, against its model
 * window, until [breakdown] (the session's one owner, which the card reads too) has the
 * conversation's real breakdown; it is fetched in the background on open and after each settled
 * turn. When to take a reading, what to keep and what to drop on a
 * focus change live in the shared [contextUsageStates] fold, the same one Android runs.
 */
@Composable
fun rememberFocusedContextUsage(
    focus: ContextConversationFocus,
    readings: ContextTokenReadings,
    breakdown: ContextBreakdownLoader?,
    window: ContextWindowSources,
): ContextWindowUsageState {
    val byConversation by readings.readings.collectAsState()
    // Same resolution as Android: the conversation's own window and model first, then the agent's.
    val inputs = ContextReadingInputs(
        key = ContextWindowUsageKey(focus.agentId, focus.conversationId, !focus.turnRunning),
        contextTokens = byConversation.readingFor(focus.agentId, focus.conversationId),
        windowTokens = window.modelSelections.windowTokensFor(focus.conversationId, window.agentOf(focus), window.models),
    )
    val latest by rememberUpdatedState(inputs)
    val states = remember(breakdown) { snapshotFlow { latest }.contextUsageStates(breakdown) }
    val state by states.collectAsState(ContextWindowUsagePolicy.cleared())
    return state
}

/**
 * What the card reads from a session: its streamed [readings], its one breakdown owner (the one the
 * composer meter reads), and where compaction and the context limit go ([routes]).
 */
class AgentContextSession(
    val readings: ContextTokenReadings,
    val breakdown: ContextBreakdownLoader,
    val routes: AgentContextRoutes,
) {
    /** The card's deps over this session, with the host's notification [attention]. */
    fun cardDeps(attention: AttentionPrefsRepository?): AgentContextCardDeps =
        AgentContextCardDeps(
            readings = readings,
            breakdown = breakdown,
            compaction = CompactionController(routes.compaction, readings),
            contextLimit = ContextLimitController(routes.contextLimit),
            attention = attention,
        )
}

/**
 * The one model-and-context card of every platform: the panel's (desktop, web)
 * and the drawer's (Android) card for the focused agent, opening its sheet over it; nothing while
 * no agent is focused. The conversation's own model and window come first.
 */
@Composable
fun FocusedAgentContextCard(
    binding: AgentContextCardBinding,
    focus: ContextConversationFocus,
    window: ContextWindowSources,
    /** The composer chip's label for the model, when the host shows one. */
    modelLabel: String?,
) {
    val agentId = focus.agentId ?: return
    val view = ConversationModelView.of(window.modelSelections, focus.conversationId, window.agentOf(focus), window.models)
    AgentContextCardHost(
        binding = binding,
        focus = AgentContextFocus(
            agentId = agentId,
            conversationId = focus.conversationId,
            modelLabel = modelLabel ?: view.label,
            modelValue = view.model,
            effort = view.effort,
            windowTokens = view.windowTokens,
            modelWindowTokens = view.modelWindowTokens,
            turnRunning = focus.turnRunning,
            picksTargetConversation = focus.picksTargetConversation,
        ),
    )
}
