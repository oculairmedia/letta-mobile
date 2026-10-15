package com.letta.mobile.runtime

import com.letta.mobile.data.model.AgentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeProjectionTest {
    private val backendId = BackendId("local")
    private val runtimeId = RuntimeId("koog")
    private val agentId = AgentId("agent-1")
    private val conversationId = ConversationId("conversation-1")

    @Test
    fun replayIgnoresDuplicateEventIds() {
        val payload = RuntimeEventPayload.LocalUserAppend(
            localMessageId = "local-1",
            text = "hello",
        )
        val event = event(offset = 1, eventId = "event-1", payload = payload)
        val duplicate = event.copy(offset = RuntimeEventOffset(2))

        val projection = RuntimeEventProjector.replay(
            seed = projection(),
            events = listOf(event, duplicate),
        )

        assertEquals(1, projection.localMessages.size)
        assertEquals(RuntimeEventOffset(1), projection.lastOffset)
    }

    @Test
    fun replayDoesNotCrossBackendRuntimeBoundaries() {
        val foreignEvent = event(
            offset = 1,
            eventId = "foreign-event",
            backendId = BackendId("remote"),
            runtimeId = RuntimeId("remote-runtime"),
            payload = RuntimeEventPayload.LocalUserAppend(
                localMessageId = "local-1",
                text = "wrong runtime",
            ),
        )

        val projection = RuntimeEventProjector.reduce(projection(), foreignEvent)

        assertTrue(RuntimeEventId("foreign-event") in projection.ignoredEventIds)
        assertEquals(RuntimeEventOffset(0), projection.lastOffset)
        assertTrue(projection.localMessages.isEmpty())
    }

    @Test
    fun toolReturnBeforeToolCallStillResolvesProjection() {
        val callId = ToolCallId("call-1")
        val projection = RuntimeEventProjector.replay(
            seed = projection(),
            events = listOf(
                event(
                    offset = 1,
                    eventId = "return-1",
                    payload = RuntimeEventPayload.ToolReturnObserved(
                        toolCallId = callId,
                        status = ToolExecutionStatus.Succeeded,
                        body = "file contents",
                    ),
                ),
                event(
                    offset = 2,
                    eventId = "call-1",
                    payload = RuntimeEventPayload.ToolCallObserved(
                        toolCallId = callId,
                        toolName = ToolName("read_file"),
                        argumentsJson = """{"path":"/memory/profile.md"}""",
                    ),
                ),
            ),
        )

        val tool = projection.toolExecutions.getValue(callId)
        assertEquals(ToolName("read_file"), tool.name)
        assertEquals(ToolExecutionStatus.Succeeded, tool.status)
        assertEquals("file contents", tool.body)
    }

    @Test
    fun localDeliveryEventsUpdateExistingLocalMessage() {
        val projection = RuntimeEventProjector.replay(
            seed = projection(),
            events = listOf(
                event(
                    offset = 1,
                    eventId = "local-1",
                    payload = RuntimeEventPayload.LocalUserAppend(
                        localMessageId = "local-1",
                        text = "hello",
                    ),
                ),
                event(
                    offset = 2,
                    eventId = "failed-1",
                    payload = RuntimeEventPayload.SendMarkedFailed(
                        localMessageId = "local-1",
                        reason = "offline",
                    ),
                ),
                event(
                    offset = 3,
                    eventId = "retry-1",
                    payload = RuntimeEventPayload.RetryRequested(localMessageId = "local-1"),
                ),
                event(
                    offset = 4,
                    eventId = "sent-1",
                    payload = RuntimeEventPayload.SendMarkedSent(
                        localMessageId = "local-1",
                        serverMessageId = "server-1",
                    ),
                ),
            ),
        )

        val message = projection.localMessages.single()
        assertEquals(RuntimeLocalDelivery.Sent, message.delivery)
        assertEquals("server-1", message.serverMessageId)
        assertEquals(null, message.failureReason)
    }

    @Test
    fun approvalResolutionMovesRequestOutOfPendingSet() {
        val approvalId = ToolApprovalId("approval-1")
        val callId = ToolCallId("call-1")
        val request = ToolApprovalRequest(
            approvalId = approvalId,
            callId = callId,
            toolName = ToolName("shell"),
            prompt = "Allow shell command?",
        )
        val decision = ToolApprovalDecision(
            approvalId = approvalId,
            callId = callId,
            decision = ToolApprovalDecisionValue.Approved,
            scope = ToolApprovalScope.Session,
        )

        val projection = RuntimeEventProjector.replay(
            seed = projection(),
            events = listOf(
                event(
                    offset = 1,
                    eventId = "approval-requested",
                    payload = RuntimeEventPayload.ApprovalRequested(request),
                ),
                event(
                    offset = 2,
                    eventId = "approval-resolved",
                    payload = RuntimeEventPayload.ApprovalResolved(decision),
                ),
            ),
        )

        assertFalse(approvalId in projection.pendingApprovals)
        assertEquals(decision, projection.resolvedApprovals[approvalId])
    }

    private fun projection(): RuntimeProjection = RuntimeProjection(
        backendId = backendId,
        runtimeId = runtimeId,
    )

    private fun event(
        offset: Long,
        eventId: String,
        payload: RuntimeEventPayload,
        backendId: BackendId = this.backendId,
        runtimeId: RuntimeId = this.runtimeId,
        runId: RunId? = null,
    ): RuntimeEventEnvelope = RuntimeEventEnvelope(
        offset = RuntimeEventOffset(offset),
        eventId = RuntimeEventId(eventId),
        backendId = backendId,
        runtimeId = runtimeId,
        agentId = agentId,
        conversationId = conversationId,
        runId = runId,
        createdAt = EpochMillis(1_700_000_000_000 + offset),
        source = RuntimeEventSource.LocalRuntime,
        payload = payload,
    )
}
