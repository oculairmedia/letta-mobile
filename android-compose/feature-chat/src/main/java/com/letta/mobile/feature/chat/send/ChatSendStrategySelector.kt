package com.letta.mobile.feature.chat.send

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.Job

internal class ChatSendStrategySelector(
    private val timelineStrategy: ChatSendStrategy,
    private val wsStrategy: ChatSendStrategy,
    private val localStrategy: ChatSendStrategy,
) {
    fun select(context: ChatSendContext): ChatSendStrategy = when {
        context.isLocalRuntime -> localStrategy
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
            "conversationId" to context.explicitConversationId,
            "isShimBackend" to context.isShimBackend,
            "isLocalRuntime" to context.isLocalRuntime,
            "isClientModeEnabled" to context.isClientModeEnabled,
        )
        Telemetry.event(
            "IrohTrace", "send.route",
            "via" to strategy.routeName,
            "conversationId" to context.explicitConversationId,
            "isShimBackend" to context.isShimBackend,
            "isLocalRuntime" to context.isLocalRuntime,
        )
        return strategy.send(text, attachments, context)
    }

    fun cancel(context: ChatSendContext) {
        select(context).cancel()
    }
}

private val ChatSendStrategy.routeName: String
    get() = when (this) {
        is LocalRuntimeChatSendStrategy -> "local"
        is WsChatSendStrategy -> "ws"
        is TimelineChatSendStrategy -> "timeline"
        else -> this::class.simpleName.orEmpty()
    }
