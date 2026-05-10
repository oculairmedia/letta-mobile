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
