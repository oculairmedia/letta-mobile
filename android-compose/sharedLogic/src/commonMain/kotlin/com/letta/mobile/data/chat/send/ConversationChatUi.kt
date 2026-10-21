package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.util.Telemetry

/** Dynamic foreground eligibility shared by inbound callbacks and terminal settlement.
 * Call only at the effect, after suspending writes: selection can change during I/O.
 */
internal class ConversationChatUi(
    val sink: ChatSendUiSink,
    private val activeConversationId: () -> String?,
) {
    val visibleConversation: ConversationId? get() = activeConversationId()?.let(::ConversationId)

    fun owns(conversationId: ConversationId?): Boolean {
        val visible = activeConversationId() ?: return true
        return visible == conversationId?.value
    }

    /** Stop visual presence only on the conversation that is visible at this callback. */
    fun visuallyComplete(conversation: ConversationId) {
        if (owns(conversation)) {
            sink.onTurnVisuallyComplete()
        } else {
            suppressed(conversation, "onTurnVisuallyComplete")
        }
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
