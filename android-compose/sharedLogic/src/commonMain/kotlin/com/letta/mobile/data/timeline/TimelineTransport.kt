package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import kotlinx.coroutines.flow.Flow

interface TimelineTransport {
    suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage>

    suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame>

    suspend fun listConversationMessages(
        conversationId: String,
        limit: Int? = null,
        after: String? = null,
        order: String? = null,
    ): List<LettaMessage>

    suspend fun listConversationMessagePage(
        request: TimelineRemotePageRequest,
        progress: TimelinePageProgress? = null,
    ): TimelineRemotePageResult {
        if (request.continuation is TimelineContinuation.Before) {
            throw TimelineRemotePageException.InvalidRequest(
                "this transport does not implement before-continuation pages",
            )
        }
        val page = TimelineRemotePageAdapter.load(request) { limit, _, after, order ->
            listConversationMessages(request.scope.conversationId, limit, after, order)
        }
        return progress?.let { TimelineRemotePageProgressClassifier.classify(request, page, it) } ?: page
    }

    suspend fun listAgentMessages(
        agentId: String,
        limit: Int? = null,
        order: String? = null,
        conversationId: String? = null,
    ): List<LettaMessage>

    /**
     * letta-mobile-fe51r (P2b pointer diet): fetch the full body of a single
     * tool-return message whose `message.list` representation was projected
     * to a preview. Returns null when the transport never projects list
     * responses (plain HTTP) and therefore has nothing to hydrate.
     */
    suspend fun getToolReturn(
        conversationId: String,
        messageId: String,
    ): LettaMessage? = null
}

class TimelineTransportHttpException(
    val code: Int,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class TimelineNoActiveRunException(val conversationId: String) :
    Exception("No active runs for conversation $conversationId")

sealed interface TimelineStreamFrame {
    data object Heartbeat : TimelineStreamFrame
    data object Done : TimelineStreamFrame
    data class Message(val message: LettaMessage) : TimelineStreamFrame
    data class RawEvent(
        val event: String?,
        val data: String,
        val id: String?,
    ) : TimelineStreamFrame
}
