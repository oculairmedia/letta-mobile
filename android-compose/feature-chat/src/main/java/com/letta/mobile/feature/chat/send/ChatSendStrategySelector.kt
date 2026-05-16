package com.letta.mobile.feature.chat.send

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.Job

internal class ChatSendStrategySelector(
    private val timelineStrategy: ChatSendStrategy,
    private val clientModeStrategy: ChatSendStrategy,
    private val wsStrategy: ChatSendStrategy,
) {
    fun select(context: ChatSendContext): ChatSendStrategy = when {
        context.isClientModeEnabled -> clientModeStrategy
        context.isShimBackend -> wsStrategy
        else -> timelineStrategy
    }

    fun send(
        text: String,
        attachments: List<MessageContentPart.Image>,
        context: ChatSendContext,
    ): Job {
        val strategy = select(context)
        Telemetry.event(
            "AdminChatVM", "sendMessage.route",
            "via" to strategy.routeName,
            "length" to text.length,
            "attachments" to attachments.size,
        )
        return strategy.send(text, attachments, context)
    }

    fun cancel(context: ChatSendContext) {
        select(context).cancel()
    }
}

private val ChatSendStrategy.routeName: String
    get() = when (this) {
        is ClientModeChatSendStrategy -> "client_mode"
        is WsChatSendStrategy -> "ws"
        is TimelineChatSendStrategy -> "timeline"
        else -> this::class.simpleName.orEmpty()
    }
