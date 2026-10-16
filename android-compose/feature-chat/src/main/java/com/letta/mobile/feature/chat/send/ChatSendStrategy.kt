package com.letta.mobile.feature.chat.send

import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.MessageContentPart
import kotlinx.coroutines.Job

internal data class ChatSendContext(
    val explicitConversationId: String?,
    /** letta-mobile-lgns8.10.4.1: the backend kind, for telemetry and the stop label only. */
    val backendKind: BackendKind = BackendKind.DIRECT_WS,
)

/**
 * letta-mobile-by4vm.52: the route into the send coordinator, named by [routeName] for the
 * `send.route` telemetry.
 */
internal class ChatSendStrategy(
    val routeName: String,
    private val sendVia: (String, List<MessageContentPart.Image>) -> Job,
    private val cancelVia: () -> Unit = {},
) {
    fun send(text: String, attachments: List<MessageContentPart.Image>): Job = sendVia(text, attachments)

    fun cancel() = cancelVia()
}
