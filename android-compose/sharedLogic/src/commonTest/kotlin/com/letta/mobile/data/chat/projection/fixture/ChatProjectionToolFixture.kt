package com.letta.mobile.data.chat.projection.fixture

import com.letta.mobile.data.a2ui.A2UI_BASIC_CATALOG_ID
import com.letta.mobile.data.a2ui.A2uiCreateSurfacePayload
import com.letta.mobile.data.a2ui.A2uiFrameEvent
import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.chat.projection.ToolTimelineState
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.parseTimelineInstant
import com.letta.mobile.ui.common.GroupPosition
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

internal object ToolApprovalFixture {
    private const val runId = "run-tools"
    private const val approvalRequestId = "approval-1"
    private const val conversationId = "conversation-fixture"
    private val instant = parseTimelineInstant("2026-08-16T01:00:00Z")
    private val pendingCall = ToolCall(
        id = "call-pending",
        name = "AskUserQuestion",
        arguments = "{\"questions\":[{\"question\":\"Continue?\",\"options\":[{\"label\":\"Yes\"}]}]}",
    )
    private val approvedCall = ToolCall(
        id = "call-approved",
        name = "Read",
        arguments = "{\"path\":\"README.md\"}",
    )

    val fixture = ChatProjectionFixture(
        id = "streaming-tool-run-with-approval-and-a2ui-link",
        timelineEvents = listOf(
            TimelineEvent.Confirmed(
                position = 0.0,
                otid = "otid-user-tools",
                content = "inspect it",
                serverId = "user-tools",
                messageType = TimelineMessageType.USER,
                date = instant,
                runId = null,
                stepId = null,
            ),
            TimelineEvent.Confirmed(
                position = 1.0,
                otid = "otid-reasoning-tools",
                content = "I will inspect the repository",
                serverId = "reasoning-tools",
                messageType = TimelineMessageType.REASONING,
                date = instant,
                runId = runId,
                stepId = "step-tools",
            ),
            TimelineEvent.Confirmed(
                position = 2.0,
                otid = "otid-tool-pending",
                content = "",
                serverId = "tool-pending",
                messageType = TimelineMessageType.TOOL_CALL,
                date = instant,
                runId = runId,
                stepId = "step-tools",
                toolCalls = persistentListOf(pendingCall),
                approvalRequestId = approvalRequestId,
            ),
            TimelineEvent.Confirmed(
                position = 3.0,
                otid = "otid-tool-approved",
                content = "",
                serverId = "tool-approved",
                messageType = TimelineMessageType.TOOL_CALL,
                date = instant,
                runId = runId,
                stepId = "step-tools",
                toolCalls = persistentListOf(approvedCall),
                approvalRequestId = approvalRequestId,
                approvalDecided = true,
                toolReturnContentByCallId = persistentMapOf("call-approved" to "contents"),
            ),
            TimelineEvent.Confirmed(
                position = 4.0,
                otid = "otid-assistant-tools",
                content = "Inspection complete",
                serverId = "assistant-tools",
                messageType = TimelineMessageType.ASSISTANT,
                date = instant,
                runId = runId,
                stepId = "step-tools",
            ),
        ),
        expectedItems = listOf(
            RenderItemExpectation(
                kind = RenderItemKind.RunBlock,
                key = runId,
                runId = runId,
                messages = listOf(
                    MessageExpectation(
                        "reasoning-tools:REASONING",
                        GroupPosition.First,
                        setOf(MessageFeature.Reasoning),
                    ),
                    MessageExpectation(
                        "tool-pending",
                        GroupPosition.Middle,
                        setOf(MessageFeature.ToolPending, MessageFeature.ApprovalPending),
                    ),
                    MessageExpectation(
                        "tool-approved",
                        GroupPosition.Middle,
                        setOf(MessageFeature.ToolSucceeded, MessageFeature.ApprovalApproved),
                    ),
                    MessageExpectation("assistant-tools", GroupPosition.Last),
                ),
            ),
            singleItem("msg-otid-user-tools", MessageExpectation("user-tools")),
        ),
        expectedToolStates = listOf(
            ToolTimelineState.AwaitingApproval,
            ToolTimelineState.Succeeded,
        ),
        a2uiEvent = A2uiFrameEvent(
            transport = "fixture",
            frameId = "frame-tools",
            timestamp = "2026-08-16T01:00:00Z",
            agentId = "agent-fixture",
            conversationId = conversationId,
            turnId = "turn-tools",
            runId = runId,
            requestId = approvalRequestId,
            messages = listOf(
                A2uiMessage.CreateSurface(
                    createSurface = A2uiCreateSurfacePayload(
                        surfaceId = "surface-tools",
                        catalogId = A2UI_BASIC_CATALOG_ID,
                    ),
                ),
            ),
        ),
    )
}
