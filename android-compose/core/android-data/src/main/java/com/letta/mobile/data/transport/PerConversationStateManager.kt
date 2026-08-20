package com.letta.mobile.data.transport

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns per-conversation turn routing state for the singleton WebSocket transport.
 *
 * The socket is shared, but send/cancel/A2UI routing is conversation-scoped: each
 * conversation tracks its own in-flight turn, active run/turn identifiers, and
 * queued user actions.
 */
internal class PerConversationStateManager {
    private val conversationStates = ConcurrentHashMap<String, PerConversationState>()
    private val runConversationIds = ConcurrentHashMap<String, String>()

    fun stateForConversation(conversationId: String): PerConversationState =
        conversationStates.getOrPut(conversationId) { PerConversationState() }

    fun activeConversationCount(): Int = conversationStates.size

    fun conversationIds(): Set<String> = conversationStates.keys

    fun clearAllTurnState() {
        conversationStates.values.forEach { perConv ->
            perConv.inFlight.set(false)
            perConv.currentRunId.set(null)
            perConv.currentTurnId.set(null)
        }
        runConversationIds.clear()
    }

    fun clearConversationTurnState(conversationId: String) {
        conversationStates[conversationId]?.let { perConv ->
            perConv.inFlight.set(false)
            perConv.currentRunId.set(null)
            perConv.currentTurnId.set(null)
        }
    }

    fun activeConversationForRun(runId: String?): String? =
        runId?.let(runConversationIds::get)

    fun recordRunConversation(runId: String, conversationId: String) {
        runConversationIds[runId] = conversationId
    }

    fun clearRunConversation(runId: String) {
        runConversationIds.remove(runId)
    }

    fun currentRunId(conversationId: String): String? =
        conversationStates[conversationId]?.currentRunId?.get()

    fun currentTurnId(conversationId: String): String? =
        conversationStates[conversationId]?.currentTurnId?.get()

    fun pendingA2uiActions(conversationId: String): ArrayDeque<UserActionFrame> =
        stateForConversation(conversationId).pendingA2uiActions

    fun existingPendingA2uiActions(conversationId: String): ArrayDeque<UserActionFrame>? =
        conversationStates[conversationId]?.pendingA2uiActions

    fun pendingA2uiActionCount(): Int =
        conversationStates.values.sumOf { it.pendingA2uiActions.size }

    fun clearPendingA2uiActions() {
        conversationStates.values.forEach { it.pendingA2uiActions.clear() }
    }

    fun clearPendingA2uiActions(conversationId: String): Int {
        val pending = conversationStates[conversationId]?.pendingA2uiActions ?: return 0
        val size = pending.size
        pending.clear()
        return size
    }
}

internal data class PerConversationState(
    val inFlight: AtomicBoolean = AtomicBoolean(false),
    val currentRunId: AtomicReference<String?> = AtomicReference(null),
    val currentTurnId: AtomicReference<String?> = AtomicReference(null),
    val pendingA2uiActions: ArrayDeque<UserActionFrame> = ArrayDeque(),
)
