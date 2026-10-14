package com.letta.mobile.testutil

import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.unavailable
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaResponse
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.repository.api.MessageRemoteSource
import io.ktor.utils.io.ByteReadChannel

/**
 * Base for the message test doubles: a [MessageRemoteSource] plus the SSE send / stream surface the
 * timeline tests drive through `MessageApiTimelineTransport`. letta-mobile-ckmub.4 removed the
 * production Letta REST `MessageApi`; this keeps its shape for the timeline engine tests only.
 * Every call fails unless a test overrides it.
 */
open class TestMessageSource : MessageRemoteSource by unavailable<MessageRemoteSource>(SessionRoute.CONVERSATIONS) {
    open suspend fun sendConversationMessage(conversationId: ConversationId, request: MessageCreateRequest): ByteReadChannel =
        notStubbed()

    open suspend fun sendConversationMessageNoStream(conversationId: ConversationId, request: MessageCreateRequest): LettaResponse =
        notStubbed()

    open suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel = notStubbed()

    private fun notStubbed(): Nothing = throw UnsupportedOperationException("not stubbed by this test")
}
