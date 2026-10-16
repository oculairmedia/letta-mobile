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
 *  - [BackendKind.LOCAL_RUNTIME] -> the `local` route
 *  - [BackendKind.IROH]          -> the `iroh` route
 *  - [BackendKind.DIRECT_WS]     -> the `timeline` route
 *
 * letta-mobile-by4vm.26: [BackendKind.DIRECT_WS] is a live configuration (a Settings "Remote
 * host" with a ws:// App Server address; LettaConfigMigration keeps such addresses), not a
 * shim-era leftover, so its strategy stays. Iroh and direct ws:// therefore still reach the same
 * App Server through two send paths (`iroh` over the frame channel, `timeline` through the
 * timeline repository): a direct ws:// session binds no frame channel (`SessionChannelTransportFactory`
 * gives it a NoOp transport), so collapsing them needs a channel transport for DIRECT_WS first —
 * a wire change tracked on letta-mobile-by4vm.52.
 */
internal class ChatSendStrategySelector(
    private val timelineStrategy: ChatSendStrategy,
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
            BackendKind.DIRECT_WS -> timelineStrategy
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
