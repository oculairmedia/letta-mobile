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
    fun `maps tool approval control request`() {
        val drafts = mapper.mapLine(
            """{"type":"control_request","request_id":"perm-call-1","request":{"subtype":"can_use_tool","tool_name":"Write","tool_call_id":"call-1","input":{"file_path":"README.md"}}}""",
            command(),
        )

        val payload = drafts.single().payload as RuntimeEventPayload.ApprovalRequested
        assertEquals("letta-code:call-1", payload.request.approvalId.value)
        assertEquals("call-1", payload.request.callId.value)
        assertEquals("Write", payload.request.toolName.value)
        assertTrue(payload.request.argumentsPreview?.contains("README.md") == true)
    }

    @Test
    fun `ignores non-json runtime log lines`() {
        assertEquals(emptyList<RuntimeEventPayload>(), mapper.mapLine("plain log", command()).map { it.payload })
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
