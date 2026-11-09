package com.letta.mobile.data.presence

import com.letta.mobile.runtime.ToolApprovalId
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ActiveTurnCoordinatorTest {
    @Test
    fun `progress cannot start resurrect or escape cancellation`() = runTest {
        val owner = ActiveTurnCoordinator(ConversationId("c"), AgentId("a"), backgroundScope) { testScheduler.currentTime }
        owner.progress(ActiveTurnIdentity("r", "t"), mapOf("call" to "Bash"))
        assertEquals(ActiveTurnPhase.Idle, owner.state.value.phase)
        owner.start(ActiveTurnIdentity("r", "t"))
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
        advanceTimeBy(10)
        owner.progress(ActiveTurnIdentity("r", "t"), linkedMapOf("z" to "First", "a" to "Second"), 2)
        assertEquals("First", owner.state.value.activeTool)
        assertEquals(0L, owner.state.value.startedAtEpochMs)
        var aborts = 0
        owner.interrupt(ActiveTurnIdentity("r", "t"), "press1") { aborts++; true }
        runCurrent()
        owner.interrupt(ActiveTurnIdentity("r", "t"), "press1") { aborts++; true }
        owner.progress(ActiveTurnIdentity("r", "t"), emptyMap())
        assertEquals(ActiveTurnPhase.Cancelling, owner.state.value.phase)
        assertEquals(1, aborts)
        owner.interrupt(ActiveTurnIdentity("r", "t"), "press2") { aborts++; true }
        assertEquals(ActiveTurnPhase.Idle, owner.state.value.phase)
        assertEquals(null, owner.state.value.activeTool)
        assertEquals(0, owner.state.value.subagentCount)
        owner.start(ActiveTurnIdentity("r2", "t2"))
        owner.terminate(ActiveTurnIdentity("r", "t"), ActiveTurnTerminalKind.Finished)
        owner.start(ActiveTurnIdentity("r", "t"))
        assertEquals("r2", owner.state.value.runId)
    }

    @Test
    fun `approval waits for acceptance and deduplicates pending submission`() = runTest {
        val owner = ActiveTurnCoordinator(ConversationId("c"), AgentId("a"), backgroundScope) { testScheduler.currentTime }
        val accepted = CompletableDeferred<Boolean>()
        owner.start(ActiveTurnIdentity("r", "t"))
        owner.awaitApproval(
            ActiveTurnIdentity("r", "t"),
            ActiveTurnApproval(ToolApprovalId("request"), ToolCallId("call")) { accepted.await() },
        )
        val submission = async { owner.submitApproval(ToolApprovalId("request"), true) }
        runCurrent()
        assertEquals(ActiveTurnPhase.AwaitingInput, owner.state.value.phase)
        assertFalse(owner.submitApproval(ToolApprovalId("request"), true))
        owner.progress(ActiveTurnIdentity("r", "t"), emptyMap(), 3)
        assertEquals(3, owner.state.value.subagentCount)
        accepted.complete(true)
        assertTrue(submission.await())
        assertEquals(ActiveTurnPhase.ToolExecuting, owner.state.value.phase)
        assertFalse(owner.submitApproval(ToolApprovalId("request"), true))
    }

    @Test
    fun `rejected approval stays parked and denial recomputes tools`() = runTest {
        val owner = ActiveTurnCoordinator(ConversationId("c"), AgentId("a"), backgroundScope) { testScheduler.currentTime }
        owner.start(ActiveTurnIdentity("r", "t"))
        var calls = 0
        owner.awaitApproval(
            ActiveTurnIdentity("r", "t"),
            ActiveTurnApproval(ToolApprovalId("request"), ToolCallId("call")) { ++calls > 1 },
        )
        assertFalse(owner.submitApproval(ToolApprovalId("request"), false))
        assertEquals(ActiveTurnPhase.AwaitingInput, owner.state.value.phase)
        assertTrue(owner.submitApproval(ToolApprovalId("request"), false))
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
    }

    @Test
    fun `timeout clears at first press deadline and stale effects cannot clear replacement`() = runTest {
        val owner = ActiveTurnCoordinator(ConversationId("c"), AgentId("a"), backgroundScope) { testScheduler.currentTime }
        owner.start(ActiveTurnIdentity("r", "t"))
        val abort = CompletableDeferred<Boolean>()
        owner.interrupt(ActiveTurnIdentity("r", "t"), "press") { abort.await() }
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(ActiveTurnPhase.Idle, owner.state.value.phase)
        owner.start(ActiveTurnIdentity("new", "new"))
        abort.complete(false)
        runCurrent()
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
    }

    @Test
    fun `terminal before start and deletion permanently fence callbacks`() = runTest {
        val owner = ActiveTurnCoordinator(ConversationId("c"), AgentId("a"), backgroundScope) { testScheduler.currentTime }
        owner.terminate(ActiveTurnIdentity("old", "old"), ActiveTurnTerminalKind.Finished)
        owner.start(ActiveTurnIdentity("old", "old"))
        assertEquals(ActiveTurnPhase.Idle, owner.state.value.phase)
        owner.start(ActiveTurnIdentity("new", "new"))
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
        owner.delete()
        owner.start(ActiveTurnIdentity("later", "later"))
        owner.progress(ActiveTurnIdentity("new", "new"), mapOf("call" to "Bash"))
        assertEquals(ActiveTurnPhase.Idle, owner.state.value.phase)
        assertEquals(null, owner.state.value.startedAtEpochMs)
    }
    @Test
    fun `multiple approvals preserve priority and accepted requests cannot replay`() = runTest {
        val owner = ActiveTurnCoordinator(ConversationId("c"), AgentId("a"), backgroundScope) { testScheduler.currentTime }
        owner.start(ActiveTurnIdentity("r", "t"))
        owner.progress(ActiveTurnIdentity("r", "t"), linkedMapOf("first" to "First", "second" to "Second"))
        owner.awaitApproval(
            ActiveTurnIdentity("r", "t"),
            ActiveTurnApproval(ToolApprovalId("one"), ToolCallId("first")) { true },
        )
        owner.awaitApproval(
            ActiveTurnIdentity("r", "t"),
            ActiveTurnApproval(ToolApprovalId("two"), ToolCallId("second")) { true },
        )
        assertTrue(owner.submitApproval(ToolApprovalId("one"), false))
        assertEquals(ActiveTurnPhase.AwaitingInput, owner.state.value.phase)
        assertEquals("Second", owner.state.value.activeTool)
        assertTrue(owner.submitApproval(ToolApprovalId("two"), false))
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
        owner.awaitApproval(
            ActiveTurnIdentity("r", "t"),
            ActiveTurnApproval(ToolApprovalId("one"), ToolCallId("first")) { error("retired request dispatched") },
        )
        assertFalse(owner.submitApproval(ToolApprovalId("one"), true))
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
    }

    @Test
    fun `old approval completion and mismatched deletion cannot affect replacement`() = runTest {
        val owner = ActiveTurnCoordinator(ConversationId("c"), AgentId("a"), backgroundScope) { testScheduler.currentTime }
        val acceptance = CompletableDeferred<Boolean>()
        owner.start(ActiveTurnIdentity("r", "t"))
        owner.awaitApproval(
            ActiveTurnIdentity("r", "t"),
            ActiveTurnApproval(ToolApprovalId("request"), ToolCallId("call")) { acceptance.await() },
        )
        val pending = async { owner.submitApproval(ToolApprovalId("request"), true) }
        runCurrent()
        owner.start(ActiveTurnIdentity("replacement", "replacement"))
        acceptance.complete(true)
        assertFalse(pending.await())
        owner.terminate(ActiveTurnIdentity("r", "t"), ActiveTurnTerminalKind.Deleted)
        assertEquals("replacement", owner.state.value.runId)
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
        owner.terminate(ActiveTurnIdentity("replacement", "replacement"), ActiveTurnTerminalKind.Failed)
        assertEquals(ActiveTurnTerminalKind.Failed, owner.outcome.value)
        owner.terminate(ActiveTurnIdentity("replacement", "replacement"), ActiveTurnTerminalKind.Finished)
        assertEquals(ActiveTurnTerminalKind.Failed, owner.outcome.value)
        owner.start(ActiveTurnIdentity("next", "next"))
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
    }

    @Test
    fun `concurrent progress publishes whole snapshots and reserves a single approval`() = runTest {
        val owner = ActiveTurnCoordinator(ConversationId("c"), AgentId("a"), backgroundScope) { testScheduler.currentTime }
        owner.start(ActiveTurnIdentity("r", "t"))
        val acceptance = CompletableDeferred<Boolean>()
        var submissions = 0
        owner.awaitApproval(
            ActiveTurnIdentity("r", "t"),
            ActiveTurnApproval(ToolApprovalId("request"), ToolCallId("call")) { submissions++; acceptance.await() },
        )
        val jobs = List(20) { async(kotlinx.coroutines.Dispatchers.Default) { owner.submitApproval(ToolApprovalId("request"), true) } }
        val progress = List(20) { index -> async(kotlinx.coroutines.Dispatchers.Default) {
            owner.progress(ActiveTurnIdentity("r", "t"), mapOf("tool" to "Bash"), index)
        } }
        progress.forEach { it.await() }
        assertEquals(ActiveTurnPhase.AwaitingInput, owner.state.value.phase)
        assertEquals("Bash", owner.state.value.activeTool)
        acceptance.complete(true)
        assertEquals(1, jobs.count { it.await() })
        assertEquals(1, submissions)
        assertEquals(ActiveTurnPhase.ToolExecuting, owner.state.value.phase)
    }

    @Test
    fun `accepted approval retains an open call before its tool frame arrives`() = runTest {
        val owner = ActiveTurnCoordinator(ConversationId("c"), AgentId("a"), backgroundScope) { testScheduler.currentTime }
        val turn = ActiveTurnIdentity("r", "t")
        owner.start(turn)
        owner.awaitApproval(turn, ActiveTurnApproval(ToolApprovalId("first"), ToolCallId("first")) { true })
        owner.awaitApproval(turn, ActiveTurnApproval(ToolApprovalId("second"), ToolCallId("second")) { true })
        assertTrue(owner.submitApproval(ToolApprovalId("first"), true))
        assertEquals(ActiveTurnPhase.AwaitingInput, owner.state.value.phase)
        assertTrue(owner.submitApproval(ToolApprovalId("second"), false))
        assertEquals(ActiveTurnPhase.ToolExecuting, owner.state.value.phase)
        owner.progress(turn, emptyMap())
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
    }


    @Test
    fun `submit approval with mismatched turn fails without invoking callback`() = runTest {
        val owner = ActiveTurnCoordinator(ConversationId("c"), AgentId("a"), backgroundScope) { testScheduler.currentTime }
        val turn1 = ActiveTurnIdentity("r1", "t1")
        val turn2 = ActiveTurnIdentity("r2", "t2")
        owner.start(turn1)
        var invoked = false
        owner.awaitApproval(turn1, ActiveTurnApproval(ToolApprovalId("req-1"), ToolCallId("call-1")) {
            invoked = true
            true
        })
        assertEquals(ActiveTurnPhase.AwaitingInput, owner.state.value.phase)

        val accepted = owner.submitApproval(turn2, ToolApprovalId("req-1"), true)
        assertEquals(false, accepted)
        assertEquals(false, invoked)
        assertEquals(ActiveTurnPhase.AwaitingInput, owner.state.value.phase)
    }
}
