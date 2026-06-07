package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import kotlinx.coroutines.flow.Flow

class MessageApiChatGateway(
    private val conversationApi: ConversationApi,
    messageApi: MessageApi,
) : ChatGateway {
    private val timelineTransport = MessageApiTimelineTransport(messageApi)

    override suspend fun listConversations(limit: Int): List<Conversation> =
        translateConversationApiErrors {
            conversationApi.listConversations(
                limit = limit,
                order = "desc",
                orderBy = "last_message_at",
            )
        }

    override suspend fun getConversation(conversationId: String): Conversation =
        translateConversationApiErrors {
            conversationApi.getConversation(conversationId)
        }

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> =
        timelineTransport.sendConversationMessage(conversationId, request)

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> =
        timelineTransport.streamConversation(conversationId)

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> =
        timelineTransport.listConversationMessages(
            conversationId = conversationId,
            limit = limit,
            after = after,
            order = order,
        )

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> =
        timelineTransport.listAgentMessages(
            agentId = agentId,
            limit = limit,
            order = order,
            conversationId = conversationId,
        )

    private inline fun <T> translateConversationApiErrors(block: () -> T): T =
        try {
            block()
        } catch (e: ApiException) {
            throw TimelineTransportHttpException(
                code = e.code,
                message = e.message ?: "HTTP ${e.code}",
                cause = e,
            )
        }
}
