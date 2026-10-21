package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.util.Telemetry

/** Dynamic foreground eligibility shared by inbound callbacks and terminal settlement.
 * Call only at the effect, after suspending writes: selection can change during I/O.
 */
internal class ConversationChatUi(
    val sink: ChatSendUiSink,
    private val activeConversationId: () -> String?,
    private val clearComposerAfterSend: () -> Unit,
) {
    val visibleConversation: ConversationId? get() = activeConversationId()?.let(::ConversationId)

    fun owns(conversationId: ConversationId?): Boolean {
        val visible = activeConversationId() ?: return true
        return visible == conversationId?.value
    }

    /** Stop visual presence only on the conversation that is visible at this callback. */
    fun visuallyComplete(conversation: ConversationId) {
        if (accepts(conversation, "onTurnVisuallyComplete")) sink.onTurnVisuallyComplete()
    }

    /** The terminal's error is computed only for a conversation still visible after its writes. */
    fun turnFinished(conversation: ConversationId, error: () -> String?) {
        if (accepts(conversation, "onTurnFinished")) sink.onTurnFinished(error())
    }

    fun started(conversation: ConversationId) {
        if (accepts(conversation, "onTurnStarted")) sink.onTurnStarted(conversation.value)
    }

    fun messageDelta(conversation: ConversationId) {
        if (accepts(conversation, "onMessageDelta")) sink.onMessageDelta(conversation.value)
    }

    fun usage(conversation: ConversationId, event: WsTimelineEvent.UsageStatistics) {
        if (!accepts(conversation, "onUsage")) return
        sink.onUsage(event.promptTokens.toInt(), event.completionTokens.toInt(), event.totalTokens.toInt())
    }

    fun error(conversation: ConversationId, message: String?) {
        if (accepts(conversation, "onError")) sink.onError(message)
    }

    fun sendFailed(conversation: ConversationId?, message: String) {
        if (accepts(conversation, "onSendFailed")) sink.onSendFailed(message)
    }

    fun clearComposer(conversation: ConversationId) {
        if (accepts(conversation, "clearComposer")) clearComposerAfterSend()
    }

    fun dispatched(conversation: ConversationId) {
        if (accepts(conversation, "onSendDispatched")) {
            sink.onSendDispatched(conversation.value.takeIf { it.isNotBlank() })
        }
    }

    fun queued(pending: QueuedChatSend, clearComposer: Boolean = true) {
        val conversation = ConversationId(pending.conversationId.value)
        if (clearComposer) this.clearComposer(conversation)
        if (accepts(conversation, "onSendQueued")) sink.onSendQueued(conversation.value)
    }

    /** Disconnect is global; only its held presence is conversation-owned. */
    fun transientDisconnect(states: List<ConversationTurnState>) {
        sink.onTransientDisconnect(states.any { state ->
            state.otid != null && owns(ConversationId(state.ownerConversationId))
        })
    }

    fun accepts(conversation: ConversationId?, callback: String): Boolean {
        if (owns(conversation)) return true
        suppressed(conversation, callback)
        return false
    }

    fun suppressed(conversationId: ConversationId?, callback: String) {
        Telemetry.event(
            "AdminChatVM", "ws.ui.backgroundConversationSuppressed",
            "conversationId" to conversationId?.value,
            "visibleConversationId" to activeConversationId(),
            "callback" to callback,
        )
    }
}
