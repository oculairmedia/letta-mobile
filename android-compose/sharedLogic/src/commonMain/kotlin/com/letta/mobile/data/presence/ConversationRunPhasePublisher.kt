package com.letta.mobile.data.presence

import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock

/**
 * Folds a conversation's runtime events through [RunPhaseReducer] and publishes the result into a
 * [ConversationRunRegistry].
 *
 * This is the whole of what a platform has to do: hand it the events its transport already
 * produces and it keeps the registry honest. Android's chat view model and desktop's chat
 * controller both bind to this — neither owns a copy of the phase logic, and neither derives tool
 * state from its timeline projection.
 *
 * Thread-safe: events arrive on transport dispatchers while the composer's typing flag is set from
 * the UI one.
 */
class ConversationRunPhasePublisher(
    private val registry: ConversationRunRegistry,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = SynchronizedObject()
    private val states = mutableMapOf<String, ConversationRunState>()

    /** Fold one runtime event for [conversationId] and publish the new phase. */
    fun onEvent(conversationId: String, agentId: String, event: RuntimeEventPayload) {
        update(conversationId, agentId) { RunPhaseReducer.reduce(it, event, now()) }
    }

    /** Fold a batch in arrival order (transports deliver drafts in batches). */
    fun onEvents(conversationId: String, agentId: String, events: List<RuntimeEventPayload>) {
        if (events.isEmpty()) return
        update(conversationId, agentId) { start ->
            val stamp = now()
            events.fold(start) { state, event -> RunPhaseReducer.reduce(state, event, stamp) }
        }
    }

    /** The user is composing to this conversation. Orthogonal to the phase; published the same way. */
    fun setUserTyping(conversationId: String, agentId: String, typing: Boolean) {
        update(conversationId, agentId) { it.copy(userTyping = typing) }
    }

    /** How many subagents are running under this conversation's current tool call. */
    fun setSubagentCount(conversationId: String, agentId: String, count: Int) {
        update(conversationId, agentId) { RunPhaseReducer.withSubagents(it, count, now()) }
    }

    /** The user asked to cancel; the terminal has not landed yet. */
    fun markInterrupting(conversationId: String, agentId: String) {
        update(conversationId, agentId) { RunPhaseReducer.interrupting(it, now()) }
    }

    /** Re-key a conversation that was published under a placeholder id before the server named it. */
    fun rekey(fromConversationId: String, toConversationId: String, agentId: String) {
        if (fromConversationId == toConversationId) return
        val carried = synchronized(lock) { states.remove(fromConversationId) }
        registry.clear(fromConversationId)
        val next = (carried ?: ConversationRunState(toConversationId, agentId))
            .copy(conversationId = toConversationId, agentId = agentId)
        synchronized(lock) { states[toConversationId] = next }
        registry.publish(next)
    }

    /** Forget this conversation entirely (the screen driving it is gone). */
    fun clear(conversationId: String) {
        synchronized(lock) { states.remove(conversationId) }
        registry.clear(conversationId)
    }

    private fun update(
        conversationId: String,
        agentId: String,
        transform: (ConversationRunState) -> ConversationRunState,
    ) {
        val next = synchronized(lock) {
            val known = states[conversationId]
            val current = known ?: ConversationRunState(conversationId, agentId)
            val updated = transform(current).copy(conversationId = conversationId, agentId = agentId)
            if (updated == known) {
                null // nothing changed and the registry already has it
            } else {
                states[conversationId] = updated
                updated
            }
        } ?: return
        registry.publish(next)
    }
}
