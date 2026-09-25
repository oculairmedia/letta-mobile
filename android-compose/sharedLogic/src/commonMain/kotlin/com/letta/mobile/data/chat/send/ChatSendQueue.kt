package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.MessageContentPart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/** letta-mobile-1n5py: one message the user sent while its conversation had a turn running. */
data class QueuedChatSend(
    val otid: String,
    val conversationId: String,
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

    /** 1-based run position of [otid], or null when it is not queued here. */
    fun positionOf(otid: String): Int? = items.indexOfFirst { it.otid == otid }.takeIf { it >= 0 }?.plus(1)
}

/**
 * letta-mobile-1n5py: the client-held send queue, per conversation, unbounded.
 *
 * Only the queue's shape lives here; WHEN to run the head is the coordinator's decision (it knows
 * whether the transport still owns a turn). Every mutation is one atomic [MutableStateFlow.update],
 * so the UI always sees a consistent order.
 */
class ChatSendQueue {
    private val _state = MutableStateFlow<Map<String, ConversationSendQueue>>(emptyMap())
    val state: StateFlow<Map<String, ConversationSendQueue>> = _state.asStateFlow()

    fun queueFor(conversationId: String): ConversationSendQueue = _state.value[conversationId] ?: EMPTY

    fun hasItems(conversationId: String): Boolean = !queueFor(conversationId).isEmpty

    /** Appends [item] behind everything already queued for its conversation; returns its position. */
    fun enqueue(item: QueuedChatSend): Int {
        val next = _state.updateAndGetQueue(item.conversationId) { it.copy(items = it.items + item) }
        return next.items.size
    }

    /** The conversation that holds [otid], if any. */
    fun conversationOf(otid: String): String? =
        _state.value.entries.firstOrNull { (_, queue) -> queue.positionOf(otid) != null }?.key

    /** Removes [otid] wherever it is queued; returns the removed item. */
    fun remove(otid: String): QueuedChatSend? {
        val conversationId = conversationOf(otid) ?: return null
        var removed: QueuedChatSend? = null
        _state.updateQueue(conversationId) { queue ->
            removed = queue.items.firstOrNull { it.otid == otid }
            queue.copy(items = queue.items.filterNot { it.otid == otid })
        }
        return removed
    }

    /**
     * Moves [otid] to the head of its conversation, keeping everything it jumps in original order
     * behind it. Returns false when [otid] is not queued.
     */
    fun promote(otid: String): Boolean {
        val conversationId = conversationOf(otid) ?: return false
        var promoted = false
        _state.updateQueue(conversationId) { queue ->
            val item = queue.items.firstOrNull { it.otid == otid } ?: return@updateQueue queue
            promoted = true
            queue.copy(items = listOf(item) + queue.items.filterNot { it.otid == otid })
        }
        return promoted
    }

    /** Takes the head of [conversationId], or null when it is empty or paused. */
    fun takeNext(conversationId: String): QueuedChatSend? {
        var taken: QueuedChatSend? = null
        _state.updateQueue(conversationId) { queue ->
            if (queue.paused || queue.isEmpty) return@updateQueue queue
            taken = queue.items.first()
            queue.copy(items = queue.items.drop(1))
        }
        return taken
    }

    /** Puts [item] back at the head (a dispatch the transport did not accept). */
    fun putBack(item: QueuedChatSend) {
        _state.updateQueue(item.conversationId) { queue ->
            if (queue.positionOf(item.otid) != null) queue else queue.copy(items = listOf(item) + queue.items)
        }
    }

    /** Holds [conversationId]'s items; returns true when there was anything to hold. */
    fun pause(conversationId: String): Boolean {
        if (!hasItems(conversationId)) return false
        _state.updateQueue(conversationId) { it.copy(paused = true) }
        return true
    }

    /** Pauses every conversation that has queued items; returns those conversations. */
    fun pauseAll(): List<String> {
        val held = _state.getAndUpdate { all -> all.mapValues { (_, queue) -> queue.copy(paused = true) } }
        return held.keys.toList()
    }

    fun resume(conversationId: String) {
        _state.updateQueue(conversationId) { it.copy(paused = false) }
    }

    fun isPaused(conversationId: String): Boolean = queueFor(conversationId).paused

    private companion object {
        val EMPTY = ConversationSendQueue()

        /** An empty queue has no entry, so a later pause cannot latch onto nothing. */
        fun MutableStateFlow<Map<String, ConversationSendQueue>>.updateQueue(
            conversationId: String,
            transform: (ConversationSendQueue) -> ConversationSendQueue,
        ) {
            updateAndGetQueue(conversationId, transform)
        }

        fun MutableStateFlow<Map<String, ConversationSendQueue>>.updateAndGetQueue(
            conversationId: String,
            transform: (ConversationSendQueue) -> ConversationSendQueue,
        ): ConversationSendQueue {
            var result = EMPTY
            update { all ->
                val next = transform(all[conversationId] ?: EMPTY)
                result = next
                if (next.isEmpty) all - conversationId else all + (conversationId to next)
            }
            return result
        }
    }
}
