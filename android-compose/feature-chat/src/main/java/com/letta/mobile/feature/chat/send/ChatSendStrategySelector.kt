package com.letta.mobile.feature.chat.send

import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.Job

/**
 * letta-mobile-lgns8.10.4.1: strategy selection keys on the active config's
 * [BackendKind], not on a URL heuristic.
 *
 *  - [BackendKind.LOCAL_RUNTIME] (or a local-bound agent) -> the `local` route
 *  - [BackendKind.IROH] and [BackendKind.DIRECT_WS]       -> the `iroh` route
 *
 * letta-mobile-by4vm.15: the legacy `timeline` route (a send through the in-memory
 * TimelineRepository) is gone with the legacy timeline. Direct ws:// goes through the same
 * channel route as Iroh; a direct ws:// session binds a NoOp frame channel
 * (`SessionChannelTransportFactory`), which refuses the send until letta-mobile-by4vm.52 gives
 * DIRECT_WS a real channel. The timeline route failed the same send already: its transport has
 * had no HTTP send since letta-mobile-ckmub.4.
 */
internal class ChatSendStrategySelector(
    private val localStrategy: ChatSendStrategy,
    private val irohStrategy: ChatSendStrategy,
) {
    fun select(context: ChatSendContext): ChatSendStrategy = when {
        // Local-runtime routing is a per-agent override, not a backend
        // classification, so it is evaluated before the kind mapping.
        context.isLocalRuntime -> localStrategy
        else -> when (context.backendKind) {
            BackendKind.LOCAL_RUNTIME -> localStrategy
            BackendKind.IROH, BackendKind.DIRECT_WS -> irohStrategy
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
            "isLocalRuntime" to context.isLocalRuntime,
        )
        Telemetry.event(
            "IrohTrace", "send.route",
            "via" to strategy.routeName,
            "conversationId" to context.explicitConversationId,
            "backendKind" to context.backendKind.name,
            "isLocalRuntime" to context.isLocalRuntime,
        )
        return strategy.send(text, attachments)
    }

    fun cancel(context: ChatSendContext) {
        select(context).cancel()
    }
}
