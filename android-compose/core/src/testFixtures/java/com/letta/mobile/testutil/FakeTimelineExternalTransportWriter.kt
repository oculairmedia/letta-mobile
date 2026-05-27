package com.letta.mobile.testutil

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter

/** Deterministic fake for the admin-shim WebSocket timeline write surface. */
class FakeTimelineExternalTransportWriter : TimelineExternalTransportWriter {
    val externalLocals: MutableList<ExternalLocal> = mutableListOf()
    val ingestedMessages: MutableList<IngestedMessage> = mutableListOf()
    val sentLocals: MutableList<LocalMarker> = mutableListOf()
    val failedLocals: MutableList<LocalMarker> = mutableListOf()
    val reconciledSends: MutableList<ReconciledSend> = mutableListOf()
    val clearedActiveConversations: MutableList<String> = mutableListOf()
    val repairedCursors: MutableList<CursorRepair> = mutableListOf()

    override suspend fun appendExternalTransportLocal(
        conversationId: String,
        content: String,
        otid: String,
        attachments: List<MessageContentPart.Image>,
    ): String {
        externalLocals += ExternalLocal(conversationId, content, otid, attachments)
        return otid
    }

    override suspend fun ingestExternalTransportMessage(conversationId: String, message: LettaMessage) {
        ingestedMessages += IngestedMessage(conversationId, message)
    }

    override suspend fun markExternalTransportLocalSent(conversationId: String, otid: String) {
        sentLocals += LocalMarker(conversationId, otid)
    }

    override suspend fun markExternalTransportLocalFailed(conversationId: String, otid: String) {
        failedLocals += LocalMarker(conversationId, otid)
    }

    override suspend fun reconcileExternalTransportSend(
        conversationId: String,
        agentId: String,
        externalConversationId: String,
        otid: String,
    ) {
        reconciledSends += ReconciledSend(conversationId, agentId, externalConversationId, otid)
    }

    override suspend fun repairExpiredConversationCursor(conversationId: String, fallbackSeq: Long?) {
        repairedCursors += CursorRepair(conversationId, fallbackSeq)
    }

    override suspend fun clearExternalTransportActive(conversationId: String) {
        clearedActiveConversations += conversationId
    }

    data class ExternalLocal(
        val conversationId: String,
        val content: String,
        val otid: String,
        val attachments: List<MessageContentPart.Image>,
    )

    data class IngestedMessage(
        val conversationId: String,
        val message: LettaMessage,
    )

    data class LocalMarker(
        val conversationId: String,
        val otid: String,
    )

    data class ReconciledSend(
        val conversationId: String,
        val agentId: String,
        val externalConversationId: String,
        val otid: String,
    )

    data class CursorRepair(
        val conversationId: String,
        val fallbackSeq: Long?,
    )
}
