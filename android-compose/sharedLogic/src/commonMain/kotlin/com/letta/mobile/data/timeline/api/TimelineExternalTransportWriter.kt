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

    suspend fun appendExternalTransportLocal(
        agentId: String?,
        conversationId: String,
        content: String,
        otid: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): String = appendExternalTransportLocal(conversationId, content, otid, attachments)

    suspend fun ingestExternalTransportMessage(conversationId: String, message: LettaMessage)

    suspend fun ingestExternalTransportMessage(agentId: String?, conversationId: String, message: LettaMessage) =
        ingestExternalTransportMessage(conversationId, message)

    suspend fun markExternalTransportLocalSent(conversationId: String, otid: String)

    suspend fun markExternalTransportLocalSent(agentId: String?, conversationId: String, otid: String) =
        markExternalTransportLocalSent(conversationId, otid)

    suspend fun markExternalTransportLocalFailed(conversationId: String, otid: String)

    suspend fun markExternalTransportLocalFailed(agentId: String?, conversationId: String, otid: String) =
        markExternalTransportLocalFailed(conversationId, otid)

    suspend fun reconcileExternalTransportSend(
        conversationId: String,
        agentId: String,
        externalConversationId: String,
        otid: String,
    )

    suspend fun reconcileExternalTransportSendScoped(
        agentId: String?,
        conversationId: String,
        externalConversationId: String,
        otid: String,
    ) = reconcileExternalTransportSend(conversationId, agentId.orEmpty(), externalConversationId, otid)

    suspend fun repairExpiredConversationCursor(conversationId: String, fallbackSeq: Long?)

    suspend fun clearExternalTransportActive(conversationId: String)

    suspend fun clearExternalTransportActive(agentId: String?, conversationId: String) =
        clearExternalTransportActive(conversationId)
}
