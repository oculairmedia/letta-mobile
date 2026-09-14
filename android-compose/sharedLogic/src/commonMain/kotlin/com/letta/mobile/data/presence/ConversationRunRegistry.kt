package com.letta.mobile.data.presence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One conversation's run, as the screen driving it knows it. */
data class ConversationRunState(
    val conversationId: String,
    val agentId: String,
    /** A turn is in flight: sent and not yet at its terminal frame. */
    val running: Boolean,
    /** Tokens are arriving (as opposed to the thinking gaps before the first token and between tool phases). */
    val streamingTokens: Boolean = false,
    /** The user is composing to this conversation. */
    val userTyping: Boolean = false,
    /** The last attempt failed. */
    val error: Boolean = false,
)

/**
 * What every conversation the app is driving is doing right now, keyed by conversation. The chat
 * view models publish their own conversation and clear it when they go; anything that needs a
 * transport-neutral "is a run in flight" - the conversation list, the mascots' presence - reads
 * from here rather than from a transport's cursor store. One instance per app.
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

    /** Conversations with a turn in flight. */
    fun runningConversationIds(): Set<String> = runs.value.filterValues { it.running }.keys
}

/**
 * Each agent's presence across all of its conversations: speaking if any of them streams tokens,
 * thinking if any is otherwise running, typing if the user composes to any, error if any failed.
 * The mascots' directors take it from here; nothing else maps run state to presence.
 */
fun Map<String, ConversationRunState>.presenceByAgent(): Map<String, AgentPresence> =
    values.groupBy { it.agentId }.mapValues { (_, states) ->
        AgentPresence(
            activity = when {
                states.any { it.running && it.streamingTokens } -> AgentActivityKind.SPEAKING
                states.any { it.running } -> AgentActivityKind.THINKING
                else -> AgentActivityKind.IDLE
            },
            userTyping = states.any { it.userTyping },
            error = states.any { it.error },
        )
    }
