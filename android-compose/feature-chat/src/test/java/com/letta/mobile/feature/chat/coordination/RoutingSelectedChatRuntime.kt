package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope

internal class RoutingSelectedChatRuntime(
    override val generation: Long,
    private val conversations: Set<String>,
    private val presentations: Map<String, ChatPagingPresentation>,
    private val canonicalWriter: TimelineExternalTransportWriter,
    parent: CoroutineScope,
    private val onRetire: () -> Unit = {},
) : SelectedChatRuntime {
    val opened = mutableListOf<String>()
    private var retired = false
    override val config = mockk<com.letta.mobile.data.model.LettaConfig>(relaxed = true)
    override val descriptor = mockk<com.letta.mobile.runtime.BackendDescriptor>(relaxed = true)
    override val scope: CoroutineScope = parent
    override val writer: TimelineExternalTransportWriter = object : TimelineExternalTransportWriter {
        override suspend fun appendExternalTransportLocal(
            conversationId: String,
            content: String,
            otid: String,
            attachments: List<MessageContentPart.Image>,
        ) = dest(conversationId).appendExternalTransportLocal(conversationId, content, otid, attachments)

        override suspend fun appendExternalTransportLocal(
            agentId: String?,
            conversationId: String,
            content: String,
            otid: String,
            attachments: List<MessageContentPart.Image>,
        ) = dest(conversationId).appendExternalTransportLocal(agentId, conversationId, content, otid, attachments)

        override suspend fun ingestExternalTransportMessage(conversationId: String, message: LettaMessage, source: String) =
            dest(conversationId).ingestExternalTransportMessage(conversationId, message, source)

        override suspend fun ingestExternalTransportMessage(
            agentId: String?,
            conversationId: String,
            message: LettaMessage,
            source: String,
        ) = dest(conversationId).ingestExternalTransportMessage(agentId, conversationId, message, source)

        override suspend fun markExternalTransportLocalSent(conversationId: String, otid: String) =
            dest(conversationId).markExternalTransportLocalSent(conversationId, otid)

        override suspend fun markExternalTransportLocalSent(agentId: String?, conversationId: String, otid: String) =
            dest(conversationId).markExternalTransportLocalSent(agentId, conversationId, otid)

        override suspend fun markExternalTransportLocalFailed(conversationId: String, otid: String) =
            dest(conversationId).markExternalTransportLocalFailed(conversationId, otid)

        override suspend fun markExternalTransportLocalFailed(agentId: String?, conversationId: String, otid: String) =
            dest(conversationId).markExternalTransportLocalFailed(agentId, conversationId, otid)

        override suspend fun reconcileExternalTransportSend(
            conversationId: String,
            agentId: String,
            externalConversationId: String,
            otid: String,
        ) = dest(conversationId).reconcileExternalTransportSend(conversationId, agentId, externalConversationId, otid)

        override suspend fun reconcileExternalTransportSendScoped(
            agentId: String?,
            conversationId: String,
            externalConversationId: String,
            otid: String,
        ) = dest(conversationId).reconcileExternalTransportSendScoped(agentId, conversationId, externalConversationId, otid)

        override suspend fun repairExpiredConversationCursor(conversationId: String, fallbackSeq: Long?) =
            dest(conversationId).repairExpiredConversationCursor(conversationId, fallbackSeq)

        override suspend fun repairExpiredConversationCursorScoped(agentId: String?, conversationId: String, fallbackSeq: Long?) =
            dest(conversationId).repairExpiredConversationCursorScoped(agentId, conversationId, fallbackSeq)

        override suspend fun repairExpiredConversationCursorScoped(
            agentId: String?,
            conversationId: String,
            fallbackSeq: Long?,
            expectedWatermark: Long?,
        ) = dest(conversationId).repairExpiredConversationCursorScoped(agentId, conversationId, fallbackSeq, expectedWatermark)

        override suspend fun clearExternalTransportActive(conversationId: String) =
            dest(conversationId).clearExternalTransportActive(conversationId)

        override suspend fun clearExternalTransportActive(agentId: String?, conversationId: String) =
            dest(conversationId).clearExternalTransportActive(agentId, conversationId)

        override suspend fun cleanupAbandonedAssistantFragments(
            agentId: String?,
            conversationId: String,
            runId: String?,
            turnId: String?,
            reason: String,
            candidateRunIds: Set<String>,
        ) = dest(conversationId).cleanupAbandonedAssistantFragments(agentId, conversationId, runId, turnId, reason, candidateRunIds)

        override suspend fun reconcileRecentMessages(
            agentId: String?,
            conversationId: String,
            reason: String,
            forceRefresh: Boolean,
            connectionGeneration: Long,
        ): RecentMessagesReconcileOutcome =
            dest(conversationId).reconcileRecentMessages(agentId, conversationId, reason, forceRefresh, connectionGeneration)

        override suspend fun turnStarted(agentId: String?, conversationId: String, runId: String?, turnId: String?) =
            dest(conversationId).turnStarted(agentId, conversationId, runId, turnId)

        override suspend fun turnEnded(agentId: String?, conversationId: String, clean: Boolean) =
            dest(conversationId).turnEnded(agentId, conversationId, clean)

        private suspend fun dest(conversationId: String): TimelineExternalTransportWriter {
            ready(conversationId)
            return canonicalWriter
        }
    }

    override suspend fun ready(conversationId: String) {
        check(!retired) { "Selected runtime retired" }
        check(conversationId in conversations) { "Unknown conversation $conversationId" }
    }

    override suspend fun open(
        conversationId: String,
        target: String?,
        scope: CoroutineScope,
    ): ChatPagingPresentation {
        ready(conversationId)
        opened += conversationId
        return presentations.getValue(conversationId)
    }

    override suspend fun retire() {
        retired = true
        onRetire()
    }
}
