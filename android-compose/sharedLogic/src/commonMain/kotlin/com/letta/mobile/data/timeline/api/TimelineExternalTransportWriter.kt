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
    ): String

    suspend fun ingestExternalTransportMessage(conversationId: String, message: LettaMessage, source: String = "external")

    suspend fun ingestExternalTransportMessage(agentId: String?, conversationId: String, message: LettaMessage, source: String = "external")

    suspend fun markExternalTransportLocalSent(conversationId: String, otid: String)

    suspend fun markExternalTransportLocalSent(agentId: String?, conversationId: String, otid: String)

    suspend fun markExternalTransportLocalFailed(conversationId: String, otid: String)

    suspend fun markExternalTransportLocalFailed(agentId: String?, conversationId: String, otid: String)

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
    )

    suspend fun repairExpiredConversationCursor(conversationId: String, fallbackSeq: Long?)

    suspend fun repairExpiredConversationCursorScoped(agentId: String?, conversationId: String, fallbackSeq: Long?)

    suspend fun clearExternalTransportActive(conversationId: String)

    suspend fun clearExternalTransportActive(agentId: String?, conversationId: String)

    suspend fun cleanupAbandonedAssistantFragments(agentId: String?, conversationId: String, runId: String?, turnId: String?, reason: String, candidateRunIds: Set<String> = emptySet()): Int

    suspend fun reconcileRecentMessages(agentId: String?, conversationId: String, reason: String, forceRefresh: Boolean = false): Int

    /**
     * letta-mobile-dangling-tool: signals that a turn started on
     * [conversationId] so the timeline's post-turn dangling-tool-call sweep
     * (see DanglingToolCallResolver) can supersede whatever the previous
     * turn's sweep left pending. Default no-op so existing fakes compile
     * unchanged.
     */
    suspend fun turnStarted(agentId: String?, conversationId: String) {}

    /**
     * letta-mobile-dangling-tool: signals that a turn ended on
     * [conversationId]. When [clean] is true (a genuine terminal completion,
     * not cancel/timeout/error) and unresolved tool-call cards remain, the
     * timeline schedules a bounded canonical-record-driven resolve sweep.
     * Default no-op so existing fakes compile unchanged.
     */
    suspend fun turnEnded(agentId: String?, conversationId: String, clean: Boolean) {}
}
