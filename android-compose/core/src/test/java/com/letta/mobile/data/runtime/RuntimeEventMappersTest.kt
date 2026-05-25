package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.ApprovalResult
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.runtime.BackendCapabilities
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.EpochMillis
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventEnvelope
import com.letta.mobile.runtime.RuntimeEventId
import com.letta.mobile.runtime.RuntimeEventOffset
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventProjector
import com.letta.mobile.runtime.RuntimeProjection
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.ToolApprovalDecisionValue
import com.letta.mobile.runtime.ToolApprovalId
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.runtime.ToolName
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEventMappersTest {
    private val backend = BackendDescriptor(
        backendId = BackendId("remote-letta:backend-a"),
        runtimeId = RuntimeId("remote-letta:backend-a"),
        kind = BackendKind.RemoteLetta,
        label = "https://backend-a.example.test",
        capabilities = BackendCapabilities(
            supportsStreaming = true,
            supportsMemFs = true,
            supportsTools = true,
            supportsApprovals = true,
            supportsAgentFileImport = true,
            supportsAgentFileExport = true,
        ),
    )

    @Test
    fun `websocket turn lifecycle maps to shared runtime event draft`() {
        val drafts = WsTimelineEvent.TurnStarted(
            turnId = "turn-1",
            agentId = "agent-1",
            conversationId = "conversation-1",
            runId = "run-1",
        ).toRuntimeEventDrafts(backend)

        val draft = drafts.single()
        val payload = draft.payload as RuntimeEventPayload.RunLifecycleChanged

        assertEquals(backend.backendId, draft.backendId)
        assertEquals(backend.runtimeId, draft.runtimeId)
        assertEquals(AgentId("agent-1"), draft.agentId)
        assertEquals(ConversationId("conversation-1"), draft.conversationId)
        assertEquals(RuntimeRunStatus.Started, payload.status)
    }

    @Test
    fun `tool return before tool call projects into one execution`() {
        val returnDrafts = ToolReturnMessage(
            id = "return-message-1",
            toolReturnRaw = JsonPrimitive("file contents"),
            status = "success",
            toolCallId = "call-1",
            runId = "run-1",
        ).toRuntimeEventDrafts(
            backend = backend,
            fallbackAgentId = AgentId("agent-1"),
            fallbackConversationId = ConversationId("conversation-1"),
        )
        val callDrafts = ToolCallMessage(
            id = "call-message-1",
            toolCalls = listOf(
                ToolCall(
                    toolCallId = "call-1",
                    name = "read_file",
                    arguments = """{"path":"/memory/profile.md"}""",
                ),
            ),
            runId = "run-1",
        ).toRuntimeEventDrafts(
            backend = backend,
            fallbackAgentId = AgentId("agent-1"),
            fallbackConversationId = ConversationId("conversation-1"),
        )

        val projection = RuntimeEventProjector.replay(
            seed = RuntimeProjection(backend.backendId, backend.runtimeId),
            events = (returnDrafts + callDrafts).mapIndexed { index, draft ->
                draft.toEnvelope(offset = index + 1L)
            },
        )

        val tool = projection.toolExecutions.getValue(ToolCallId("call-1"))
        assertEquals(ToolName("read_file"), tool.name)
        assertEquals(ToolExecutionStatus.Succeeded, tool.status)
        assertEquals("file contents", tool.body)
    }

    @Test
    fun `approval request and response map through shared approval contracts`() {
        val requestDrafts = ApprovalRequestMessage(
            id = "approval-1",
            toolCall = ToolCall(
                toolCallId = "call-approval",
                name = "shell",
                arguments = """{"command":"rm -rf /tmp/demo"}""",
            ),
            runId = "run-1",
        ).toRuntimeEventDrafts(
            backend = backend,
            fallbackAgentId = AgentId("agent-1"),
            fallbackConversationId = ConversationId("conversation-1"),
        )
        val responseDrafts = ApprovalResponseMessage(
            id = "approval-response-1",
            approvals = listOf(
                ApprovalResult(
                    toolCallId = "call-approval",
                    approve = false,
                    reason = "dangerous",
                ),
            ),
            approvalRequestId = "approval-1",
            runId = "run-1",
        ).toRuntimeEventDrafts(
            backend = backend,
            fallbackAgentId = AgentId("agent-1"),
            fallbackConversationId = ConversationId("conversation-1"),
        )

        val projection = RuntimeEventProjector.replay(
            seed = RuntimeProjection(backend.backendId, backend.runtimeId),
            events = (requestDrafts + responseDrafts).mapIndexed { index, draft ->
                draft.toEnvelope(offset = index + 1L)
            },
        )

        assertTrue(ToolApprovalId("approval-1") !in projection.pendingApprovals)
        assertEquals(
            ToolApprovalDecisionValue.Denied,
            projection.resolvedApprovals.getValue(ToolApprovalId("approval-1")).decision,
        )
    }

    private fun RuntimeEventDraft.toEnvelope(offset: Long): RuntimeEventEnvelope = RuntimeEventEnvelope(
        offset = RuntimeEventOffset(offset),
        eventId = RuntimeEventId("event-$offset"),
        backendId = backendId,
        runtimeId = runtimeId,
        agentId = agentId,
        conversationId = conversationId,
        runId = runId,
        createdAt = EpochMillis(1_700_000_000_000 + offset),
        source = source,
        payload = payload,
    )
}
