package com.letta.mobile.feature.chat.send

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.feature.chat.coordination.WsChatSendCoordinator
import kotlinx.coroutines.Job

/**
 * letta-mobile-lgns8.10.4.1: the Iroh-native chat send strategy.
 *
 * Routing keys on [com.letta.mobile.data.model.BackendKind]; an Iroh config
 * lands here. The [WsChatSendCoordinator] it drives is transport-neutral — it
 * talks to the `IChannelTransport` that `SessionGraphFactory` bound for the
 * active config (`IrohChannelTransport` for `iroh://`). The legacy shim
 * WebSocket route that once shared this coordinator was deleted in g70jb.3.
 */
internal class IrohChatSendStrategy(
    private val coordinator: WsChatSendCoordinator,
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
