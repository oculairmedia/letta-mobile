package com.letta.mobile.feature.chat.send

import com.letta.mobile.data.model.MessageContentPart
import kotlinx.coroutines.Job

internal data class ChatSendContext(
    val isClientModeEnabled: Boolean,
    val explicitConversationId: String?,
    val isShimBackend: Boolean = false,
)

internal interface ChatSendStrategy {
    fun send(
        text: String,
        attachments: List<MessageContentPart.Image>,
        context: ChatSendContext,
    ): Job

    fun cancel()
}
