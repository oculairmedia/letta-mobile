package com.letta.mobile.data.chat.send

import com.letta.mobile.data.transport.WsChatBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * letta-mobile-1n5py: what a chat screen can see and do with the send queue. Every host (Android,
 * desktop) binds this one object; the queue logic stays in [QueuedSendDriver].
 */
class ChatSendQueueControls internal constructor(
    private val scope: CoroutineScope,
    private val driver: QueuedSendDriver,
    /** Every conversation's queued messages, in run order. */
    val state: StateFlow<SendQueues>,
) {
    /** Drops one queued message; it never reached the server, so nothing else needs undoing. */
    fun cancel(id: QueuedSendId): Job = scope.launch { driver.cancel(id) }

    /** Aborts the running turn and runs [id] next, ahead of anything queued before it. */
    fun sendNow(id: QueuedSendId): Job = scope.launch { driver.sendNow(id) }

    /** Releases a queue a Stop (or a disconnect) paused. */
    fun resume(conversationId: QueueConversationId): Job = scope.launch { driver.resume(conversationId) }
}

/** The coordinator's turn state as [QueuedSendDriver] sees it: its lock, its bridge, its dispatch. */
internal class CoordinatorQueueTurns(
    private val lock: Mutex,
    private val bridge: WsChatBridge,
    private val dispatchQueued: suspend (QueuedChatSend) -> Boolean,
) : QueuedSendTurns {
    override suspend fun <T> serialized(block: suspend () -> T): T = lock.withLock { block() }

    override fun hasActiveTurn(conversationId: QueueConversationId): Boolean =
        bridge.hasActiveChatTurn(conversationId.value)

    override fun abortTurn(conversationId: QueueConversationId): Boolean = bridge.cancel(conversationId.value)

    override suspend fun dispatch(item: QueuedChatSend): Boolean = dispatchQueued(item)
}
