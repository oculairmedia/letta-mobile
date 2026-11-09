package com.letta.mobile.data.presence

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
        owner.progress("r", "t", mapOf("call" to "Bash"))
        assertEquals(ActiveTurnPhase.Idle, owner.state.value.phase)
        owner.start("r", "t")
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
        advanceTimeBy(10)
        owner.progress("r", "t", linkedMapOf("z" to "First", "a" to "Second"), 2)
        assertEquals("First", owner.state.value.activeTool)
        assertEquals(0L, owner.state.value.startedAtEpochMs)
        var aborts = 0
        owner.interrupt("r", "t", "press1") { aborts++; true }
        runCurrent()
        owner.interrupt("r", "t", "press1") { aborts++; true }
        owner.progress("r", "t", emptyMap())
        assertEquals(ActiveTurnPhase.Cancelling, owner.state.value.phase)
        assertEquals(1, aborts)
        owner.interrupt("r", "t", "press2") { aborts++; true }
        assertEquals(ActiveTurnPhase.Idle, owner.state.value.phase)
        assertEquals(null, owner.state.value.activeTool)
        assertEquals(0, owner.state.value.subagentCount)
        owner.start("r2", "t2")
        owner.terminate("r", "t", ActiveTurnTerminalKind.Finished)
        owner.start("r", "t")
        assertEquals("r2", owner.state.value.runId)
    }

    @Test
    fun `approval waits for acceptance and deduplicates pending submission`() = runTest {
        val owner = ActiveTurnCoordinator(ConversationId("c"), AgentId("a"), backgroundScope) { testScheduler.currentTime }
        val accepted = CompletableDeferred<Boolean>()
        owner.start("r", "t")
        owner.awaitApproval("r", "t", "request", "call") { accepted.await() }
        val submission = async { owner.submitApproval("request", true) }
        runCurrent()
        assertEquals(ActiveTurnPhase.AwaitingInput, owner.state.value.phase)
        assertFalse(owner.submitApproval("request", true))
        owner.progress("r", "t", emptyMap(), 3)
        assertEquals(3, owner.state.value.subagentCount)
        accepted.complete(true)
        assertTrue(submission.await())
        assertEquals(ActiveTurnPhase.ToolExecuting, owner.state.value.phase)
        assertFalse(owner.submitApproval("request", true))
    }

    @Test
    fun `rejected approval stays parked and denial recomputes tools`() = runTest {
        val owner = ActiveTurnCoordinator(ConversationId("c"), AgentId("a"), backgroundScope) { testScheduler.currentTime }
        owner.start("r", "t")
        var calls = 0
        owner.awaitApproval("r", "t", "request", "call") { ++calls > 1 }
        assertFalse(owner.submitApproval("request", false))
        assertEquals(ActiveTurnPhase.AwaitingInput, owner.state.value.phase)
        assertTrue(owner.submitApproval("request", false))
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
    }

    @Test
    fun `timeout clears at first press deadline and stale effects cannot clear replacement`() = runTest {
        val owner = ActiveTurnCoordinator(ConversationId("c"), AgentId("a"), backgroundScope) { testScheduler.currentTime }
        owner.start("r", "t")
        val abort = CompletableDeferred<Boolean>()
        owner.interrupt("r", "t", "press") { abort.await() }
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(ActiveTurnPhase.Idle, owner.state.value.phase)
        owner.start("new", "new")
        abort.complete(false)
        runCurrent()
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
    }

    @Test
    fun `terminal before start and deletion permanently fence callbacks`() = runTest {
        val owner = ActiveTurnCoordinator(ConversationId("c"), AgentId("a"), backgroundScope) { testScheduler.currentTime }
        owner.terminate("old", "old", ActiveTurnTerminalKind.Finished)
        owner.start("old", "old")
        assertEquals(ActiveTurnPhase.Idle, owner.state.value.phase)
        owner.start("new", "new")
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
        owner.delete()
        owner.start("later", "later")
        owner.progress("new", "new", mapOf("call" to "Bash"))
        assertEquals(ActiveTurnPhase.Idle, owner.state.value.phase)
        assertEquals(null, owner.state.value.startedAtEpochMs)
    }
    @Test
    fun `multiple approvals preserve priority and accepted requests cannot replay`() = runTest {
        val owner = ActiveTurnCoordinator(ConversationId("c"), AgentId("a"), backgroundScope) { testScheduler.currentTime }
        owner.start("r", "t")
        owner.progress("r", "t", linkedMapOf("first" to "First", "second" to "Second"))
        owner.awaitApproval("r", "t", "one", "first") { true }
        owner.awaitApproval("r", "t", "two", "second") { true }
        assertTrue(owner.submitApproval("one", false))
        assertEquals(ActiveTurnPhase.AwaitingInput, owner.state.value.phase)
        assertEquals("Second", owner.state.value.activeTool)
        assertTrue(owner.submitApproval("two", false))
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
        owner.awaitApproval("r", "t", "one", "first") { error("retired request dispatched") }
        assertFalse(owner.submitApproval("one", true))
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
    }

    @Test
    fun `old approval completion and mismatched deletion cannot affect replacement`() = runTest {
        val owner = ActiveTurnCoordinator(ConversationId("c"), AgentId("a"), backgroundScope) { testScheduler.currentTime }
        val acceptance = CompletableDeferred<Boolean>()
        owner.start("r", "t")
        owner.awaitApproval("r", "t", "request", "call") { acceptance.await() }
        val pending = async { owner.submitApproval("request", true) }
        runCurrent()
        owner.start("replacement", "replacement")
        acceptance.complete(true)
        assertFalse(pending.await())
        owner.terminate("r", "t", ActiveTurnTerminalKind.Deleted)
        assertEquals("replacement", owner.state.value.runId)
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
        owner.terminate("replacement", "replacement", ActiveTurnTerminalKind.Failed)
        assertEquals(ActiveTurnTerminalKind.Failed, owner.outcome.value)
        owner.terminate("replacement", "replacement", ActiveTurnTerminalKind.Finished)
        assertEquals(ActiveTurnTerminalKind.Failed, owner.outcome.value)
        owner.start("next", "next")
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
    }

    @Test
    fun `concurrent progress publishes whole snapshots and reserves a single approval`() = runTest {
        val owner = ActiveTurnCoordinator(ConversationId("c"), AgentId("a"), backgroundScope) { testScheduler.currentTime }
        owner.start("r", "t")
        val acceptance = CompletableDeferred<Boolean>()
        var submissions = 0
        owner.awaitApproval("r", "t", "request", "call") { submissions++; acceptance.await() }
        val jobs = List(20) { async(kotlinx.coroutines.Dispatchers.Default) { owner.submitApproval("request", true) } }
        val progress = List(20) { index -> async(kotlinx.coroutines.Dispatchers.Default) {
            owner.progress("r", "t", mapOf("tool" to "Bash"), index)
        } }
        progress.forEach { it.await() }
        assertEquals(ActiveTurnPhase.AwaitingInput, owner.state.value.phase)
        assertEquals("Bash", owner.state.value.activeTool)
        acceptance.complete(true)
        assertEquals(1, jobs.count { it.await() })
        assertEquals(1, submissions)
        assertEquals(ActiveTurnPhase.ToolExecuting, owner.state.value.phase)
    }

}
