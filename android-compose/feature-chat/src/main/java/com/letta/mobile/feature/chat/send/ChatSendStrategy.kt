package com.letta.mobile.feature.chat.send

import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.MessageContentPart
import kotlinx.coroutines.Job

internal data class ChatSendContext(
    val isClientModeEnabled: Boolean,
    val explicitConversationId: String?,
    /**
     * letta-mobile-lgns8.10.4.1: routing keys on the backend KIND, not on a
     * boolean named `isShimBackend` that was true for Iroh.
     */
    val backendKind: BackendKind = BackendKind.REST,
    val isLocalRuntime: Boolean = false,
) {
    /** Iroh or shim WS — i.e. a duplex frame channel rather than REST. */
    val usesChannelTransport: Boolean get() = backendKind.usesChannelTransport

    /** ONLY the genuine LettaShim. Never true for Iroh. */
    val isShimBackend: Boolean get() = backendKind.isShim
}

internal interface ChatSendStrategy {
    fun send(
        text: String,
        attachments: List<MessageContentPart.Image>,
        context: ChatSendContext,
    ): Job

    fun cancel()
}
