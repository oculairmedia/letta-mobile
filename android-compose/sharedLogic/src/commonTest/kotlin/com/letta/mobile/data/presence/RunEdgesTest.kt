package com.letta.mobile.data.presence

import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolApprovalId
import com.letta.mobile.runtime.ToolApprovalRequest
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.runtime.ToolName
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-te3n1.1: every moment of a run is counted once, however the registry is sampled. */
class RunEdgesTest {
    private var clock = 0L
    private var state = ConversationRunState("c1", "a1")

    private fun feed(vararg events: RuntimeEventPayload) {
        events.forEach { state = RunPhaseReducer.reduce(state, it, ++clock) }
    }

    private fun toolCall(id: String) = RuntimeEventPayload.ToolCallObserved(ToolCallId(id), ToolName("grep"))
    private fun toolReturn(id: String) = RuntimeEventPayload.ToolReturnObserved(ToolCallId(id), ToolExecutionStatus.Succeeded, "ok")
    private fun lifecycle(status: RuntimeRunStatus) = RuntimeEventPayload.RunLifecycleChanged(status)
    private fun send(id: String) = RuntimeEventPayload.LocalUserAppend(id, "hi")

    @Test
    fun aTurnCountsItsSendToolsAndFinish() {
        feed(send("l1"), toolCall("t1"), toolCall("t2"), toolReturn("t1"), toolReturn("t2"), lifecycle(RuntimeRunStatus.Completed))
        assertEquals(RunEdges(sent = 1, toolsStarted = 2, toolsEnded = 2, completed = 1), state.edges)
    }

    @Test
    fun aToolSeenTwiceStartsOnceAndAReReportedTerminalFinishesOnce() {
        feed(send("l1"), toolCall("t1"), toolCall("t1"), lifecycle(RuntimeRunStatus.Completed), lifecycle(RuntimeRunStatus.Completed))
        assertEquals(1, state.edges.toolsStarted)
        assertEquals(1, state.edges.toolsEnded, "the terminal ended the call that was still open")
        assertEquals(1, state.edges.completed)
    }

    @Test
    fun backToBackTurnsCountTwoFinishesEvenWhenDoneIsNeverSampled() {
        feed(send("l1"), lifecycle(RuntimeRunStatus.Completed), send("l2"), lifecycle(RuntimeRunStatus.Completed), send("l3"))
        assertEquals(2, state.edges.completed)
        assertEquals(3, state.edges.sent)
        assertEquals(RunPhase.QUEUED, state.phase)
    }

    @Test
    fun cancelAndFailureAreTheirOwnCountsAndAFailureIsCountedOnEntry() {
        feed(send("l1"), lifecycle(RuntimeRunStatus.Cancelled))
        feed(send("l2"), lifecycle(RuntimeRunStatus.Failed), RuntimeEventPayload.SendMarkedFailed("l2", "boom"))
        assertEquals(RunEdges(sent = 2, cancelled = 1, failed = 1), state.edges)
    }

    @Test
    fun eachDistinctApprovalCountsOnce() {
        val ask = { id: String -> RuntimeEventPayload.ApprovalRequested(ToolApprovalRequest(ToolApprovalId(id), ToolCallId("t-$id"), ToolName("Bash"), "ls")) }
        feed(send("l1"), ask("ap-1"), ask("ap-1"), ask("ap-2"))
        assertEquals(2, state.edges.approvals)
    }

    @Test
    fun presenceSumsTheEdgesOfEveryConversationOfTheAgent() {
        val one = ConversationRunState("c1", "a1", edges = RunEdges(sent = 1, completed = 1))
        val two = ConversationRunState("c2", "a1", edges = RunEdges(sent = 2, failed = 1))
        val presence = mapOf("c1" to one, "c2" to two).presenceByAgent().getValue("a1")
        assertEquals(RunEdges(sent = 3, completed = 1, failed = 1), presence.edges)
    }
}
