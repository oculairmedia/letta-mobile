package com.letta.mobile.testutil

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaResponse
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.MessageSearchResult
import com.letta.mobile.data.repository.api.MessageRemoteSource
import io.ktor.utils.io.ByteReadChannel

/**
 * Base for the message test doubles: a [MessageRemoteSource] plus the SSE send / stream surface the
 * timeline tests drive through `MessageApiTimelineTransport`. letta-mobile-ckmub.4 removed the
 * production Letta REST `MessageApi`; this keeps its shape for the timeline engine tests only.
 * Every call fails unless a test overrides it.
 */
open class TestMessageSource : MessageRemoteSource {
    open suspend fun sendConversationMessage(conversationId: ConversationId, request: MessageCreateRequest): ByteReadChannel =
        unsupported("sendConversationMessage")

    suspend fun sendConversationMessage(conversationId: String, request: MessageCreateRequest): ByteReadChannel =
        sendConversationMessage(ConversationId(conversationId), request)

    open suspend fun sendConversationMessageNoStream(conversationId: ConversationId, request: MessageCreateRequest): LettaResponse =
        unsupported("sendConversationMessageNoStream")

    open suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel = unsupported("streamConversation")

    suspend fun streamConversation(conversationId: String): ByteReadChannel = streamConversation(ConversationId(conversationId))

    override suspend fun fetchRecentMessages(
        conversationId: ConversationId,
        messageLimit: Int,
        beforeMessageId: String?,
    ): List<LettaMessage> = unsupported("fetchRecentMessages")

    override suspend fun listMessages(
        agentId: AgentId,
        limit: Int?,
        before: String?,
        after: String?,
        order: String?,
        conversationId: ConversationId?,
    ): List<LettaMessage> = unsupported("listMessages")

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = unsupported("listConversationMessages")

    override suspend fun sendMessage(agentId: AgentId, request: MessageCreateRequest): LettaResponse =
        unsupported("sendMessage")

    override suspend fun cancelMessage(agentId: AgentId, runIds: List<String>?): Map<String, String> =
        unsupported("cancelMessage")

    override suspend fun searchMessages(request: MessageSearchRequest): List<MessageSearchResult> =
        unsupported("searchMessages")

    override suspend fun resetMessages(agentId: AgentId): Unit = unsupported("resetMessages")

    private fun unsupported(operation: String): Nothing =
        throw UnsupportedOperationException("TestMessageSource.$operation is not stubbed by this test")
}
