package com.letta.mobile.data.presence

import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolApprovalDecision
import com.letta.mobile.runtime.ToolApprovalDecisionValue
import com.letta.mobile.runtime.ToolApprovalId
import com.letta.mobile.runtime.ToolApprovalRequest
import com.letta.mobile.runtime.ToolApprovalScope
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.runtime.ToolName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The transition table, walked as real turns rather than asserted state by state: what the mascot
 * and the conversation list are allowed to say at each step of a turn.
 */
class RunPhaseReducerTest {

    private var clock = 1_000L
    private var state = ConversationRunState("c1", "a1")

    private fun feed(event: RuntimeEventPayload): RunPhase {
        clock += 10
        state = RunPhaseReducer.reduce(state, event, clock)
        return state.phase
    }

    private fun frame(messageType: String) =
        RuntimeEventPayload.RemoteStreamFrame(frameId = "f-$messageType-$clock", messageType = messageType, body = "{}")

    private fun toolCall(id: String, name: String) =
        RuntimeEventPayload.ToolCallObserved(ToolCallId(id), ToolName(name))

    private fun toolReturn(id: String) =
        RuntimeEventPayload.ToolReturnObserved(ToolCallId(id), ToolExecutionStatus.Succeeded, body = "ok")

    private fun lifecycle(status: RuntimeRunStatus) = RuntimeEventPayload.RunLifecycleChanged(status)

    @Test
    fun aRealTurnWalksReasoningThenToolsThenTheReply() {
        assertEquals(RunPhase.QUEUED, feed(RuntimeEventPayload.LocalUserAppend("local-1", "hello")))
        assertEquals(RunPhase.QUEUED, feed(lifecycle(RuntimeRunStatus.Started)))
        assertEquals(RunPhase.REASONING, feed(frame("reasoning_message")))

        assertEquals(RunPhase.WORKING, feed(toolCall("t1", "grep")))
        assertEquals("grep", state.toolName)
        assertEquals(1, state.openToolCalls)

        // The gap between tool phases is QUEUED, not "still running grep".
        assertEquals(RunPhase.QUEUED, feed(toolReturn("t1")))
        assertEquals(0, state.openToolCalls)

        assertEquals(RunPhase.WORKING, feed(toolCall("t2", "Read")))
        assertEquals("Read", state.toolName)
        assertEquals(RunPhase.QUEUED, feed(toolReturn("t2")))

        assertEquals(RunPhase.RESPONDING, feed(frame("assistant_message")))
        assertTrue(state.streamingTokens)

        assertEquals(RunPhase.DONE, feed(lifecycle(RuntimeRunStatus.Completed)))
        assertNull(state.toolName)

        // DONE is momentary: the next turn's first event starts from rest.
        assertEquals(RunPhase.QUEUED, feed(RuntimeEventPayload.LocalUserAppend("local-2", "again")))
    }

    @Test
    fun theApprovalDetourParksTheTurnOnTheUser() {
        feed(RuntimeEventPayload.LocalUserAppend("local-1", "delete it"))
        feed(frame("reasoning_message"))

        val request = ToolApprovalRequest(
            approvalId = ToolApprovalId("ap-1"),
            callId = ToolCallId("t1"),
            toolName = ToolName("Bash"),
            prompt = "rm -rf",
        )
        assertEquals(RunPhase.AWAITING_INPUT, feed(RuntimeEventPayload.ApprovalRequested(request)))
        assertEquals("Bash", state.toolName)
        assertTrue(state.awaitingApproval)

        // Tokens arriving while parked must not steal the phase: the turn is still on the user.
        assertEquals(RunPhase.AWAITING_INPUT, feed(frame("reasoning_message")))

        val approved = ToolApprovalDecision(
            approvalId = ToolApprovalId("ap-1"),
            callId = ToolCallId("t1"),
            decision = ToolApprovalDecisionValue.Approved,
            scope = ToolApprovalScope.Once,
        )
        assertEquals(RunPhase.WORKING, feed(RuntimeEventPayload.ApprovalResolved(approved)))
        assertEquals(RunPhase.WORKING, feed(toolCall("t1", "Bash")))
        assertEquals(RunPhase.QUEUED, feed(toolReturn("t1")))
        assertEquals(RunPhase.RESPONDING, feed(frame("assistant_message")))
    }

    @Test
    fun aDeniedApprovalReturnsToTheGapWithNothingRunning() {
        feed(RuntimeEventPayload.LocalUserAppend("local-1", "delete it"))
        feed(
            RuntimeEventPayload.ApprovalRequested(
                ToolApprovalRequest(ToolApprovalId("ap-1"), ToolCallId("t1"), ToolName("Bash"), "rm -rf"),
            ),
        )
        val denied = ToolApprovalDecision(
            approvalId = ToolApprovalId("ap-1"),
            callId = ToolCallId("t1"),
            decision = ToolApprovalDecisionValue.Denied,
            scope = ToolApprovalScope.Once,
        )
        assertEquals(RunPhase.QUEUED, feed(RuntimeEventPayload.ApprovalResolved(denied)))
        assertNull(state.toolName)
        assertEquals(0, state.openToolCalls, "a denied call never returns, so it stops counting")
    }

    @Test
    fun denyingOneOfTwoOpenCallsKeepsTheOtherRunning() {
        feed(RuntimeEventPayload.LocalUserAppend("local-1", "two at once"))
        assertEquals(RunPhase.WORKING, feed(toolCall("t1", "grep")))
        feed(
            RuntimeEventPayload.ApprovalRequested(
                ToolApprovalRequest(ToolApprovalId("ap-2"), ToolCallId("t2"), ToolName("Bash"), "rm -rf"),
            ),
        )
        val denied = ToolApprovalDecision(
            approvalId = ToolApprovalId("ap-2"),
            callId = ToolCallId("t2"),
            decision = ToolApprovalDecisionValue.Denied,
            scope = ToolApprovalScope.Once,
        )
        // t1 is still in flight: the turn is working, not idling in the gap, and still names a tool.
        assertEquals(RunPhase.WORKING, feed(RuntimeEventPayload.ApprovalResolved(denied)))
        assertEquals(1, state.openToolCalls)
        assertEquals("Bash", state.toolName, "the name stays what the turn last did until nothing is open")
        assertEquals(RunPhase.QUEUED, feed(toolReturn("t1")))
    }

    @Test
    fun aNewTurnForgetsTheLastTurnsSubagents() {
        feed(RuntimeEventPayload.LocalUserAppend("local-1", "fan out"))
        feed(toolCall("t1", "Task"))
        state = RunPhaseReducer.withSubagents(state, 3, ++clock)
        assertEquals(RunPhase.DELEGATING, state.phase)
        assertEquals(RunPhase.DONE, feed(lifecycle(RuntimeRunStatus.Completed)))
        // The next turn's first tool is plain WORKING until the subagent registry speaks again.
        feed(RuntimeEventPayload.LocalUserAppend("local-2", "again"))
        assertEquals(0, state.subagentCount)
        assertEquals(RunPhase.WORKING, feed(toolCall("t2", "grep")))
    }

    @Test
    fun theSameToolCallSeenTwiceIsStillOneCallInFlight() {
        feed(RuntimeEventPayload.LocalUserAppend("local-1", "go"))
        // The approval request carries the call, and its tool-call frame follows: one call, not two.
        feed(
            RuntimeEventPayload.ApprovalRequested(
                ToolApprovalRequest(ToolApprovalId("ap-1"), ToolCallId("t1"), ToolName("Bash"), "ls"),
            ),
        )
        feed(
            RuntimeEventPayload.ApprovalResolved(
                ToolApprovalDecision(
                    ToolApprovalId("ap-1"),
                    ToolCallId("t1"),
                    ToolApprovalDecisionValue.Approved,
                    ToolApprovalScope.Once,
                ),
            ),
        )
        feed(toolCall("t1", "Bash"))
        assertEquals(1, state.openToolCalls)
        assertEquals(RunPhase.QUEUED, feed(toolReturn("t1")))
    }

    @Test
    fun overlappingToolCallsStayWorkingUntilTheLastReturn() {
        feed(RuntimeEventPayload.LocalUserAppend("local-1", "search everything"))
        assertEquals(RunPhase.WORKING, feed(toolCall("t1", "grep")))
        assertEquals(RunPhase.WORKING, feed(toolCall("t2", "glob")))
        assertEquals(RunPhase.WORKING, feed(toolCall("t3", "Read")))
        assertEquals(3, state.openToolCalls)

        assertEquals(RunPhase.WORKING, feed(toolReturn("t2")))
        assertEquals(RunPhase.WORKING, feed(toolReturn("t3")))
        assertEquals(1, state.openToolCalls)
        // Reasoning tokens between two open tool calls do not demote the honest "it is working".
        assertEquals(RunPhase.WORKING, feed(frame("reasoning_message")))
        assertEquals(RunPhase.QUEUED, feed(toolReturn("t1")))
        assertEquals(0, state.openToolCalls)
    }

    @Test
    fun aTerminalBeforeTheLastToolReturnStillEndsTheTurn() {
        feed(RuntimeEventPayload.LocalUserAppend("local-1", "go"))
        feed(toolCall("t1", "Bash"))
        feed(toolCall("t2", "Bash"))
        assertEquals(RunPhase.FAILED, feed(lifecycle(RuntimeRunStatus.Failed)))
        assertEquals(0, state.openToolCalls)
        assertTrue(state.error)

        // The straggling returns arrive after the terminal; they must not restart the run.
        assertEquals(RunPhase.FAILED, feed(toolReturn("t1")))
        assertEquals(RunPhase.FAILED, feed(toolReturn("t2")))
        assertTrue(!state.running)

        // A retry is a new turn.
        assertEquals(RunPhase.QUEUED, feed(RuntimeEventPayload.RetryRequested("local-1")))
    }

    @Test
    fun subagentsUnderAToolCallReadAsDelegating() {
        feed(RuntimeEventPayload.LocalUserAppend("local-1", "fan out"))
        assertEquals(RunPhase.WORKING, feed(toolCall("t1", "Task")))
        state = RunPhaseReducer.withSubagents(state, 3, ++clock)
        assertEquals(RunPhase.DELEGATING, state.phase)
        assertEquals(3, state.subagentCount)
        // Still delegating while the tool runs; back to the gap when it returns.
        assertEquals(RunPhase.QUEUED, feed(toolReturn("t1")))
        state = RunPhaseReducer.withSubagents(state, 0, ++clock)
        assertEquals(RunPhase.QUEUED, state.phase)
    }

    @Test
    fun aFailedSendAndAnErrorFrameBothAttributeTheFailureHere() {
        feed(RuntimeEventPayload.LocalUserAppend("local-1", "go"))
        assertEquals(RunPhase.FAILED, feed(RuntimeEventPayload.SendMarkedFailed("local-1", "offline")))

        state = ConversationRunState("c1", "a1")
        feed(RuntimeEventPayload.LocalUserAppend("local-2", "go"))
        assertEquals(RunPhase.FAILED, feed(frame("error_message")))
    }

    @Test
    fun interruptingHoldsUntilTheTerminalAndOnlyAppliesToARunningTurn() {
        feed(RuntimeEventPayload.LocalUserAppend("local-1", "go"))
        feed(toolCall("t1", "Bash"))
        state = RunPhaseReducer.interrupting(state, ++clock)
        assertEquals(RunPhase.INTERRUPTING, state.phase)
        assertEquals(RunPhase.DONE, feed(lifecycle(RuntimeRunStatus.Cancelled)))

        val rested = ConversationRunState("c2", "a1")
        assertEquals(rested, RunPhaseReducer.interrupting(rested, clock))
    }

    @Test
    fun phaseSinceIsStampedOnChangeOnly() {
        feed(RuntimeEventPayload.LocalUserAppend("local-1", "go"))
        feed(toolCall("t1", "grep"))
        val enteredWorkingAt = state.phaseSinceEpochMs
        feed(toolCall("t2", "glob")) // still WORKING
        assertEquals(enteredWorkingAt, state.phaseSinceEpochMs)
        feed(toolReturn("t1"))
        feed(toolReturn("t2"))
        assertTrue(state.phaseSinceEpochMs > enteredWorkingAt)
    }

    @Test
    fun theBodysMessageTypeClassifiesFramesTheMapperDidNotLabel() {
        feed(RuntimeEventPayload.LocalUserAppend("local-1", "go"))
        val unlabeled = RuntimeEventPayload.RemoteStreamFrame(
            frameId = "f1",
            messageType = null,
            body = """{"delta":{"message_type":"assistant_message","content":"hi"}}""",
        )
        assertEquals(RunPhase.RESPONDING, feed(unlabeled))
    }
}
