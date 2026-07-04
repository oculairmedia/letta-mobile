package com.letta.mobile.runtime.local

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransport
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Timeline transport for local-runtime conversations (letta-mobile-czomn):
 * hydration reads the letta.js transcript on disk so chat history survives
 * app restarts. Sends never come through here — the chat send strategy
 * routes local turns to the embedded runtime coordinator — and there is no
 * push stream; live frames are ingested directly by that coordinator.
 */
@Singleton
class LettaCodeLocalTimelineTransport @Inject constructor(
    private val store: LettaCodeLocalBackendStore,
) : TimelineTransport {
    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> =
        error("Local-runtime sends route through the embedded runtime coordinator, not the timeline transport.")

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> = flow {
        // Stay "connected" silently: completing would put the persistent
        // stream subscriber into a reconnect loop, and local frames arrive
        // via the embedded runtime coordinator instead.
        awaitCancellation()
    }

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        val messages = store.readTranscript(agentIdFromLocalConversationId(conversationId))
        val limited = if (limit != null && messages.size > limit) messages.takeLast(limit) else messages
        return if (order == "desc") limited.reversed() else limited
    }

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> {
        val messages = store.readTranscript(agentId)
        val limited = if (limit != null && messages.size > limit) messages.takeLast(limit) else messages
        return if (order == "desc") limited.reversed() else limited
    }

    companion object {
        fun isLocalConversationId(conversationId: String): Boolean = conversationId.startsWith("local-conv-")

        fun isLocalAgentId(agentId: String): Boolean = agentId.startsWith("local-agent-")

        fun agentIdFromLocalConversationId(conversationId: String): String =
            conversationId.removePrefix("local-conv-")
    }
}

/**
 * Routes timeline traffic by id namespace: local-runtime conversations and
 * agents go to the on-device transcript transport, everything else to the
 * remote API transport.
 */
class LocalRoutingTimelineTransport(
    private val local: LettaCodeLocalTimelineTransport,
    private val remote: TimelineTransport,
) : TimelineTransport {
    private fun transportFor(conversationId: String): TimelineTransport =
        if (LettaCodeLocalTimelineTransport.isLocalConversationId(conversationId)) local else remote

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> = transportFor(conversationId).sendConversationMessage(conversationId, request)

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> =
        transportFor(conversationId).streamConversation(conversationId)

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = transportFor(conversationId).listConversationMessages(conversationId, limit, after, order)

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> =
        if (LettaCodeLocalTimelineTransport.isLocalAgentId(agentId)) {
            local.listAgentMessages(agentId, limit, order, conversationId)
        } else {
            remote.listAgentMessages(agentId, limit, order, conversationId)
        }

    override suspend fun getToolReturn(
        conversationId: String,
        messageId: String,
    ): LettaMessage? = transportFor(conversationId).getToolReturn(conversationId, messageId)
}
