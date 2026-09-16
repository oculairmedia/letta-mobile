package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GenerationTimelineTransportTest {
    @Test fun retirementCancelsAcceptedRpcAndRejectsLaterRpc() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val remote = object : TimelineTransport {
            override suspend fun listConversationMessages(conversationId: String, limit: Int?, after: String?, order: String?): List<LettaMessage> {
                entered.complete(Unit)
                try { CompletableDeferred<Unit>().await() } finally { cancelled.complete(Unit) }
                return emptyList()
            }
            override suspend fun listAgentMessages(agentId: String, limit: Int?, order: String?, conversationId: String?) = emptyList<LettaMessage>()
            override suspend fun sendConversationMessage(conversationId: String, request: MessageCreateRequest): Flow<LettaMessage> = error("unused")
            override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> = error("unused")
        }
        val transport = GenerationTimelineTransport(remote, this)
        val rpc = async { transport.listConversationMessages("conversation") }
        entered.await()
        transport.retire()
        assertTrue(cancelled.isCompleted)
        assertFailsWith<CancellationException> { rpc.await() }
        assertFailsWith<CancellationException> { transport.listConversationMessages("conversation") }
    }
}
