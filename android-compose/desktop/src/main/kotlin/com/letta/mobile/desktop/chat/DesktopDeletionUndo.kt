package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * letta-mobile-bzvro.31: the delete the shell offers to take back (the undo bar).
 *
 * Only the most recent delete can be undone: a second delete replaces the offer of the first, whose
 * conversation then stays deleted. An offer belongs to the backend that served the delete: a new
 * backend ([backendChanged]) voids it, so Undo never hits another one.
 */
class DesktopDeletionUndo {
    /** One undoable delete: what to restore, to which state, and on which backend. */
    data class Offer(
        val conversationId: String,
        /** Whether the chat was already archived before the delete; Undo puts it back as it was. */
        val wasArchived: Boolean,
        val behavior: ConversationDeleteBehavior,
        val backendGeneration: Long,
        /** The row as listed, for putting it straight back when the roster cannot be re-read. */
        val listed: DesktopConversationSummary? = null,
    ) {
        /** Names this delete for its undo bar: a newer delete replaces the bar. */
        fun undoKey(): String = "$conversationId@$backendGeneration"
    }

    private val _pending = MutableStateFlow<Offer?>(null)
    val pending: StateFlow<Offer?> = _pending.asStateFlow()

    /** Bumped whenever a different gateway is bound; an offer from an older one is stale. */
    var backendGeneration = 0L
        private set

    /** A different backend was bound: any pending offer is void. */
    fun backendChanged() {
        backendGeneration++
        _pending.value = null
    }

    fun isCurrent(offer: Offer): Boolean = offer.backendGeneration == backendGeneration

    /**
     * Offers an undo for a delete [listed] (the row as it was listed, if it was) that started on
     * backend [startedOn]. Nothing is offered for a permanent delete, or when the backend changed
     * while the delete ran; the offer replaces any earlier one.
     */
    fun offer(
        conversationId: String,
        listed: DesktopConversationSummary?,
        behavior: ConversationDeleteBehavior,
        startedOn: Long,
    ) {
        if (behavior == ConversationDeleteBehavior.Permanent || startedOn != backendGeneration) return
        _pending.value = Offer(conversationId, listed?.archived == true, behavior, startedOn, listed)
    }

    /** The bar timed out, was dismissed or was acted on: the offer is spent. */
    fun clear(conversationId: String) {
        _pending.update { if (it?.conversationId == conversationId) null else it }
    }

    companion object {
        /** Restore the row or its archive flag, preserving newer details on an existing row. */
        fun rosterWithRowPutBack(roster: List<DesktopConversationSummary>, offer: Offer): List<DesktopConversationSummary>? {
            val row = offer.listed?.copy(archived = offer.wasArchived) ?: return null
            val current = roster.firstOrNull { it.id == row.id }
            return when {
                current == null -> listOf(row) + roster
                current.archived == row.archived -> null
                else -> roster.map { if (it.id == row.id) it.copy(archived = row.archived) else it }
            }
        }
    }
}
