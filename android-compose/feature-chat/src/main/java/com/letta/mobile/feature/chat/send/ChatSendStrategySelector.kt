package com.letta.mobile.feature.chat.send

import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.Job

/**
 * letta-mobile-lgns8.10.4.1: strategy selection keys on the active config's
 * [BackendKind], not on a URL heuristic or on the old `isShimBackend` boolean
 * (which was `true` for Iroh and therefore routed the production transport
 * down the shim-shaped path).
 *
 * The mapping is total and exclusive:
 *  - [BackendKind.LOCAL_RUNTIME] -> [LocalRuntimeChatSendStrategy]
 *  - [BackendKind.IROH]          -> [IrohChatSendStrategy]
 *  - [BackendKind.SHIM_WS]       -> [WsChatSendStrategy]  (shim configs only)
 *  - [BackendKind.REST]          -> [TimelineChatSendStrategy]
 */
internal class ChatSendStrategySelector(
    private val timelineStrategy: ChatSendStrategy,
    private val wsStrategy: ChatSendStrategy,
    private val localStrategy: ChatSendStrategy,
    private val irohStrategy: ChatSendStrategy,
) {
    fun select(context: ChatSendContext): ChatSendStrategy = when {
        // Local-runtime routing is a per-agent override, not a backend
        // classification, so it is evaluated before the kind mapping.
        context.isLocalRuntime -> localStrategy
        else -> when (context.backendKind) {
            BackendKind.LOCAL_RUNTIME -> localStrategy
            BackendKind.IROH -> irohStrategy
            BackendKind.SHIM_WS -> wsStrategy
            BackendKind.REST -> timelineStrategy
        }
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
            "backendKind" to context.backendKind.name,
            "isShimBackend" to context.isShimBackend,
            "isLocalRuntime" to context.isLocalRuntime,
            "isClientModeEnabled" to context.isClientModeEnabled,
        )
        Telemetry.event(
            "IrohTrace", "send.route",
            "via" to strategy.routeName,
            "conversationId" to context.explicitConversationId,
            "backendKind" to context.backendKind.name,
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
        is IrohChatSendStrategy -> "iroh"
        is WsChatSendStrategy -> "ws"
        is TimelineChatSendStrategy -> "timeline"
        else -> this::class.simpleName.orEmpty()
    }
