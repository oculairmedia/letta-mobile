package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.MessageContentPart
import kotlin.jvm.JvmInline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/** letta-mobile-1n5py: a queued message's identity — the otid it is sent with once it runs. */
@JvmInline
value class QueuedSendId(val value: String)

/** letta-mobile-1n5py: the conversation a queue belongs to. */
@JvmInline
value class QueueConversationId(val value: String)

/** letta-mobile-1n5py: one message the user sent while its conversation had a turn running. */
data class QueuedChatSend(
    val id: QueuedSendId,
    val conversationId: QueueConversationId,
    val text: String,
    val attachments: List<MessageContentPart.Image> = emptyList(),
)

/**
 * letta-mobile-1n5py: the messages waiting for one conversation, in the order they will run.
 *
 * [paused] mirrors the App Server's own queue after an `abort_message`: a Stop holds what is
 * queued instead of running it, until the user resumes, sends again, or pushes one item through.
 */
data class ConversationSendQueue(
    val items: List<QueuedChatSend> = emptyList(),
    val paused: Boolean = false,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** 1-based run position of [id], or null when it is not queued here. */
    fun positionOf(id: QueuedSendId): Int? = items.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1)

    fun without(id: QueuedSendId): ConversationSendQueue = copy(items = items.filterNot { it.id == id })

    /** [id] first, everything it jumps behind it in original order; unchanged when absent. */
    fun promoted(id: QueuedSendId): ConversationSendQueue {
        val item = items.firstOrNull { it.id == id } ?: return this
        return copy(items = listOf(item) + items.filterNot { it.id == id })
    }
}

/** Every conversation's queue; an empty queue has no entry. */
typealias SendQueues = Map<QueueConversationId, ConversationSendQueue>

/**
 * letta-mobile-1n5py: the client-held send queue, per conversation, unbounded.
 *
 * Only the queue's shape lives here; WHEN to run the head is the coordinator's decision (it knows
 * whether the transport still owns a turn). Every mutation is one atomic [MutableStateFlow.update],
 * so the UI always sees a consistent order.
 */
class ChatSendQueue {
    private val _state = MutableStateFlow<SendQueues>(emptyMap())
    val state: StateFlow<SendQueues> = _state.asStateFlow()

    fun queueFor(conversationId: QueueConversationId): ConversationSendQueue = _state.value[conversationId] ?: EMPTY

    fun hasItems(conversationId: QueueConversationId): Boolean = !queueFor(conversationId).isEmpty

    /** Appends [item] behind everything already queued for its conversation; returns its position. */
    fun enqueue(item: QueuedChatSend): Int =
        updateQueue(item.conversationId) { it.copy(items = it.items + item) }.items.size

    /** The conversation that holds [id], if any. */
    fun conversationOf(id: QueuedSendId): QueueConversationId? =
        _state.value.entries.firstOrNull { (_, queue) -> queue.positionOf(id) != null }?.key

    /** Removes [id] wherever it is queued; returns the removed item. */
    fun remove(id: QueuedSendId): QueuedChatSend? {
        val conversationId = conversationOf(id) ?: return null
        val removed = queueFor(conversationId).items.firstOrNull { it.id == id }
        updateQueue(conversationId) { it.without(id) }
        return removed
    }

    /** Moves [id] to the head of its conversation. Returns false when [id] is not queued. */
    fun promote(id: QueuedSendId): Boolean {
        val conversationId = conversationOf(id) ?: return false
        updateQueue(conversationId) { it.promoted(id) }
        return true
    }

    /** Takes the head of [conversationId], or null when it is empty or paused. */
    fun takeNext(conversationId: QueueConversationId): QueuedChatSend? {
        var taken: QueuedChatSend? = null
        updateQueue(conversationId) { queue ->
            taken = queue.items.firstOrNull()?.takeUnless { queue.paused }
            if (taken == null) queue else queue.copy(items = queue.items.drop(1))
        }
        return taken
    }

    /** Puts [item] back at the head (a dispatch the transport did not accept). */
    fun putBack(item: QueuedChatSend) {
        updateQueue(item.conversationId) { queue ->
            if (queue.positionOf(item.id) != null) queue else queue.copy(items = listOf(item) + queue.items)
        }
    }

    /** Holds [conversationId]'s items; returns true when there was anything to hold. */
    fun pause(conversationId: QueueConversationId): Boolean {
        if (!hasItems(conversationId)) return false
        updateQueue(conversationId) { it.copy(paused = true) }
        return true
    }

    /** Pauses every conversation that has queued items; returns those conversations. */
    fun pauseAll(): List<QueueConversationId> =
        _state.getAndUpdate { all -> all.mapValues { (_, queue) -> queue.copy(paused = true) } }.keys.toList()

    fun resume(conversationId: QueueConversationId) {
        updateQueue(conversationId) { it.copy(paused = false) }
    }

    fun isPaused(conversationId: QueueConversationId): Boolean = queueFor(conversationId).paused

    /** An emptied queue drops its entry, so a later pause cannot latch onto nothing. */
    private fun updateQueue(
        conversationId: QueueConversationId,
        transform: (ConversationSendQueue) -> ConversationSendQueue,
    ): ConversationSendQueue {
        var result = EMPTY
        _state.update { all ->
            val next = transform(all[conversationId] ?: EMPTY)
            result = next
            if (next.isEmpty) all - conversationId else all + (conversationId to next)
        }
        return result
    }

    private companion object {
        val EMPTY = ConversationSendQueue()
    }
}
