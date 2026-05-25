package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart

/**
 * Narrow timeline surface for the admin-shim WebSocket transport path.
 * This keeps WebSocket tests on deterministic fakes instead of mocking the
 * process-wide TimelineRepository and its cached TimelineSyncLoop state.
 */
interface TimelineExternalTransportWriter {
    suspend fun appendExternalTransportLocal(
        conversationId: String,
        content: String,
        otid: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): String

    suspend fun ingestExternalTransportMessage(conversationId: String, message: LettaMessage)

    suspend fun markExternalTransportLocalSent(conversationId: String, otid: String)

    suspend fun markExternalTransportLocalFailed(conversationId: String, otid: String)

    suspend fun reconcileExternalTransportSend(
        conversationId: String,
        agentId: String,
        externalConversationId: String,
        otid: String,
    )

    suspend fun clearExternalTransportActive(conversationId: String)
}
