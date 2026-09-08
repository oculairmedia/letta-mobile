package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.MessageCreateRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin

/** Bounded RPC operations are children of the captured backend, not the requesting UI. */
class GenerationTimelineTransport(
    private val delegate: TimelineTransport,
    parent: CoroutineScope,
) : TimelineTransport {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)

    private suspend fun <T> owned(block: suspend () -> T): T {
        val request = scope.async { block() }
        return try { request.await() } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { request.cancelAndJoin() }
        }
    }

    suspend fun retire() = job.cancelAndJoin()

    override suspend fun listConversationMessages(conversationId: String, limit: Int?, after: String?, order: String?) =
        owned { delegate.listConversationMessages(conversationId, limit, after, order) }
    override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?) =
        owned { delegate.listConversationMessagePage(request, progress) }
    override suspend fun listAgentMessages(agentId: String, limit: Int?, order: String?, conversationId: String?) =
        owned { delegate.listAgentMessages(agentId, limit, order, conversationId) }
    override suspend fun getToolReturn(conversationId: String, messageId: String) =
        owned { delegate.getToolReturn(conversationId, messageId) }

    // Iroh sends and live frames belong to ChatSendCoordinator. Do not return a flow which
    // could outlive this RPC generation and silently bypass retirement.
    override suspend fun sendConversationMessage(conversationId: String, request: MessageCreateRequest): kotlinx.coroutines.flow.Flow<com.letta.mobile.data.model.LettaMessage> =
        error("Use the captured external send owner")
    override suspend fun streamConversation(conversationId: String): kotlinx.coroutines.flow.Flow<TimelineStreamFrame> =
        error("Use the captured external frame owner")
}
