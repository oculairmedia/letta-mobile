package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.chat.send.QueueConversationId
import com.letta.mobile.data.chat.send.SendQueues
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.ui.chat.QueuedSendActions
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * letta-mobile-1n5py: desktop's binding of the shared send queue. The queue itself lives in the
 * shared ChatSendCoordinator; desktop only reads the selected conversation's queue and forwards the
 * panel's controls to the coordinator serving that conversation.
 */

/** The selected conversation's queued sends on the canonical route; empty off it. */
@Composable
internal fun rememberSelectedSendQueue(
    chatController: DesktopChatController,
    canonicalPresentation: CanonicalTimelinePresentation?,
    selectedConversationId: String?,
): ConversationSendQueue {
    val queues by remember(canonicalPresentation, selectedConversationId) {
        chatController.canonicalSendQueue()?.state ?: MutableStateFlow<SendQueues>(emptyMap())
    }.collectAsState()
    return selectedConversationId?.let { queues[QueueConversationId(it)] } ?: ConversationSendQueue()
}

/** The panel's controls, resolved against whichever coordinator serves the selection at click time. */
internal fun desktopQueuedSendActions(chatController: DesktopChatController) = QueuedSendActions(
    onCancel = { id -> chatController.canonicalSendQueue()?.cancel(id) },
    onSendNow = { id -> chatController.canonicalSendQueue()?.sendNow(id) },
    onResume = {
        val queue = chatController.canonicalSendQueue()
        chatController.state.value.selectedConversationId?.let { queue?.resume(QueueConversationId(it)) }
    },
)
