package com.letta.mobile.runtime.local

import com.letta.mobile.data.model.MessageId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.AgentId
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
        conversationId: ConversationId,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> =
        error("Local-runtime sends route through the embedded runtime coordinator, not the timeline transport.")

    override suspend fun streamConversation(conversationId: ConversationId): Flow<TimelineStreamFrame> = flow {
        // Stay "connected" silently: completing would put the persistent
        // stream subscriber into a reconnect loop, and local frames arrive
        // via the embedded runtime coordinator instead.
        awaitCancellation()
    }

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        val messages = store.readTranscript(agentIdFromLocalConversationId(conversationId.value))
        val limited = if (limit != null && messages.size > limit) messages.takeLast(limit) else messages
        return if (order == "desc") limited.reversed() else limited
    }

    override suspend fun listAgentMessages(
        agentId: AgentId,
        limit: Int?,
        order: String?,
        conversationId: ConversationId?,
    ): List<LettaMessage> {
        val messages = store.readTranscript(agentId.value)
        val limited = if (limit != null && messages.size > limit) messages.takeLast(limit) else messages
        return if (order == "desc") limited.reversed() else limited
    }

    companion object {
        // An on-device conversation id is local-conv-<localAgentId> where the
        // agent id is itself local (local-agent-*). The `local-conv-` prefix
        // ALONE is NOT sufficient: the REMOTE letta-code-local backend (e.g.
        // Meridian over Iroh) also names its conversations `local-conv-<n>`
        // (local-conv-101, …). Matching the bare prefix misrouted follow-up
        // sends on those remote conversations to the (unavailable) on-device
        // runtime — "local runtime not available". Require the suffix to be a
        // local agent id so remote local-conv-* stay on the remote transport.
        fun isLocalConversationId(conversationId: String): Boolean =
            conversationId.startsWith("local-conv-") &&
                isLocalAgentId(agentIdFromLocalConversationId(conversationId))

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
        conversationId: ConversationId,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> = transportFor(conversationId.value).sendConversationMessage(conversationId, request)

    override suspend fun streamConversation(conversationId: ConversationId): Flow<TimelineStreamFrame> =
        transportFor(conversationId.value).streamConversation(conversationId)

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = transportFor(conversationId.value).listConversationMessages(conversationId, limit, after, order)

    override suspend fun listAgentMessages(
        agentId: AgentId,
        limit: Int?,
        order: String?,
        conversationId: ConversationId?,
    ): List<LettaMessage> =
        if (LettaCodeLocalTimelineTransport.isLocalAgentId(agentId.value)) {
            local.listAgentMessages(agentId, limit, order, conversationId)
        } else {
            remote.listAgentMessages(agentId, limit, order, conversationId)
        }

    override suspend fun getToolReturn(
        conversationId: ConversationId,
        messageId: MessageId,
    ): LettaMessage? = transportFor(conversationId.value).getToolReturn(conversationId, messageId)
}
