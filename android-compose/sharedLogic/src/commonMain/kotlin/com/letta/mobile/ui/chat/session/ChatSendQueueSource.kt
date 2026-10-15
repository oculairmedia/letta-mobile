package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.chat.send.ChatSendQueueControls
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.chat.send.QueueConversationId
import com.letta.mobile.data.chat.send.QueuedSendId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * letta-mobile-by4vm.98.2: a chat host's send-queue extension point, read by a session port for
 * the queued-sends panel and the composer. Where [canQueueWhileStreaming] is true a send during a
 * run goes to the host, which queues it behind the run, instead of waiting for the run to end.
 */
interface ChatSendQueueSource {
    /** The conversation's queued sends, in run order. */
    val queue: Flow<ConversationSendQueue>

    /** A send during an active run is queued rather than refused. */
    val canQueueWhileStreaming: StateFlow<Boolean>

    fun cancel(id: QueuedSendId)

    fun sendNow(id: QueuedSendId)

    /** Releases the conversation's queue a Stop (or a disconnect) paused. */
    fun resume()
}

/** One conversation's queue on the send coordinator serving it (letta-mobile-1n5py). */
data class ChatSendQueueTarget(
    val controls: ChatSendQueueControls,
    val conversationId: QueueConversationId,
)

/**
 * The shared [ChatSendQueueSource] over a coordinator's [ChatSendQueueControls]: the panel follows
 * whichever conversation [targets] names, and each control goes to the target [current] names at
 * click time (null: no queue there, the control does nothing).
 */
class CoordinatorSendQueue(
    targets: Flow<ChatSendQueueTarget?>,
    override val canQueueWhileStreaming: StateFlow<Boolean>,
    private val current: () -> ChatSendQueueTarget?,
) : ChatSendQueueSource {
    @OptIn(ExperimentalCoroutinesApi::class)
    override val queue: Flow<ConversationSendQueue> = targets.distinctUntilChanged()
        .flatMapLatest { target ->
            target?.let { (controls, id) -> controls.state.map { it[id] ?: ConversationSendQueue() } } ?: flowOf(ConversationSendQueue())
        }
        .distinctUntilChanged()

    override fun cancel(id: QueuedSendId) {
        current()?.controls?.cancel(id)
    }

    override fun sendNow(id: QueuedSendId) {
        current()?.controls?.sendNow(id)
    }

    override fun resume() {
        current()?.let { it.controls.resume(it.conversationId) }
    }
}
