package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

class IrohExternalTransportDuplicateIngestTest {
    @Test
    fun duplicate_external_paths_reduce_one_stream_frame_once() = runTest {
        val loop = TimelineSyncLoop(
            messageApi = EmptyTimelineTransport,
            conversationId = "conv-h30cy",
            scope = this,
            pendingLocalStore = NoOpPendingLocalStore,
            conversationCursorStore = NoOpConversationCursorStore,
            startStreamSubscriber = false,
        )
        val message = AssistantMessage(
            id = "cm-stream-h30cy-1",
            contentRaw = JsonPrimitive("A"),
            runId = "run-h30cy",
            otid = "otid-h30cy",
            seqId = 21,
        )

        loop.ingestStreamEvent(message, source = "coordinator")
        loop.ingestStreamEvent(message, source = "fanout")

        val assistantRows = loop.state.value.events
            .filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.ASSISTANT }

        assertEquals(1, assistantRows.size)
        assertEquals("A", assistantRows.single().content)
        loop.close()
    }

    @Test
    fun seq_less_incremental_repeated_tokens_are_not_deduped() = runTest {
        val loop = TimelineSyncLoop(
            messageApi = EmptyTimelineTransport,
            conversationId = "conv-mermaid",
            scope = this,
            pendingLocalStore = NoOpPendingLocalStore,
            conversationCursorStore = NoOpConversationCursorStore,
            startStreamSubscriber = false,
        )
        val tokens = listOf("A", "[", "L", "L", "M", " ", " ", "snapshots", "]")

        tokens.forEach { token ->
            loop.ingestStreamEvent(
                AssistantMessage(
                    id = "reply-mermaid",
                    contentRaw = JsonPrimitive(token),
                    runId = "run-mermaid",
                    otid = "mermaid-otid",
                    seqId = null,
                ),
                source = "coordinator",
            )
        }

        val assistantRows = loop.state.value.events
            .filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.ASSISTANT }

        assertEquals(1, assistantRows.size)
        assertEquals("A[LLM  snapshots]", assistantRows.single().content)
        loop.close()
    }

    private object EmptyTimelineTransport : TimelineTransport {
        override suspend fun sendConversationMessage(
            conversationId: String,
            request: MessageCreateRequest,
        ): Flow<LettaMessage> = emptyFlow()

        override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> = emptyFlow()

        override suspend fun listConversationMessages(
            conversationId: String,
            limit: Int?,
            after: String?,
            order: String?,
        ): List<LettaMessage> = emptyList()

        override suspend fun listAgentMessages(
            agentId: String,
            limit: Int?,
            order: String?,
            conversationId: String?,
        ): List<LettaMessage> = emptyList()
    }
}
