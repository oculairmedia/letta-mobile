package com.letta.mobile.feature.chat.send

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.feature.chat.coordination.LocalRuntimeChatSendCoordinator
import kotlinx.coroutines.Job

internal class LocalRuntimeChatSendStrategy(
    private val coordinator: LocalRuntimeChatSendCoordinator,
) : ChatSendStrategy {
    override fun send(
        text: String,
        attachments: List<MessageContentPart.Image>,
        context: ChatSendContext,
    ): Job = coordinator.send(text, attachments)

    override fun cancel() {
        coordinator.cancel()
    }
}
