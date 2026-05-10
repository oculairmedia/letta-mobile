package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import io.ktor.utils.io.ByteReadChannel
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Focused characterization of TimelineSyncLoop's event-folding semantics before
 * routing all Client Mode stream chunks through TimelineRepository.
 */
@Tag("integration")
class TimelineReducerCharacterizationTest {

    @Test
    fun `assistant deltas with same server id append into one confirmed event`() = withLoop { loop ->
        loop.ingestStreamEvent(AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("Hel")))
        loop.ingestStreamEvent(AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("lo")))

        val event = loop.state.value.events.single() as TimelineEvent.Confirmed
        assertEquals(TimelineMessageType.ASSISTANT, event.messageType)
        assertEquals("Hello", event.content)
    }

    @Test
    fun `reasoning deltas with same server id append into one confirmed event`() = withLoop { loop ->
        loop.ingestStreamEvent(ReasoningMessage(id = "reason-1", reasoning = "Think"))
        loop.ingestStreamEvent(ReasoningMessage(id = "reason-1", reasoning = "ing"))

        val event = loop.state.value.events.single() as TimelineEvent.Confirmed
        assertEquals(TimelineMessageType.REASONING, event.messageType)
        assertEquals("Thinking", event.content)
    }

    @Test
    fun `tool call snapshots merge arguments instead of replacing with blank deltas`() = withLoop { loop ->
        loop.ingestStreamEvent(
            ToolCallMessage(
                id = "tool-step-1",
                toolCall = ToolCall(toolCallId = "call-1", name = "shell", arguments = ""),
            )
        )
        loop.ingestStreamEvent(
            ToolCallMessage(
                id = "tool-step-1",
                toolCall = ToolCall(toolCallId = "call-1", name = "shell", arguments = "{\"cmd\":\"ls\"}"),
            )
        )
        loop.ingestStreamEvent(
            ToolCallMessage(
                id = "tool-step-1",
                toolCall = ToolCall(toolCallId = "call-1", name = "shell", arguments = ""),
            )
        )

        val event = loop.state.value.events.single() as TimelineEvent.Confirmed
        assertEquals(TimelineMessageType.TOOL_CALL, event.messageType)
        assertEquals("{\"cmd\":\"ls\"}", event.toolCalls.single().arguments)
    }

    @Test
    fun `batched tool calls stay in one event and individual tool returns attach by call id`() = withLoop { loop ->
        loop.ingestStreamEvent(
            ToolCallMessage(
                id = "tool-batch",
                toolCalls = listOf(
                    ToolCall(toolCallId = "call-a", name = "read", arguments = "a"),
                    ToolCall(toolCallId = "call-b", name = "write", arguments = "b"),
                ),
            )
        )
        loop.ingestStreamEvent(
            ToolReturnMessage(
                id = "return-b",
                toolCallId = "call-b",
                status = "success",
                toolReturnRaw = JsonPrimitive("done"),
            )
        )

        val event = loop.state.value.events.single() as TimelineEvent.Confirmed
        assertEquals(listOf("call-a", "call-b"), event.toolCalls.map { it.effectiveId })
        assertEquals("done", event.toolReturnContentByCallId["call-b"])
        assertEquals(null, event.toolReturnContentByCallId["call-a"])
        assertTrue(event.approvalDecided)
    }

    @Test
    fun `tool return arriving before tool call is buffered and attached when call lands`() = withLoop { loop ->
        loop.ingestStreamEvent(
            ToolReturnMessage(
                id = "return-first",
                toolCallId = "call-late",
                status = "success",
                toolReturnRaw = JsonPrimitive("late result"),
            )
        )
        assertEquals("tool returns never render standalone", 0, loop.state.value.events.size)

        loop.ingestStreamEvent(
            ToolCallMessage(
                id = "tool-late",
                toolCall = ToolCall(toolCallId = "call-late", name = "lookup", arguments = "{}"),
            )
        )

        val event = loop.state.value.events.single() as TimelineEvent.Confirmed
        assertEquals("late result", event.toolReturnContentByCallId["call-late"])
        assertEquals("late result", event.toolReturnContent)
        assertTrue(event.approvalDecided)
    }

    @Test
    fun `approval request and response fold into one decided tool call event`() = withLoop { loop ->
        loop.ingestStreamEvent(
            ApprovalRequestMessage(
                id = "approval-1",
                toolCall = ToolCall(toolCallId = "call-approval", name = "danger", arguments = "{}"),
            )
        )
        loop.ingestStreamEvent(
            ApprovalResponseMessage(
                id = "approval-response-1",
                approvalRequestId = "approval-1",
                approve = true,
            )
        )

        val event = loop.state.value.events.single() as TimelineEvent.Confirmed
        assertEquals("approval-1", event.approvalRequestId)
        assertTrue(event.approvalDecided)
    }

    @Test
    fun `client mode stream chunks reduce into timeline locals`() = withLoop { loop ->
        loop.upsertClientModeStreamChunk(
            chunk = ClientModeStreamChunk(text = "Hel", event = ClientModeStreamEvent.ASSISTANT),
            assistantMessageId = "assistant-1",
        )
        loop.upsertClientModeStreamChunk(
            chunk = ClientModeStreamChunk(text = "lo", event = ClientModeStreamEvent.ASSISTANT),
            assistantMessageId = "assistant-1",
        )
        loop.upsertClientModeStreamChunk(
            chunk = ClientModeStreamChunk(text = "Think", event = ClientModeStreamEvent.REASONING, uuid = "reason-1"),
            assistantMessageId = "assistant-1",
        )
        loop.upsertClientModeStreamChunk(
            chunk = ClientModeStreamChunk(text = "ing", event = ClientModeStreamEvent.REASONING, uuid = "reason-1"),
            assistantMessageId = "assistant-1",
        )

        val assistant = loop.state.value.events.first { it.otid == "cm-assist-assistant-1" } as TimelineEvent.Local
        val reasoning = loop.state.value.events.first { it.otid == "cm-reason-reason-1" } as TimelineEvent.Local
        assertEquals("Hello", assistant.content)
        assertEquals("Thinking", reasoning.reasoningContent)
    }

    @Test
    fun `client mode batched tool results update original timeline local`() = withLoop { loop ->
        val startedAt = java.time.Instant.parse("2026-05-10T12:00:00Z")
        val callBCompletedAt = startedAt.plusMillis(250)
        loop.upsertClientModeStreamChunk(
            chunk = ClientModeStreamChunk(
                event = ClientModeStreamEvent.TOOL_CALL,
                toolCallId = "batch-1",
                toolCalls = listOf(
                    ToolCall(toolCallId = "call-a", name = "read", arguments = "a"),
                    ToolCall(toolCallId = "call-b", name = "write", arguments = "b"),
                ),
            ),
            assistantMessageId = "assistant-1",
            sentAt = startedAt,
        )
        loop.upsertClientModeStreamChunk(
            chunk = ClientModeStreamChunk(
                event = ClientModeStreamEvent.TOOL_RESULT,
                toolCallId = "call-b",
                text = "done",
            ),
            assistantMessageId = "assistant-1",
            sentAt = callBCompletedAt,
        )

        val tool = loop.state.value.events.single() as TimelineEvent.Local
        assertEquals("cm-tool-batch-1", tool.otid)
        assertEquals(listOf("call-a", "call-b"), tool.toolCalls.map { it.effectiveId })
        assertEquals("done", tool.toolReturnContentByCallId["call-b"])
        assertEquals(null, tool.toolReturnContentByCallId["call-a"])
        assertEquals("batch-1", tool.toolBatchIdByCallId["call-a"])
        assertEquals("batch-1", tool.toolBatchIdByCallId["call-b"])
        assertEquals(startedAt, tool.toolStartedAtByCallId["call-a"])
        assertEquals(startedAt, tool.toolStartedAtByCallId["call-b"])
        assertEquals(callBCompletedAt, tool.toolCompletedAtByCallId["call-b"])
    }

    @Test
    fun `client mode tool result before batched call folds into final batch local`() = withLoop { loop ->
        val resultAt = java.time.Instant.parse("2026-05-10T12:00:00Z")
        val batchAt = resultAt.plusMillis(100)
        loop.upsertClientModeStreamChunk(
            chunk = ClientModeStreamChunk(
                event = ClientModeStreamEvent.TOOL_RESULT,
                toolCallId = "call-b",
                text = "early result",
            ),
            assistantMessageId = "assistant-1",
            sentAt = resultAt,
        )
        assertEquals("early result", (loop.state.value.events.single() as TimelineEvent.Local).toolReturnContentByCallId["call-b"])

        loop.upsertClientModeStreamChunk(
            chunk = ClientModeStreamChunk(
                event = ClientModeStreamEvent.TOOL_CALL,
                toolCallId = "batch-1",
                toolCalls = listOf(
                    ToolCall(toolCallId = "call-a", name = "read", arguments = "a"),
                    ToolCall(toolCallId = "call-b", name = "write", arguments = "b"),
                ),
            ),
            assistantMessageId = "assistant-1",
            sentAt = batchAt,
        )

        val tool = loop.state.value.events.single() as TimelineEvent.Local
        assertEquals("cm-tool-batch-1", tool.otid)
        assertEquals(listOf("call-a", "call-b"), tool.toolCalls.map { it.effectiveId })
        assertEquals("early result", tool.toolReturnContentByCallId["call-b"])
        assertEquals(resultAt, tool.toolCompletedAtByCallId["call-b"])
        assertEquals("batch-1", tool.toolBatchIdByCallId["call-b"])
    }

    @Test
    fun `client mode user local is fuzzy-collapsed when matching server echo arrives`() = withLoop { loop ->
        loop.appendClientModeLocal(content = "hello from client mode")

        loop.ingestStreamEvent(UserMessage(id = "server-user-client-mode", contentRaw = JsonPrimitive("hello from client mode")))

        val event = loop.state.value.events.single() as TimelineEvent.Confirmed
        assertEquals(TimelineMessageType.USER, event.messageType)
        assertEquals(MessageSource.CLIENT_MODE_HARNESS, event.source)
        assertEquals("hello from client mode", event.content)
    }

    @Test
    fun `client mode local stream is replay-collapsed when matching server event arrives`() = withLoop { loop ->
        loop.upsertClientModeLocalAssistantChunk(
            localId = "cm-assist-1",
            build = {
                TimelineEvent.Local(
                    position = 0.0,
                    otid = "cm-assist-1",
                    content = "final answer",
                    role = Role.ASSISTANT,
                    sentAt = java.time.Instant.now(),
                    deliveryState = DeliveryState.SENT,
                    source = MessageSource.CLIENT_MODE_HARNESS,
                    messageType = TimelineMessageType.ASSISTANT,
                )
            },
            transform = { it.copy(content = it.content + " ignored") },
        )

        loop.ingestStreamEvent(AssistantMessage(id = "server-assist-1", contentRaw = JsonPrimitive("final answer")))

        val event = loop.state.value.events.single() as TimelineEvent.Confirmed
        assertEquals("cm-assist-1", event.otid)
        assertEquals(MessageSource.CLIENT_MODE_HARNESS, event.source)
        assertEquals("final answer", event.content)
    }

    @Test
    fun `client mode post-tool assistant prose does not mutate pre-tool preamble`() = withLoop { loop ->
        loop.upsertClientModeStreamChunk(
            chunk = ClientModeStreamChunk(
                event = ClientModeStreamEvent.ASSISTANT,
                text = "I'll create the bead first.\n\n",
            ),
            assistantMessageId = "assistant-1",
        )
        loop.upsertClientModeStreamChunk(
            chunk = ClientModeStreamChunk(
                event = ClientModeStreamEvent.TOOL_CALL,
                toolCallId = "call-bd-create",
                toolCalls = listOf(ToolCall(toolCallId = "call-bd-create", name = "Bash", arguments = "bd create")),
            ),
            assistantMessageId = "assistant-1",
        )
        loop.upsertClientModeStreamChunk(
            chunk = ClientModeStreamChunk(
                event = ClientModeStreamEvent.TOOL_RESULT,
                toolCallId = "call-bd-create",
                text = "created letta-mobile-bunm",
            ),
            assistantMessageId = "assistant-1",
        )
        loop.upsertClientModeStreamChunk(
            chunk = ClientModeStreamChunk(
                event = ClientModeStreamEvent.ASSISTANT,
                text = "Filed it: letta-mobile-bunm\n\nMatrix heartbeat creates fresh conversations.",
            ),
            assistantMessageId = "assistant-1",
        )

        loop.ingestStreamEvent(
            AssistantMessage(
                id = "server-assist-final",
                contentRaw = JsonPrimitive("Filed it: letta-mobile-bunm\n\nMatrix heartbeat creates fresh conversations."),
            )
        )

        val events = loop.state.value.events
        val preamble = events.first { it.otid == "cm-assist-assistant-1" } as TimelineEvent.Local
        val tool = events.first { it.otid == "cm-tool-call-bd-create" } as TimelineEvent.Local
        val final = events.single {
            it is TimelineEvent.Confirmed && it.messageType == TimelineMessageType.ASSISTANT
        } as TimelineEvent.Confirmed

        assertEquals("I'll create the bead first.\n\n", preamble.content)
        assertEquals(TimelineMessageType.TOOL_CALL, tool.messageType)
        assertEquals("Filed it: letta-mobile-bunm\n\nMatrix heartbeat creates fresh conversations.", final.content)
        assertTrue(
            "The post-tool local should fuzzy-collapse into the server final instead of leaving a second final prose bubble",
            events.none { it is TimelineEvent.Local && it.otid == "cm-assist-assistant-1-after-tool-1" },
        )
    }

    @Test
    fun `client mode reasoning local is fuzzy-collapsed when matching server reasoning arrives`() = withLoop { loop ->
        loop.upsertClientModeStreamChunk(
            chunk = ClientModeStreamChunk(
                event = ClientModeStreamEvent.REASONING,
                uuid = "reason-client-mode",
                text = "thinking out loud",
            ),
            assistantMessageId = "assistant-1",
        )

        loop.ingestStreamEvent(ReasoningMessage(id = "server-reason-client-mode", reasoning = "thinking out loud"))

        val event = loop.state.value.events.single() as TimelineEvent.Confirmed
        assertEquals(TimelineMessageType.REASONING, event.messageType)
        assertEquals(MessageSource.CLIENT_MODE_HARNESS, event.source)
        assertEquals("thinking out loud", event.content)
    }

    @Test
    fun `stream event with already known otid is dropped as REST-SSE duplicate`() = withLoop { loop ->
        loop.ingestStreamEvent(UserMessage(id = "server-user-1", contentRaw = JsonPrimitive("hello"), otid = "shared-otid"))
        loop.ingestStreamEvent(UserMessage(id = "server-user-2", contentRaw = JsonPrimitive("hello"), otid = "shared-otid"))

        val event = loop.state.value.events.single() as TimelineEvent.Confirmed
        assertEquals("server-user-1", event.serverId)
        assertEquals("shared-otid", event.otid)
    }
}

private fun withLoop(block: suspend (TimelineSyncLoop) -> Unit) = runBlocking {
    val scope = CoroutineScope(Dispatchers.Unconfined)
    val loop = TimelineSyncLoop(NoStreamMessageApi(), "conv-characterization", scope)
    try {
        block(loop)
    } finally {
        scope.coroutineContext.job.cancel()
    }
}

private class NoStreamMessageApi : MessageApi(mockk(relaxed = true)) {
    override suspend fun streamConversation(conversationId: String): ByteReadChannel {
        awaitCancellation()
    }

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = emptyList()

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): ByteReadChannel = ByteReadChannel("data: [DONE]\n\n".toByteArray())
}
