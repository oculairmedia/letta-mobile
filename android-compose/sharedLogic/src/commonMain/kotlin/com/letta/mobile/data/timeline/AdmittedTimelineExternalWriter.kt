package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter

/** Owns complete external operations, including transport awaits and their final persistence. */
class AdmittedTimelineExternalWriter(
    private val delegate: TimelineExternalTransportWriter,
    private val admission: TimelineLegacyAdmission,
) : TimelineExternalTransportWriter {
    override suspend fun appendExternalTransportLocal(conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>) =
        admission.admitted(conversationId) { delegate.appendExternalTransportLocal(conversationId, content, otid, attachments) }
    override suspend fun appendExternalTransportLocal(agentId: String?, conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>) =
        admission.admitted(conversationId) { delegate.appendExternalTransportLocal(agentId, conversationId, content, otid, attachments) }
    override suspend fun ingestExternalTransportMessage(conversationId: String, message: LettaMessage, source: String) =
        admission.admitted(conversationId) { delegate.ingestExternalTransportMessage(conversationId, message, source) }
    override suspend fun ingestExternalTransportMessage(agentId: String?, conversationId: String, message: LettaMessage, source: String) =
        admission.admitted(conversationId) { delegate.ingestExternalTransportMessage(agentId, conversationId, message, source) }
    override suspend fun markExternalTransportLocalSent(conversationId: String, otid: String) =
        admission.admitted(conversationId) { delegate.markExternalTransportLocalSent(conversationId, otid) }
    override suspend fun markExternalTransportLocalSent(agentId: String?, conversationId: String, otid: String) =
        admission.admitted(conversationId) { delegate.markExternalTransportLocalSent(agentId, conversationId, otid) }
    override suspend fun markExternalTransportLocalFailed(conversationId: String, otid: String) =
        admission.admitted(conversationId) { delegate.markExternalTransportLocalFailed(conversationId, otid) }
    override suspend fun markExternalTransportLocalFailed(agentId: String?, conversationId: String, otid: String) =
        admission.admitted(conversationId) { delegate.markExternalTransportLocalFailed(agentId, conversationId, otid) }
    override suspend fun reconcileExternalTransportSend(conversationId: String, agentId: String, externalConversationId: String, otid: String) =
        admission.admitted(conversationId) { delegate.reconcileExternalTransportSend(conversationId, agentId, externalConversationId, otid) }
    override suspend fun reconcileExternalTransportSendScoped(agentId: String?, conversationId: String, externalConversationId: String, otid: String) =
        admission.admitted(conversationId) { delegate.reconcileExternalTransportSendScoped(agentId, conversationId, externalConversationId, otid) }
    override suspend fun repairExpiredConversationCursor(conversationId: String, fallbackSeq: Long?) =
        admission.admitted(conversationId) { delegate.repairExpiredConversationCursor(conversationId, fallbackSeq) }
    override suspend fun repairExpiredConversationCursorScoped(agentId: String?, conversationId: String, fallbackSeq: Long?) =
        repairExpiredConversationCursorScoped(agentId, conversationId, fallbackSeq, expectedWatermark = null)
    override suspend fun repairExpiredConversationCursorScoped(
        agentId: String?,
        conversationId: String,
        fallbackSeq: Long?,
        expectedWatermark: Long?,
    ) = admission.admitted(conversationId) {
        delegate.repairExpiredConversationCursorScoped(agentId, conversationId, fallbackSeq, expectedWatermark)
    }
    override suspend fun clearExternalTransportActive(conversationId: String) =
        admission.admitted(conversationId) { delegate.clearExternalTransportActive(conversationId) }
    override suspend fun clearExternalTransportActive(agentId: String?, conversationId: String) =
        admission.admitted(conversationId) { delegate.clearExternalTransportActive(agentId, conversationId) }
    override suspend fun cleanupAbandonedAssistantFragments(agentId: String?, conversationId: String, runId: String?, turnId: String?, reason: String, candidateRunIds: Set<String>) =
        admission.admitted(conversationId) { delegate.cleanupAbandonedAssistantFragments(agentId, conversationId, runId, turnId, reason, candidateRunIds) }
    override suspend fun reconcileRecentMessages(agentId: String?, conversationId: String, reason: String, forceRefresh: Boolean, connectionGeneration: Long) =
        admission.admitted(conversationId) { delegate.reconcileRecentMessages(agentId, conversationId, reason, forceRefresh, connectionGeneration) }
    override suspend fun turnStarted(agentId: String?, conversationId: String, runId: String?, turnId: String?) =
        admission.admitted(conversationId) { delegate.turnStarted(agentId, conversationId, runId, turnId) }
    override suspend fun turnEnded(agentId: String?, conversationId: String, clean: Boolean) =
        admission.admitted(conversationId) { delegate.turnEnded(agentId, conversationId, clean) }
}
