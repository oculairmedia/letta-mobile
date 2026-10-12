package com.letta.mobile.desktop

import com.letta.mobile.data.chat.send.QueueConversationId
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.ui.chat.QueuedSendActions

/*
 * letta-mobile-1n5py: desktop's binding of the shared send queue. The queue itself lives in the
 * shared ChatSendCoordinator; desktop only forwards the shared page's queue controls to the
 * coordinator serving the selected conversation (the port reads the queue, DesktopChatSessionPort).
 */

/** The panel's controls, resolved against whichever coordinator serves the selection at click time. */
internal fun desktopQueuedSendActions(chatController: DesktopChatController) = QueuedSendActions(
    onCancel = { id -> chatController.canonicalSendQueue()?.cancel(id) },
    onSendNow = { id -> chatController.canonicalSendQueue()?.sendNow(id) },
    onResume = {
        val queue = chatController.canonicalSendQueue()
        chatController.state.value.selectedConversationId?.let { queue?.resume(QueueConversationId(it)) }
    },
)
