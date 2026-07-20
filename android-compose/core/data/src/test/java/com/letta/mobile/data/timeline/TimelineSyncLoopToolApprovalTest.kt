package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.api.NoActiveRunException
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.UserMessage
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import io.mockk.mockk
import kotlin.random.Random
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import app.cash.turbine.test
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag
/**
 * Integration-level tests for [TimelineSyncLoop] using a programmable fake API.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class TimelineSyncLoopToolApprovalTest {

    @Test
    fun `hydrate attaches tool_return to its invoking ApprovalRequest`() = runBlocking {
        val api = FakeSyncApi()
        val reqId = "req-1"
        val tcid = "toolu_abc"
        val toolCall = com.letta.mobile.data.model.ToolCall(
            id = tcid, name = "Bash", arguments = "{\"command\":\"echo hi\"}",
        )
        api.addStoredMessage(
            com.letta.mobile.data.model.ApprovalRequestMessage(
                id = reqId, toolCalls = persistentListOf(toolCall),
            )
        )
        api.addStoredMessage(
            com.letta.mobile.data.model.ToolReturnMessage(
                id = "ret-1",
                toolCallId = tcid,
                toolReturnRaw = JsonPrimitive("hi\n"),
            )
        )

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)
        sync.hydrate()

        val events = sync.state.value.events.filterIsInstance<TimelineEvent.Confirmed>()
        // The tool_return should NOT appear as a standalone event.
        assertEquals(
            "Expected exactly one bubble (the TOOL_CALL), not a separate TOOL_RETURN",
            1, events.size,
        )
        val bubble = events.single()
        assertEquals(TimelineMessageType.TOOL_CALL, bubble.messageType)
        assertEquals("hi\n", bubble.toolReturnContent)
        assertTrue("approvalDecided must be true once return observed", bubble.approvalDecided)

        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `hydrate attaches batched tool returns by tool call id`() = runBlocking {
        val api = FakeSyncApi()
        val callA = com.letta.mobile.data.model.ToolCall(
            id = "toolu_a", name = "Bash", arguments = "{\"command\":\"a\"}",
        )
        val callB = com.letta.mobile.data.model.ToolCall(
            id = "toolu_b", name = "Bash", arguments = "{\"command\":\"b\"}",
        )
        api.addStoredMessage(
            com.letta.mobile.data.model.ApprovalRequestMessage(
                id = "req-batch",
                toolCalls = persistentListOf(callA, callB),
            )
        )
        api.addStoredMessage(
            com.letta.mobile.data.model.ToolReturnMessage(
                id = "ret-a",
                toolCallId = "toolu_a",
                toolReturnRaw = JsonPrimitive("a-output"),
            )
        )
        api.addStoredMessage(
            com.letta.mobile.data.model.ToolReturnMessage(
                id = "ret-b",
                toolCallId = "toolu_b",
                toolReturnRaw = JsonPrimitive("b-output"),
                status = "error",
            )
        )

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-batch", scope)
        sync.hydrate()

        val bubble = sync.state.value.events
            .filterIsInstance<TimelineEvent.Confirmed>()
            .single { it.messageType == TimelineMessageType.TOOL_CALL }
        assertEquals("a-output", bubble.toolReturnContentByCallId["toolu_a"])
        assertEquals("b-output", bubble.toolReturnContentByCallId["toolu_b"])
        assertEquals(false, bubble.toolReturnIsErrorByCallId["toolu_a"])
        assertEquals(true, bubble.toolReturnIsErrorByCallId["toolu_b"])
        assertEquals("a-output", bubble.toolReturnContent)
        assertTrue(bubble.approvalDecided)

        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `streamed tool_return attaches to tool_call event and flips approvalDecided`() = runTest {
        val api = FakeSyncApi()
        val reqId = "req-2"
        val tcid = "toolu_xyz"
        val toolCall = com.letta.mobile.data.model.ToolCall(
            id = tcid, name = "Bash", arguments = "{\"command\":\"ls\"}",
        )
        // Seed the store with a user message so send has something to build from.
        api.nextStreamMessages = listOf(
            com.letta.mobile.data.model.ApprovalRequestMessage(
                id = reqId, toolCalls = persistentListOf(toolCall),
            ),
            com.letta.mobile.data.model.ToolReturnMessage(
                id = "ret-2",
                toolCallId = tcid,
                toolReturnRaw = JsonPrimitive("file_a\nfile_b\n"),
            ),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

        val send = async { sync.send("list files") }
        advanceUntilIdle()
        send.await()

        val confirmed = sync.state.value.events.filterIsInstance<TimelineEvent.Confirmed>()
        val bubbleByType = confirmed.groupBy { it.messageType }
        assertFalse(
            "Standalone TOOL_RETURN event leaked through into the timeline",
            bubbleByType.containsKey(TimelineMessageType.TOOL_RETURN),
        )
        val toolCallEvent = bubbleByType[TimelineMessageType.TOOL_CALL]?.single()
        assertNotNull(toolCallEvent)
        assertEquals("file_a\nfile_b\n", toolCallEvent!!.toolReturnContent)
        assertTrue(toolCallEvent.approvalDecided)

        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `streamed tool_return arriving before tool_call attaches when call lands`() = runBlocking {
        val api = FakeSyncApi()
        val job = kotlinx.coroutines.Job()
        val scope = CoroutineScope(Dispatchers.Unconfined + job)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)
        val toolCallId = "toolu_early_return"

        try {
            sync.ingestStreamEvent(
                com.letta.mobile.data.model.ToolReturnMessage(
                    id = "return-before-call",
                    toolCallId = toolCallId,
                    toolReturnRaw = JsonPrimitive("early output"),
                    status = "success",
                    runId = "run-early",
                )
            )

            assertTrue(
                "tool_return should not render as a standalone event",
                sync.state.value.events.none {
                    it is TimelineEvent.Confirmed && it.messageType == TimelineMessageType.TOOL_RETURN
                },
            )

            sync.ingestStreamEvent(
                com.letta.mobile.data.model.ApprovalRequestMessage(
                    id = "call-after-return",
                    runId = "run-early",
                    toolCall = com.letta.mobile.data.model.ToolCall(
                        id = toolCallId,
                        name = "Bash",
                        arguments = "{\"command\":\"echo early\"}",
                    ),
                )
            )

            val toolCallEvent = sync.state.value.events
                .filterIsInstance<TimelineEvent.Confirmed>()
                .single { it.messageType == TimelineMessageType.TOOL_CALL }
            assertEquals("early output", toolCallEvent.toolReturnContent)
            assertEquals("early output", toolCallEvent.toolReturnContentByCallId[toolCallId])
            assertTrue(toolCallEvent.approvalDecided)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `tool_return with short discriminator is still dispatched via SseParser`() = runBlocking {
        // letta-mobile-mge5.18 regression guard: SSE uses message_type="tool_return"
        // (without _message). LettaMessageSerializer must accept both forms.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val short = """{"message_type":"tool_return","id":"r1","tool_call_id":"tc1","tool_return":"done"}"""
        val parsed = json.decodeFromString(
            com.letta.mobile.data.model.LettaMessage.serializer(), short,
        )
        assertTrue(
            "short-form discriminator must decode to ToolReturnMessage; got ${parsed::class.simpleName}",
            parsed is com.letta.mobile.data.model.ToolReturnMessage,
        )
        val long = """{"message_type":"tool_return_message","id":"r2","tool_call_id":"tc2","tool_return":"ok"}"""
        val parsedLong = json.decodeFromString(
            com.letta.mobile.data.model.LettaMessage.serializer(), long,
        )
        assertTrue(parsedLong is com.letta.mobile.data.model.ToolReturnMessage)
    }

    @Test
    fun `approval_response with short discriminator is dispatched via SseParser`() = runBlocking {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val short = """{"message_type":"approval_response","id":"x","approval_request_id":"req-1","approve":true}"""
        val parsed = json.decodeFromString(
            com.letta.mobile.data.model.LettaMessage.serializer(), short,
        )
        assertTrue(
            "short form must decode to ApprovalResponseMessage; got ${parsed::class.simpleName}",
            parsed is com.letta.mobile.data.model.ApprovalResponseMessage,
        )
    }

    @Test
    fun `reasoning short and long discriminators both decode to ReasoningMessage`() = runBlocking {
        // letta-mobile-mge5.22: SSE emits "reasoning"; REST emits
        // "reasoning_message". Both must land in ReasoningMessage or inner
        // thoughts disappear from the timeline.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val short = """{"message_type":"reasoning","id":"r1","reasoning":"thinking..."}"""
        val long = """{"message_type":"reasoning_message","id":"r2","reasoning":"thinking again"}"""
        listOf(short, long).forEach { frame ->
            val parsed = json.decodeFromString(
                com.letta.mobile.data.model.LettaMessage.serializer(), frame,
            )
            assertTrue(
                "Frame $frame decoded to ${parsed::class.simpleName} — expected ReasoningMessage",
                parsed is com.letta.mobile.data.model.ReasoningMessage,
            )
        }
    }

    @Test
    fun `all known discriminator pairs decode as expected`() = runBlocking {
        // Regression guard for mge5.16/18/22 — whenever the server adds a
        // new short discriminator we should catch it here first before the
        // UI silently drops messages.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        data class Case(val frame: String, val expected: Class<*>)
        val cases = listOf(
            Case("""{"message_type":"user_message","id":"u","content":"hi"}""",
                com.letta.mobile.data.model.UserMessage::class.java),
            Case("""{"message_type":"assistant_message","id":"a","content":"ok"}""",
                com.letta.mobile.data.model.AssistantMessage::class.java),
            Case("""{"message_type":"system_message","id":"s","content":"sys"}""",
                com.letta.mobile.data.model.SystemMessage::class.java),
            Case("""{"message_type":"reasoning","id":"r","reasoning":"t"}""",
                com.letta.mobile.data.model.ReasoningMessage::class.java),
            Case("""{"message_type":"reasoning_message","id":"r","reasoning":"t"}""",
                com.letta.mobile.data.model.ReasoningMessage::class.java),
            Case("""{"message_type":"hidden_reasoning","id":"h","state":"redacted"}""",
                com.letta.mobile.data.model.HiddenReasoningMessage::class.java),
            Case("""{"message_type":"hidden_reasoning_message","id":"h","state":"redacted"}""",
                com.letta.mobile.data.model.HiddenReasoningMessage::class.java),
            Case("""{"message_type":"tool_call","id":"tc"}""",
                com.letta.mobile.data.model.ToolCallMessage::class.java),
            Case("""{"message_type":"tool_call_message","id":"tc"}""",
                com.letta.mobile.data.model.ToolCallMessage::class.java),
            Case("""{"message_type":"tool_return","id":"tr","tool_call_id":"x","tool_return":"r"}""",
                com.letta.mobile.data.model.ToolReturnMessage::class.java),
            Case("""{"message_type":"tool_return_message","id":"tr","tool_call_id":"x","tool_return":"r"}""",
                com.letta.mobile.data.model.ToolReturnMessage::class.java),
            Case("""{"message_type":"approval_request","id":"ar"}""",
                com.letta.mobile.data.model.ApprovalRequestMessage::class.java),
            Case("""{"message_type":"approval_request_message","id":"ar"}""",
                com.letta.mobile.data.model.ApprovalRequestMessage::class.java),
            Case("""{"message_type":"approval_response","id":"ap","approval_request_id":"r","approve":true}""",
                com.letta.mobile.data.model.ApprovalResponseMessage::class.java),
            Case("""{"message_type":"approval_response_message","id":"ap","approval_request_id":"r","approve":true}""",
                com.letta.mobile.data.model.ApprovalResponseMessage::class.java),
            Case("""{"message_type":"stop_reason","stop_reason":"end_turn"}""",
                com.letta.mobile.data.model.StopReason::class.java),
        )
        cases.forEach { (frame, expected) ->
            val parsed = json.decodeFromString(
                com.letta.mobile.data.model.LettaMessage.serializer(), frame,
            )
            assertTrue(
                "Frame $frame decoded to ${parsed::class.simpleName}; expected ${expected.simpleName}",
                expected.isInstance(parsed),
            )
        }
    }

    @Test
    fun `streamed approval_request followed by tool_return preserves output on later delta frame`() = runBlocking {
        // letta-mobile-mge5.23: repro of the "approve/reject still visible
        // + no output" symptom. Sequence of SSE frames observed in practice:
        //   1. approval_request (streaming delta 1) with empty args
        //   2. approval_request (streaming delta 2) with populated args
        //   3. tool_return_message with the output
        //   4. approval_request (final settling delta) with the same
        //      server id but potentially empty fields — if this overwrites
        //      the already-attached toolReturnContent, the UI regresses to
        //      empty-output / approve-reject-visible state.
        // The merge path in ingestStreamEvent must preserve existing
        // toolReturnContent/approvalDecided/toolCalls on such deltas.
        val api = FakeSyncApi()
        val job = kotlinx.coroutines.Job()
        val loop = TimelineSyncLoop(
            conversationId = "conv-x",
            messageApi = MessageApiTimelineTransport(api),
            scope = CoroutineScope(Dispatchers.Unconfined + job),
        )
        try {
        loop.hydrate(limit = 50)

        val approvalBase = com.letta.mobile.data.model.ApprovalRequestMessage(
            id = "msg-ar-1",
            runId = "run-1",
            otid = "otid-ar-1",
            toolCall = com.letta.mobile.data.model.ToolCall(
                id = "toolu_xyz", name = "Bash", arguments = "{\"command\":\"echo ok\"}",
            ),
        )
        // Frame 1: populated call
        loop.ingestStreamEvent(approvalBase)
        // Frame 2: tool_return for the call
        val toolReturn = com.letta.mobile.data.model.ToolReturnMessage(
            id = "msg-tr-1",
            toolCallId = "toolu_xyz",
            toolReturnRaw = kotlinx.serialization.json.JsonPrimitive("ok\n"),
            status = "success",
            runId = "run-1",
        )
        loop.ingestStreamEvent(toolReturn)
        // Frame 3: settling delta with the same server id but blank args
        val stale = com.letta.mobile.data.model.ApprovalRequestMessage(
            id = "msg-ar-1",
            runId = "run-1",
            otid = "otid-ar-1",
            toolCall = com.letta.mobile.data.model.ToolCall(
                id = "toolu_xyz", name = "Bash", arguments = null,
            ),
        )
        loop.ingestStreamEvent(stale)

        val final = loop.state.value.events
            .filterIsInstance<TimelineEvent.Confirmed>()
            .firstOrNull { it.serverId == "msg-ar-1" }
        assertNotNull("call event must still exist after settling delta", final)
        assertTrue("approvalDecided must survive the settling delta", final!!.approvalDecided)
        assertEquals("toolReturnContent must survive the settling delta", "ok\n", final.toolReturnContent)
        // args should have been preserved from the populated earlier frame
        val firstCall = final.toolCalls.firstOrNull()
        assertNotNull(firstCall)
        assertEquals("{\"command\":\"echo ok\"}", firstCall!!.arguments)
        } finally {
            job.cancel()
        }
    }

    /**
     * letta-mobile-gqz3 regression test (updated for letta-mobile-t8q7).
     *
     * Original gqz3 bug: the subscriber's `ApiException` catch branch only
     * treated `"No active runs"` as an idle pattern. When a previously-
     * active run aged out, the server returned
     * `EXPIRED: Run was created more than 3 hours ago, and is now expired.`
     * which the subscriber mis-classified as a generic network error,
     * wedging at the backoff cap and never opening a fresh stream.
     *
     * Post-t8q7: idle-pattern classification (including EXPIRED) now lives
     * in `MessageApi.streamConversation` and is signalled by the stackless
     * `NoActiveRunException`. The subscriber's ApiException branch only
     * handles real network/server errors. This contract test verifies
     * EXPIRED still reaches the idle path — it just goes through the
     * stackless exception now.
     *
     * Assertion: observing EXPIRED must emit `streamSubscriber.idle404`
     * and must NOT emit `streamSubscriber.error` or `streamSubscriber.networkError`.
     */
}
