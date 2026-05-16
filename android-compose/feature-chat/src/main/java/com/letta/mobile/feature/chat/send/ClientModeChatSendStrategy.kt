package com.letta.mobile.feature.chat.send

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.feature.chat.ClientModeSendCoordinator
import kotlinx.coroutines.Job

internal class ClientModeChatSendStrategy(
    private val coordinator: ClientModeSendCoordinator,
) : ChatSendStrategy {
    override fun send(
        text: String,
        attachments: List<MessageContentPart.Image>,
        context: ChatSendContext,
    ): Job = coordinator.send(
        text = text,
        attachments = attachments,
        explicitConversationId = context.explicitConversationId,
    )

    override fun cancel() {
        coordinator.cancelActiveStream()
    }
}
