package com.letta.mobile.feature.chat.send

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.feature.chat.coordination.WsChatSendCoordinator
import kotlinx.coroutines.Job

/**
 * letta-mobile-lgns8.10.4.1: the Iroh-native chat send strategy.
 *
 * Iroh backends previously selected [WsChatSendStrategy] — the shim-shaped
 * route — because `ShimBackendDetector` reported `isShimBackend = true` for
 * them. Routing now keys on [com.letta.mobile.data.model.BackendKind], and an
 * Iroh config lands here instead. [WsChatSendStrategy] is reachable ONLY for
 * genuinely shim-configured backends.
 *
 * Both strategies drive the same [WsChatSendCoordinator]. That is deliberate
 * and not an alias: the coordinator is transport-neutral — it talks to the
 * `IChannelTransport` that `SessionGraphFactory` bound for the active config
 * (`IrohChannelTransport` for `iroh://`, the shim `ChannelTransport` for a
 * shim config). What the two strategies separate is the *routing decision* and
 * its telemetry, so "an Iroh client selected a shim route" is a type-level
 * impossibility rather than a runtime coincidence.
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
