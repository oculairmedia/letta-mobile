package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ReasoningMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

class IrohExternalTransportDuplicateIngestTest {
    @Test
    fun `external frame dedupe scope includes agent only for provisional conversations`() {
        assertEquals("agent-a|default", externalConversationDedupeKey("agent-a", "default"))
        assertEquals("agent-b|default", externalConversationDedupeKey("agent-b", "default"))
        assertEquals(
            "agent-a|conv-default-agent-a",
            externalConversationDedupeKey("agent-a", "conv-default-agent-a"),
        )
        assertEquals("local-conv-176", externalConversationDedupeKey("agent-a", "local-conv-176"))
        assertEquals("local-conv-176", externalConversationDedupeKey("agent-b", "local-conv-176"))
    }

    @Test
    fun repeated_completed_frames_enter_only_one_agent_keyed_holder() = runTest {
        val repository = TimelineRepository(
            EmptyTimelineTransport,
            NoOpPendingLocalStore,
            NoOpConversationCursorStore,
        )
        val reasoning = ReasoningMessage(
            id = "reasoning-shared",
            reasoning = "One thought",
            runId = "run-shared",
            stepId = "step-shared",
            seqId = 40,
        )
        val assistant = AssistantMessage(
            id = "assistant-shared",
            contentRaw = JsonPrimitive("One answer"),
            runId = "run-shared",
            stepId = "step-shared",
            seqId = 41,
        )

        repeat(12) {
            repository.ingestExternalTransportMessage("agent-a", "local-conv-176", reasoning, "fanout")
            repository.ingestExternalTransportMessage("agent-b", "local-conv-176", reasoning, "fanout")
            repository.ingestExternalTransportMessage("agent-a", "local-conv-176", assistant, "fanout")
            repository.ingestExternalTransportMessage("agent-b", "local-conv-176", assistant, "fanout")
        }

        val allRows = listOf("agent-a", "agent-b").flatMap { agentId ->
            repository.observe(agentId, "local-conv-176").value.events
                .filterIsInstance<TimelineEvent.Confirmed>()
        }
        assertEquals(1, allRows.count { it.messageType == TimelineMessageType.REASONING })
        assertEquals(1, allRows.count { it.messageType == TimelineMessageType.ASSISTANT })
        repository.clear("agent-a", "local-conv-176")
        repository.clear("agent-b", "local-conv-176")
    }

    @Test
    fun no_sequence_same_id_growth_is_not_dropped_by_repository_dedupe() = runTest {
        val repository = TimelineRepository(
            EmptyTimelineTransport,
            NoOpPendingLocalStore,
            NoOpConversationCursorStore,
        )

        repository.ingestExternalTransportMessage(
            "agent-a",
            "local-conv-growing",
            AssistantMessage(
                id = "assistant-growing",
                contentRaw = JsonPrimitive("Hel"),
                runId = "run-growing",
                seqId = null,
            ),
            "fanout",
        )
        repository.ingestExternalTransportMessage(
            "agent-a",
            "local-conv-growing",
            AssistantMessage(
                id = "assistant-growing",
                contentRaw = JsonPrimitive("Hello"),
                runId = "run-growing",
                seqId = null,
            ),
            "fanout",
        )

        val assistantRows = repository.observe("agent-a", "local-conv-growing").value.events
            .filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.ASSISTANT }
        assertEquals(1, assistantRows.size)
        assertEquals("Hello", assistantRows.single().content)
        repository.clear("agent-a", "local-conv-growing")
    }

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
