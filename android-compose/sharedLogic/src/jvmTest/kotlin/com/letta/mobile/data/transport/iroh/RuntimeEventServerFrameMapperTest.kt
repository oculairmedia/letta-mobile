package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolApprovalId
import com.letta.mobile.runtime.ToolApprovalRequest
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.runtime.ToolName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Safety net for the finding-2 extraction of
 * IrohChannelTransport.payloadToServerFrames into [RuntimeEventServerFrameMapper]:
 * proves every branch still produces the same [ServerFrame] shapes (ids,
 * prefixes, statuses) the desktop App Server gateway and the mobile iroh
 * transport both depend on.
 */
class RuntimeEventServerFrameMapperTest {
    private val context = RuntimeEventServerFrameMapper.Context(
        agentId = "agent-1",
        conversationId = "conv-1",
        turnId = "turn-1",
        runId = "run-1",
    )

    @Test
    fun completedLifecycle_mapsToTurnDoneCompleted() {
        val frames = RuntimeEventServerFrameMapper.map(
            RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Completed),
            context,
        )
        val turnDone = assertIs<ServerFrame.TurnDone>(frames.single())
        assertEquals("completed", turnDone.status)
        assertEquals("turn-1", turnDone.turnId)
        assertEquals("run-1", turnDone.runId)
    }

    @Test
    fun failedLifecycle_mapsToTurnDoneFailed() {
        val frames = RuntimeEventServerFrameMapper.map(
            RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Failed, reason = "boom"),
            context,
        )
        val turnDone = assertIs<ServerFrame.TurnDone>(frames.single())
        assertEquals("failed", turnDone.status)
    }

    @Test
    fun cancelledLifecycle_mapsToTurnDoneCancelled() {
        val frames = RuntimeEventServerFrameMapper.map(
            RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Cancelled),
            context,
        )
        val turnDone = assertIs<ServerFrame.TurnDone>(frames.single())
        assertEquals("cancelled", turnDone.status)
    }

    @Test
    fun startedAndRunningLifecycle_mapToNothing() {
        assertTrue(
            RuntimeEventServerFrameMapper.map(
                RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Started),
                context,
            ).isEmpty(),
        )
        assertTrue(
            RuntimeEventServerFrameMapper.map(
                RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Running),
                context,
            ).isEmpty(),
        )
    }

    @Test
    fun toolCallObserved_mapsToToolCallMessageWithPrefixedId() {
        val frames = RuntimeEventServerFrameMapper.map(
            RuntimeEventPayload.ToolCallObserved(
                toolCallId = ToolCallId("tc-1"),
                toolName = ToolName("search"),
                argumentsJson = """{"q":"hi"}""",
            ),
            context,
        )
        val toolCall = assertIs<ServerFrame.ToolCallMessage>(frames.single())
        assertEquals("toolcall-tc-1", toolCall.id)
        assertEquals("search", toolCall.toolCall?.name)
    }

    @Test
    fun toolReturnFailed_mapsToErrorStatus() {
        val frames = RuntimeEventServerFrameMapper.map(
            RuntimeEventPayload.ToolReturnObserved(
                toolCallId = ToolCallId("tc-1"),
                status = ToolExecutionStatus.Failed,
                body = "boom",
            ),
            context,
        )
        val toolReturn = assertIs<ServerFrame.ToolReturnMessage>(frames.single())
        assertEquals("toolreturn-tc-1", toolReturn.id)
        assertEquals("error", toolReturn.status)
    }

    @Test
    fun toolReturnSucceeded_mapsToSuccessStatus() {
        val frames = RuntimeEventServerFrameMapper.map(
            RuntimeEventPayload.ToolReturnObserved(
                toolCallId = ToolCallId("tc-1"),
                status = ToolExecutionStatus.Succeeded,
                body = "ok",
            ),
            context,
        )
        val toolReturn = assertIs<ServerFrame.ToolReturnMessage>(frames.single())
        assertEquals("success", toolReturn.status)
    }

    @Test
    fun approvalRequested_mapsToApprovalRequestMessageType() {
        val frames = RuntimeEventServerFrameMapper.map(
            RuntimeEventPayload.ApprovalRequested(
                request = ToolApprovalRequest(
                    approvalId = ToolApprovalId("approval-1"),
                    callId = ToolCallId("tc-1"),
                    toolName = ToolName("search"),
                    prompt = "May I search?",
                    argumentsPreview = """{"q":"hi"}""",
                ),
            ),
            context,
        )
        val approval = assertIs<ServerFrame.ToolCallMessage>(frames.single())
        assertEquals("approval_request_message", approval.type)
        assertEquals("approval-1", approval.id)
    }

    @Test
    fun externalTransportFrame_isDropped() {
        val frames = RuntimeEventServerFrameMapper.map(
            RuntimeEventPayload.ExternalTransportFrame(
                frameId = "frame-1",
                transportMessageId = "tm-1",
                body = "{}",
            ),
            context,
        )
        assertTrue(frames.isEmpty())
    }

    @Test
    fun remoteStreamFrame_assistantDelta_mapsToAssistantMessageWithStableOtid() {
        val body = """{"message_type":"assistant_message","id":"cm-stream-x","content":"Hi"}"""
        val frames = RuntimeEventServerFrameMapper.map(
            RuntimeEventPayload.RemoteStreamFrame(
                frameId = "frame-1",
                messageId = "cm-stream-x",
                messageType = "assistant_message",
                body = body,
            ),
            context,
        )
        val assistant = assertIs<ServerFrame.AssistantMessage>(frames.single())
        assertEquals("Hi", assistant.content)
        assertEquals("iroh-assistant-cm-stream-x", assistant.otid)
    }
}
