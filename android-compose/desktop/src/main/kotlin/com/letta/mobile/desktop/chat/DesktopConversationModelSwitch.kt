package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.modelcontrol.ConversationEffortGateway
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChange
import com.letta.mobile.data.repository.modelcontrol.ModelHandle
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice

/**
 * letta-mobile-by4vm.85: one per-conversation switch in flight, with what to restore if the host
 * rejects it.
 */
internal data class DesktopModelSwitch(
    val conversationId: ConversationId,
    /** The selection value picked (what the composer chip matches on). */
    val model: String,
    val effort: ReasoningEffortChoice,
    val previousOverride: String?,
    val previousLabel: String,
)

/**
 * Sends a switch: `model.update` with the effort where the gateway carries one
 * ([ConversationEffortGateway]), else the model-only [ChatGatewayExtras.setConversationModel]. A
 * gateway without the effort refuses an effort pick rather than drop it silently.
 */
internal suspend fun DesktopChatGateway.sendConversationModel(
    conversation: ConversationId,
    model: ModelHandle,
    effort: ReasoningEffortChoice,
): Conversation {
    if (this is ConversationEffortGateway) {
        return updateConversationModel(ConversationModelChange(conversation, model, effort))
    }
    val extras = this as? ChatGatewayExtras ?: error("This backend cannot change a conversation's model")
    check(effort == ReasoningEffortChoice.Unchanged) { "This backend cannot change a conversation's reasoning effort" }
    return extras.setConversationModel(conversation.value, model.value)
}
