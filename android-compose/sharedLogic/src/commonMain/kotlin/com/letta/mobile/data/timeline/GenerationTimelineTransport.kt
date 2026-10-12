package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageId
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.MessageCreateRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.plus

/** Bounded RPC operations are children of the captured backend, not the requesting UI. */
class GenerationTimelineTransport(
    private val delegate: TimelineTransport,
    parent: CoroutineScope,
) : TimelineTransport {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = parent + job

    private suspend fun <T> owned(block: suspend () -> T): T {
        val request = scope.async { block() }
        return try { request.await() } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { request.cancelAndJoin() }
        }
    }

    suspend fun retire() = job.cancelAndJoin()

    override suspend fun listConversationMessages(conversationId: ConversationId, limit: Int?, after: String?, order: String?) =
        owned { delegate.listConversationMessages(conversationId, limit, after, order) }
    override suspend fun listConversationMessagesBefore(conversationId: ConversationId, limit: Int, before: String, order: String) =
        owned { delegate.listConversationMessagesBefore(conversationId, limit, before, order) }
    override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?) =
        owned { delegate.listConversationMessagePage(request, progress) }
    override suspend fun listAgentMessages(agentId: AgentId, limit: Int?, order: String?, conversationId: ConversationId?) =
        owned { delegate.listAgentMessages(agentId, limit, order, conversationId) }
    override suspend fun getToolReturn(conversationId: ConversationId, messageId: MessageId) =
        owned { delegate.getToolReturn(conversationId, messageId) }

    // Iroh sends and live frames belong to ChatSendCoordinator. Do not return a flow which
    // could outlive this RPC generation and silently bypass retirement.
    override suspend fun sendConversationMessage(conversationId: ConversationId, request: MessageCreateRequest): kotlinx.coroutines.flow.Flow<com.letta.mobile.data.model.LettaMessage> =
        error("Use the captured external send owner")
    override suspend fun streamConversation(conversationId: ConversationId): kotlinx.coroutines.flow.Flow<TimelineStreamFrame> =
        error("Use the captured external frame owner")
}
