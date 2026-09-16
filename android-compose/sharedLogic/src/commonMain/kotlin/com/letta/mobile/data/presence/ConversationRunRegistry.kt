package com.letta.mobile.data.presence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One conversation's run, as the screen driving it knows it. */
data class ConversationRunState(
    val conversationId: String,
    val agentId: String,
    /** What the turn is doing right now — the honest signal, reduced from the runtime's events. */
    val phase: RunPhase = RunPhase.IDLE,
    /** The tool in flight (or the last one that ran), when there is one. */
    val toolName: String? = null,
    /**
     * Tool calls issued and not yet returned. Ids rather than a counter because the same call can
     * be observed twice (an approval request carries its tool call, and the tool-call message
     * follows it), and a double count would leave the conversation working forever.
     */
    val openToolCallIds: Set<String> = emptySet(),
    /** Subagents running under this conversation's current tool call. */
    val subagentCount: Int = 0,
    /** When [phase] was entered, for minimum-dwell and "running for 12s" surfaces. */
    val phaseSinceEpochMs: Long = 0L,
    /** The user is composing to this conversation. Orthogonal to [phase]. */
    val userTyping: Boolean = false,
) {
    /** How many tool calls are in flight; tools overlap, so this is a count, not a flag. */
    val openToolCalls: Int get() = openToolCallIds.size

    /** A turn is in flight: sent and not yet at its terminal frame. */
    val running: Boolean get() = phase.isBusy

    /** Tokens are arriving (as opposed to the gaps before the first token and between tool phases). */
    val streamingTokens: Boolean get() = phase == RunPhase.RESPONDING

    /** The last attempt failed. */
    val error: Boolean get() = phase == RunPhase.FAILED

    /** The turn is parked on a tool approval. */
    val awaitingApproval: Boolean get() = phase == RunPhase.AWAITING_INPUT
}

/**
 * What every conversation the app is driving is doing right now, keyed by conversation. The chat
 * view models publish their own conversation and clear it when they go; anything that needs a
 * transport-neutral "is a run in flight" - the conversation list, the mascots' presence - reads
 * from here rather than from a transport's cursor store. One instance per app (per window on
 * desktop).
 */
class ConversationRunRegistry {
    private val _runs = MutableStateFlow<Map<String, ConversationRunState>>(emptyMap())
    val runs: StateFlow<Map<String, ConversationRunState>> = _runs.asStateFlow()

    fun publish(state: ConversationRunState) {
        _runs.update { it + (state.conversationId to state) }
    }

    fun clear(conversationId: String) {
        _runs.update { it - conversationId }
    }

    /** The conversation's last published state, or a rested one if it has never published. */
    fun stateOf(conversationId: String, agentId: String): ConversationRunState =
        runs.value[conversationId] ?: ConversationRunState(conversationId, agentId)

    /** Conversations with a turn in flight. */
    fun runningConversationIds(): Set<String> = runs.value.filterValues { it.running }.keys
}

/**
 * Each agent's presence across all of its conversations. The busiest honest phase wins: responding
 * beats running a tool, which beats reasoning; a parked approval and a failed attempt are their own
 * flags, attributed to the conversation they actually happened in.
 *
 * The mascots' directors take it from here; nothing else maps run state to presence.
 */
fun Map<String, ConversationRunState>.presenceByAgent(): Map<String, AgentPresence> =
    values.groupBy { it.agentId }.mapValues { (_, states) ->
        AgentPresence(
            activity = when {
                states.any { it.phase == RunPhase.RESPONDING } -> AgentActivityKind.SPEAKING
                states.any { it.phase == RunPhase.DELEGATING } -> AgentActivityKind.DELEGATING
                states.any { it.phase == RunPhase.WORKING } -> AgentActivityKind.WORKING
                states.any { it.running } -> AgentActivityKind.THINKING
                else -> AgentActivityKind.IDLE
            },
            awaitingApproval = states.any { it.awaitingApproval },
            userTyping = states.any { it.userTyping },
            error = states.any { it.error },
            toolName = states.firstOrNull { it.phase.isToolRunning }?.toolName,
        )
    }
