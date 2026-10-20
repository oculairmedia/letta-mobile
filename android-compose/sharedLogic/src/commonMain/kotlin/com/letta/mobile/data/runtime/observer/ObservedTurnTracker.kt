package com.letta.mobile.data.runtime.observer

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Identity and round boundary of a passively observed conversation turn. */
internal data class ObservedTurn<Conversation : Any>(
    val agentId: String,
    val conversationId: Conversation,
    val turnId: String?,
    val runId: String?,
    val roundContinues: Boolean = false,
) {
    fun after(previous: ObservedTurn<Conversation>?, messageType: String?): ObservedTurn<Conversation> {
        val sameTurn = previous?.takeIf { canContinue(it) }
        return copy(
            turnId = turnId ?: sameTurn?.turnId,
            runId = runId ?: sameTurn?.runId,
            roundContinues = continuesRound(previous, messageType),
        )
    }

    private fun canContinue(previous: ObservedTurn<Conversation>): Boolean {
        return turnId == null || previous.turnId == null || previous.turnId == turnId
    }

    private fun continuesRound(previous: ObservedTurn<Conversation>?, messageType: String?): Boolean {
        return when (messageType) {
            "stop_reason" -> roundContinues
            "approval_request_message" -> true
            "usage_statistics" -> previous?.roundContinues ?: false
            else -> false
        }
    }

    fun matches(other: ObservedTurn<Conversation>): Boolean {
        if (turnId != null && turnId != other.turnId) return false
        return runId == null || runId == other.runId
    }
}

/** Bounded completion history keyed by the caller's conversation identity type. */
internal class ObservedTurnTracker<Conversation : Any> {
    private val lock = SynchronizedObject()
    private val activeByConversation = HashMap<Conversation, ObservedTurn<Conversation>>()
    private val completedTurnIds = LinkedHashSet<String>()
    private val completedRunIds = LinkedHashSet<String>()
    private val completedWithoutTurnConversations = HashSet<Conversation>()

    fun active(conversation: Conversation): ObservedTurn<Conversation>? {
        return synchronized(lock) { activeByConversation[conversation] }
    }

    fun clearConversation(conversation: Conversation) {
        synchronized(lock) {
            activeByConversation.remove(conversation)
            completedWithoutTurnConversations.remove(conversation)
        }
    }

    fun reset() {
        synchronized(lock) {
            activeByConversation.clear()
            completedTurnIds.clear()
            completedRunIds.clear()
            completedWithoutTurnConversations.clear()
        }
    }

    fun isCompleted(incoming: ObservedTurn<Conversation>): Boolean {
        return synchronized(lock) { isCompletedLocked(incoming) }
    }

    fun resolveTerminal(incoming: ObservedTurn<Conversation>): ObservedTurn<Conversation>? {
        return synchronized(lock) {
            val active = activeByConversation[incoming.conversationId]
            val effective = incoming.copy(
                turnId = incoming.turnId ?: active?.turnId,
                runId = incoming.runId ?: active?.runId,
            )
            if (consumeCompletedTurnFinished(effective)) return@synchronized null
            active?.takeIf { it.matches(effective) }?.let { effective }
        }
    }

    private fun consumeCompletedTurnFinished(incoming: ObservedTurn<Conversation>): Boolean {
        val closedWithoutTurn = completedWithoutTurnConversations.remove(incoming.conversationId)
        val completedTurn = incoming.turnId?.let { it in completedTurnIds } == true
        val completedRun = incoming.runId?.let { it in completedRunIds } == true
        val inactive = activeByConversation[incoming.conversationId] == null
        val alreadyDone = closedWithoutTurn || completedTurn || (inactive && completedRun)
        if (alreadyDone) {
            rememberBounded(completedTurnIds, incoming.turnId)
            rememberBounded(completedRunIds, incoming.runId)
        }
        return alreadyDone
    }

    fun markCompleted(incoming: ObservedTurn<Conversation>) {
        synchronized(lock) {
            activeByConversation.remove(incoming.conversationId)
            rememberBounded(completedTurnIds, incoming.turnId)
            rememberBounded(completedRunIds, incoming.runId)
            if (incoming.turnId == null) {
                completedWithoutTurnConversations.add(incoming.conversationId)
            } else {
                completedWithoutTurnConversations.remove(incoming.conversationId)
            }
        }
    }

    fun recordDelta(incoming: ObservedTurn<Conversation>, messageType: String?) {
        synchronized(lock) {
            if (isCompletedLocked(incoming)) return@synchronized
            if (messageType == null) return@synchronized
            if (messageType in setOf("ping", "heartbeat")) return@synchronized
            if (incoming.turnId != null && incoming.runId != null) completedRunIds.remove(incoming.runId)
            completedWithoutTurnConversations.remove(incoming.conversationId)
            val previous = activeByConversation[incoming.conversationId]
            activeByConversation[incoming.conversationId] = incoming.after(previous, messageType)
        }
    }

    fun completeOnIdleLoop(conversation: Conversation): ObservedTurn<Conversation>? {
        return synchronized(lock) {
            val current = activeByConversation[conversation] ?: return@synchronized null
            if (current.roundContinues) return@synchronized null
            markCompleted(current)
            current
        }
    }

    private fun isCompletedLocked(incoming: ObservedTurn<Conversation>): Boolean {
        return when {
            incoming.turnId != null -> incoming.turnId in completedTurnIds
            incoming.runId != null -> incoming.runId in completedRunIds
            else -> false
        }
    }

    private fun <T : Any> rememberBounded(target: LinkedHashSet<T>, value: T?) {
        val key = value ?: return
        target.remove(key)
        target.add(key)
        while (target.size > MAX_COMPLETED_OBSERVER_TURNS) {
            target.remove(target.first())
        }
    }

    companion object {
        private const val MAX_COMPLETED_OBSERVER_TURNS = 32
    }
}
