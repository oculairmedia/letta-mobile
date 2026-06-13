package com.letta.mobile.runtime.local

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LettaCodeStreamJsonMapperTest {
    private val mapper = LettaCodeStreamJsonMapper()

    @Test
    fun `maps lettacode message frame to runtime stream frame`() {
        val drafts = mapper.mapLine(
            """{"type":"message","id":"msg-1","message_type":"assistant_message","content":[{"type":"text","text":"hello"}],"run_id":"run-1"}""",
            command(),
        )

        val payload = drafts.single().payload as RuntimeEventPayload.RemoteStreamFrame
        assertEquals("msg-1", payload.frameId)
        assertEquals("assistant_message", payload.messageType)
        assertEquals("hello", payload.body)
        assertEquals("run-1", drafts.single().runId?.value)
    }

    @Test
    fun `streamed deltas with the same otid share one message id`() {
        val chunks = listOf(
            """{"type":"message","id":"letta-msg-126","message_type":"assistant_message","otid":"provider-assistant-1-uuid","content":[{"type":"text","text":"Hey"}],"run_id":"local-run-2","seq_id":17}""",
            """{"type":"message","id":"letta-msg-127","message_type":"assistant_message","otid":"provider-assistant-1-uuid","content":[{"type":"text","text":"!"}],"run_id":"local-run-2","seq_id":18}""",
        ).flatMap { mapper.mapLine(it, command()) }

        val payloads = chunks.map { it.payload as RuntimeEventPayload.RemoteStreamFrame }
        assertEquals(listOf("letta-msg-126", "letta-msg-127"), payloads.map { it.frameId })
        assertEquals(
            "deltas must merge into one timeline message, not one bubble per chunk",
            listOf("provider-assistant-1-uuid", "provider-assistant-1-uuid"),
            payloads.map { it.messageId },
        )
        assertEquals(listOf("Hey", "!"), payloads.map { it.body })
    }

    @Test
    fun `preserves stream event wrapper metadata`() {
        val drafts = mapper.mapLine(
            """{"type":"stream_event","run_id":"run-wrapper","event":{"type":"message","id":"msg-1","message_type":"assistant_message","content":"hello"}}""",
            command(),
        )

        val payload = drafts.single().payload as RuntimeEventPayload.RemoteStreamFrame
        assertEquals("msg-1", payload.frameId)
        assertEquals("hello", payload.body)
        assertEquals("run-wrapper", drafts.single().runId?.value)
    }

    @Test
    fun `preserves stream event wrapper metadata on lifecycle frames`() {
        val drafts = mapper.mapLine(
            """{"type":"stream_event","run_id":"run-wrapper","event":{"type":"result","subtype":"success","result":"done"}}""",
            command(),
        )

        val payload = drafts.single().payload as RuntimeEventPayload.RunLifecycleChanged
        assertEquals(RuntimeRunStatus.Completed, payload.status)
        assertEquals("run-wrapper", drafts.single().runId?.value)
    }

    @Test
    fun `maps result frame to completed lifecycle`() {
        val drafts = mapper.mapLine(
            """{"type":"result","subtype":"success","result":"done"}""",
            command(),
        )

        val payload = drafts.single().payload as RuntimeEventPayload.RunLifecycleChanged
        assertEquals(RuntimeRunStatus.Completed, payload.status)
        assertEquals(null, payload.reason)
    }

    @Test
    fun `maps approval request frame to tool call observed`() {
        val drafts = mapper.mapLine(
            """{"type":"message","id":"letta-msg-16","message_type":"approval_request_message","tool_call":{"tool_call_id":"call_00_abc","name":"Bash","arguments":"{\"command\":\"echo hi\"}"},"run_id":"local-run-1"}""",
            command(),
        )

        val payload = drafts.single().payload as RuntimeEventPayload.ToolCallObserved
        assertEquals("call_00_abc", payload.toolCallId.value)
        assertEquals("Bash", payload.toolName.value)
        assertEquals("""{"command":"echo hi"}""", payload.argumentsJson)
        assertEquals("local-run-1", drafts.single().runId?.value)
    }

    @Test
    fun `maps tool return frame to tool return observed`() {
        val drafts = mapper.mapLine(
            """{"type":"message","id":"letta-msg-17","message_type":"tool_return_message","tool_call_id":"call_00_abc","status":"error","tool_return":"boom","run_id":"local-run-1"}""",
            command(),
        )

        val payload = drafts.single().payload as RuntimeEventPayload.ToolReturnObserved
        assertEquals("call_00_abc", payload.toolCallId.value)
        assertEquals(com.letta.mobile.runtime.ToolExecutionStatus.Failed, payload.status)
        assertEquals("boom", payload.body)
    }

    @Test
    fun `tool return with boolean is_err maps to failed`() {
        val drafts = mapper.mapLine(
            """{"type":"message","id":"m","message_type":"tool_return_message","tool_call_id":"call_b","is_err":true,"tool_return":"nope"}""",
            command(),
        )

        val payload = drafts.single().payload as RuntimeEventPayload.ToolReturnObserved
        assertEquals(com.letta.mobile.runtime.ToolExecutionStatus.Failed, payload.status)
    }

    @Test
    fun `ignores tool approval control request until approvals are supported`() {
        val drafts = mapper.mapLine(
            """{"type":"control_request","request_id":"perm-call-1","request":{"subtype":"can_use_tool","tool_name":"Write","tool_call_id":"call-1","input":{"file_path":"README.md"}}}""",
            command(),
        )

        assertEquals(emptyList<RuntimeEventPayload>(), drafts.map { it.payload })
    }

    @Test
    fun `ignores tool approval request id independently from tool call id`() {
        val drafts = mapper.mapLine(
            """{"type":"control_request","request_id":"approval-random-123","request":{"subtype":"can_use_tool","tool_name":"Write","tool_call_id":"call-1","input":{"file_path":"README.md"}}}""",
            command(),
        )

        assertEquals(emptyList<RuntimeEventPayload>(), drafts.map { it.payload })
    }

    @Test
    fun `ignores openai compatible tool call payload until tools are supported`() {
        val drafts = mapper.mapLine(
            """{"type":"tool_call","run_id":"run-1","tool_calls":[{"id":"call-1","function":{"name":"Write","arguments":{"file_path":"README.md"}}}]}""",
            command(),
        )

        assertEquals(emptyList<RuntimeEventPayload>(), drafts.map { it.payload })
    }

    @Test
    fun `ignores tool return payload until tools are supported`() {
        val drafts = mapper.mapLine(
            """{"type":"tool_return_message","run_id":"run-1","tool_call_id":"call-1","status":"error","func_response":"permission denied"}""",
            command(),
        )

        assertEquals(emptyList<RuntimeEventPayload>(), drafts.map { it.payload })
    }

    @Test
    fun `maps error frame to failed lifecycle`() {
        val drafts = mapper.mapLine(
            """{"type":"error","run_id":"run-1","message":"provider failed"}""",
            command(),
        )

        val payload = drafts.single().payload as RuntimeEventPayload.RunLifecycleChanged
        assertEquals(RuntimeRunStatus.Failed, payload.status)
        assertEquals("provider failed", payload.reason)
        assertEquals("run-1", drafts.single().runId?.value)
    }

    @Test
    fun `ignores non-json runtime log lines`() {
        assertEquals(emptyList<RuntimeEventPayload>(), mapper.mapLine("plain log", command()).map { it.payload })
    }

    @Test
    fun `ignores malformed json and unknown stream events`() {
        assertEquals(emptyList<RuntimeEventPayload>(), mapper.mapLine("{bad-json", command()).map { it.payload })
        assertEquals(
            emptyList<RuntimeEventPayload>(),
            mapper.mapLine("""{"type":"stream_event","event":{"type":"future_event","payload":true}}""", command()).map { it.payload },
        )
    }

    private fun command(): TurnCommand = TurnCommand(
        backendId = BackendId("local-lettacode:test"),
        runtimeId = RuntimeId("local-lettacode:test"),
        agentId = AgentId("agent-1"),
        conversationId = ConversationId("conv-1"),
        input = TurnInput.UserMessage(
            localMessageId = "local-1",
            text = "hello",
        ),
    )
}
