package com.letta.mobile.feature.chat.send

import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.MessageContentPart
import kotlinx.coroutines.Job

internal data class ChatSendContext(
    val explicitConversationId: String?,
    /**
     * letta-mobile-lgns8.10.4.1: routing keys on the backend KIND, not on a
     * boolean named `isShimBackend` that was true for Iroh.
     */
    val backendKind: BackendKind = BackendKind.DIRECT_WS,
    val isLocalRuntime: Boolean = false,
) {
    /** Iroh — i.e. a duplex frame channel rather than a directly dialled App Server. */
    val usesChannelTransport: Boolean get() = backendKind.usesChannelTransport
}

/**
 * letta-mobile-by4vm.52: one route into a send coordinator. The Iroh, timeline and local-runtime
 * routes were three wrapper classes that differed only in which coordinator they called; each is
 * now one of these, named by [routeName] for the `send.route` telemetry.
 */
internal class ChatSendStrategy(
    val routeName: String,
    private val sendVia: (String, List<MessageContentPart.Image>) -> Job,
    private val cancelVia: () -> Unit = {},
) {
    fun send(text: String, attachments: List<MessageContentPart.Image>): Job = sendVia(text, attachments)

    fun cancel() = cancelVia()
}
