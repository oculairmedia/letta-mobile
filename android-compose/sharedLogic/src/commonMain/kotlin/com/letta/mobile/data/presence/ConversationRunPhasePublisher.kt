package com.letta.mobile.data.presence

import com.letta.mobile.data.model.SubagentEntry
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
/** The conversation a run belongs to and the agent driving it: what every phase is published under. */
data class RunScope(val conversationId: String, val agentId: String)

class ConversationRunPhasePublisher(
    private val registry: ConversationRunRegistry,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = SynchronizedObject()
    private val states = mutableMapOf<String, ConversationRunState>()

    /** The conversations the last [setSubagents] list touched, so the next one can clear those it drops. */
    private var subagentScopes: Set<RunScope> = emptySet()

    /** Fold one runtime event for [conversationId] and publish the new phase. */
    fun onEvent(scope: RunScope, event: RuntimeEventPayload) {
        update(scope) { RunPhaseReducer.reduce(it, event, now()) }
    }

    /** Fold a batch in arrival order (transports deliver drafts in batches). */
    fun onEvents(scope: RunScope, events: List<RuntimeEventPayload>) {
        if (events.isEmpty()) return
        update(scope) { start ->
            val stamp = now()
            events.fold(start) { state, event -> RunPhaseReducer.reduce(state, event, stamp) }
        }
    }

    /** The user is composing to this conversation. Orthogonal to the phase; published the same way. */
    fun setUserTyping(scope: RunScope, typing: Boolean) {
        update(scope) { it.copy(userTyping = typing) }
    }

    /**
     * The whole current list from one subagent feed (the registry the platform already shows as
     * chips). Each entry's own conversation gets its foreground count - a running tool with
     * subagents under it is DELEGATING - and whether a dream is running there; a conversation this
     * feed listed before and lists no longer goes back to none. This is the one way either
     * platform tells the run registry about subagents.
     */
    fun setSubagents(entries: List<SubagentEntry>) {
        val byScope = subagentsByConversation(entries)
        val stale = synchronized(lock) {
            val gone = subagentScopes - byScope.keys
            subagentScopes = byScope.keys
            gone
        }
        stale.forEach { scope -> update(scope) { RunPhaseReducer.withSubagents(it, SubagentActivity.NONE, now()) } }
        byScope.forEach { (scope, scoped) ->
            val activity = SubagentActivity.of(scoped)
            update(scope) { RunPhaseReducer.withSubagents(it, activity, now()) }
        }
    }

    /** The user asked to cancel; the terminal has not landed yet. */
    fun markInterrupting(scope: RunScope) {
        update(scope) { RunPhaseReducer.interrupting(it, now()) }
    }

    /** Re-key a conversation that was published under a placeholder id before the server named it. */
    fun rekey(fromConversationId: String, to: RunScope) {
        if (fromConversationId == to.conversationId) return
        synchronized(lock) {
            val carried = states.remove(fromConversationId)
            registry.clear(fromConversationId)
            val next = (carried ?: ConversationRunState(to.conversationId, to.agentId))
                .copy(conversationId = to.conversationId, agentId = to.agentId)
            states[to.conversationId] = next
            registry.publish(next)
        }
    }

    /** Forget this conversation entirely (the screen driving it is gone). */
    fun clear(conversationId: String) {
        synchronized(lock) {
            states.remove(conversationId)
            registry.clear(conversationId)
        }
    }

    // Each mutation of [states] and its publication into the registry happen under the one lock:
    // two updates racing could otherwise store A, store-and-publish B, then publish the stale A.
    private fun update(scope: RunScope, transform: (ConversationRunState) -> ConversationRunState) {
        synchronized(lock) {
            val known = states[scope.conversationId]
            val current = known ?: ConversationRunState(scope.conversationId, scope.agentId)
            val updated = transform(current).copy(conversationId = scope.conversationId, agentId = scope.agentId)
            if (updated == known) return // nothing changed and the registry already has it
            states[scope.conversationId] = updated
            registry.publish(updated)
        }
    }
}
