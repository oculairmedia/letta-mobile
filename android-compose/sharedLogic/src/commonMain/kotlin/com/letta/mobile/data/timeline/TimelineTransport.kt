package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageId
import com.letta.mobile.data.model.MessageCreateRequest
import kotlinx.coroutines.flow.Flow

interface TimelineTransport {
    suspend fun sendConversationMessage(
        conversationId: ConversationId,
        request: MessageCreateRequest,
    ): Flow<LettaMessage>

    suspend fun streamConversation(conversationId: ConversationId): Flow<TimelineStreamFrame>

    suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int? = null,
        after: String? = null,
        order: String? = null,
    ): List<LettaMessage>

    /**
     * One page strictly older than [before], newest first. Null means this transport has no such
     * route; the page loader then refuses the request rather than falling back to a tail read,
     * which would silently re-serve the newest page as "older history".
     */
    suspend fun listConversationMessagesBefore(
        conversationId: ConversationId,
        limit: Int,
        before: String,
        order: String,
    ): List<LettaMessage>? = null

    suspend fun listConversationMessagePage(
        request: TimelineRemotePageRequest,
        progress: TimelinePageProgress? = null,
    ): TimelineRemotePageResult {
        val page = TimelineRemotePageAdapter.load(request) { limit, before, after, order ->
            if (before != null) {
                listConversationMessagesBefore(ConversationId(request.scope.conversationId), limit, before, order)
                    ?: throw TimelineRemotePageException.InvalidRequest(
                        "this transport does not implement before-continuation pages",
                    )
            } else {
                listConversationMessages(ConversationId(request.scope.conversationId), limit, after, order)
            }
        }
        return progress?.let { TimelineRemotePageProgressClassifier.classify(request, page, it) } ?: page
    }

    suspend fun listAgentMessages(
        agentId: AgentId,
        limit: Int? = null,
        order: String? = null,
        conversationId: ConversationId? = null,
    ): List<LettaMessage>

    /**
     * letta-mobile-fe51r (P2b pointer diet): fetch the full body of a single
     * tool-return message whose `message.list` representation was projected
     * to a preview. Returns null when the transport never projects list
     * responses (plain HTTP) and therefore has nothing to hydrate.
     */
    suspend fun getToolReturn(
        conversationId: ConversationId,
        messageId: MessageId,
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
