package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException

/** Keeps transient queued admissions retryable while surfacing permanent validation failures. */
internal suspend fun acceptPendingSendOrReportFailure(
    pending: QueuedChatSend,
    foreground: ConversationChatUi,
    isQueued: () -> Boolean,
    accept: suspend () -> Boolean,
): Boolean? = runCatching { accept() }.getOrElse { failure ->
    when (failure) {
        is CancellationException -> throw failure
        // Pending-store require checks (identity, row and byte budgets) cannot heal on retry.
        is IllegalArgumentException -> rejectPendingSend(pending, foreground, failure)
        is Exception -> {
            val queueOwnsSend = isQueued()
            if (pending.attempt > 0 || queueOwnsSend) throw failure
            rejectPendingSend(pending, foreground, failure)
        }
        else -> throw failure
    }
}

private fun rejectPendingSend(pending: QueuedChatSend, foreground: ConversationChatUi, failure: Exception): Boolean? {
    Telemetry.error("AdminChatVM", "ws.send.pendingRejected", failure)
    foreground.sendFailed(ConversationId(pending.conversationId.value),
        "Couldn’t save this message. Dismiss an unsent message or try a smaller message.")
    return null
}
