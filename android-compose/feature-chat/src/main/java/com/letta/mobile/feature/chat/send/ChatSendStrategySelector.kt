package com.letta.mobile.feature.chat.send

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.Job

/**
 * letta-mobile-by4vm.110.6: every Android backend (an Iroh host, a ws:// App Server, the on-device
 * runtime's loopback App Server) is served by the session's App Server channel, so every send takes
 * the one channel route. The selector stays as the screen's seam and its `send.route` telemetry.
 */
internal class ChatSendStrategySelector(
    private val channelStrategy: ChatSendStrategy,
) {
    fun send(
        text: String,
        attachments: List<MessageContentPart.Image>,
        context: ChatSendContext,
    ): Job {
        Telemetry.event(
            "AdminChatVM", "sendMessage.route",
            "via" to channelStrategy.routeName,
            "length" to text.length,
            "attachments" to attachments.size,
            "conversationId" to context.explicitConversationId,
            "backendKind" to context.backendKind.name,
        )
        Telemetry.event(
            "IrohTrace", "send.route",
            "via" to channelStrategy.routeName,
            "conversationId" to context.explicitConversationId,
            "backendKind" to context.backendKind.name,
        )
        return channelStrategy.send(text, attachments)
    }

    fun cancel() = channelStrategy.cancel()
}
