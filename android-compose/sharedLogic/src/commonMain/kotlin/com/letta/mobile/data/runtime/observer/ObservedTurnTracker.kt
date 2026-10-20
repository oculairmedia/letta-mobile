package com.letta.mobile.data.runtime.observer

import com.letta.mobile.data.model.ConversationId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Identity and round boundary of a passively observed conversation turn. */
internal data class ObservedTurn(
    val agentId: String,
    val conversationId: String,
    val turnId: String?,
    val runId: String?,
    val roundContinues: Boolean = false,
) {
    fun after(previous: ObservedTurn?, messageType: String?): ObservedTurn {
        val sameTurn = previous?.takeIf { canContinue(it) }
        return copy(
            turnId = turnId ?: sameTurn?.turnId,
            runId = runId ?: sameTurn?.runId,
            roundContinues = continuesRound(previous, messageType),
        )
    }

    private fun canContinue(previous: ObservedTurn): Boolean {
        return turnId == null || previous.turnId == null || previous.turnId == turnId
    }

    private fun continuesRound(previous: ObservedTurn?, messageType: String?): Boolean {
        return when (messageType) {
            "stop_reason" -> roundContinues
            "approval_request_message" -> true
            "usage_statistics" -> previous?.roundContinues ?: false
            else -> false
        }
    }

    fun matches(other: ObservedTurn): Boolean {
        if (turnId != null && turnId != other.turnId) return false
        return runId == null || runId == other.runId
    }
}

/** Bounded completion history; duplicate terminals never clear a newer active turn. */
internal class ObservedTurnTracker {
    private val lock = SynchronizedObject()
    private val activeByConversation = HashMap<String, ObservedTurn>()
    private val completedTurnIds = LinkedHashSet<String>()
    private val completedRunIds = LinkedHashSet<String>()
    private val completedWithoutTurnConversations = HashSet<String>()

    fun active(conversation: ConversationId): ObservedTurn? {
        return synchronized(lock) { activeByConversation[conversation.value] }
    }

    fun clearConversation(conversation: ConversationId) {
        synchronized(lock) {
            activeByConversation.remove(conversation.value)
            completedWithoutTurnConversations.remove(conversation.value)
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

    fun isCompleted(incoming: ObservedTurn): Boolean {
        return synchronized(lock) { isCompletedLocked(incoming) }
    }

    fun resolveTerminal(incoming: ObservedTurn): ObservedTurn? {
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

    private fun consumeCompletedTurnFinished(incoming: ObservedTurn): Boolean {
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

    fun markCompleted(incoming: ObservedTurn) {
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

    fun recordDelta(incoming: ObservedTurn, messageType: String?) {
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

    fun completeOnIdleLoop(conversation: ConversationId): ObservedTurn? {
        return synchronized(lock) {
            val current = activeByConversation[conversation.value] ?: return@synchronized null
            if (current.roundContinues) return@synchronized null
            markCompleted(current)
            current
        }
    }

    private fun isCompletedLocked(incoming: ObservedTurn): Boolean {
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
