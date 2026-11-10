package com.letta.mobile.data.presence

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.Run
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ActiveTurnSessionTest {
    private val conversation = ConversationId("conversation")
    private val agent = AgentId("agent")

    @Test
    fun droppedTerminalIsReconciledWithoutAStopOrAResend() = runTest {
        var status = "running"
        val reads = mutableListOf<RunId?>()
        val session = ActiveTurnSession(backgroundScope, now = { testScheduler.currentTime }, readRuns = { c, a, run ->
            assertEquals(conversation, c)
            assertEquals(agent, a)
            assertEquals(RunId("server-run"), run)
            reads += run
            listOf(Run("server-run", agent.value, conversationId = conversation.value, status = status))
        })
        val owner = session.owner(conversation, agent)
        listOf(draft(RuntimeRunStatus.Started), draft(RuntimeRunStatus.Running)).asFlow().collect { event ->
            session.onEvent(ActiveTurnRuntimeEvent(conversation, agent, event.payload, RunId("command"), event.runId))
        }
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
        status = "completed" // The terminal frame is deliberately dropped, but the persisted run closes.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(ActiveTurnPhase.Idle, owner.state.value.phase)
        assertEquals(ActiveTurnTerminalKind.Finished, owner.outcome.value)
        assertEquals<List<RunId?>>(listOf(RunId("server-run")), reads)
        session.close()
    }

    @Test
    fun presentationReplacementRetainsOwnerAndSecondStopDispatchesOnlyOnce() = runTest {
        val session = ActiveTurnSession(backgroundScope, now = { testScheduler.currentTime })
        val first = session.owner(conversation, agent)
        session.onEvent(ActiveTurnRuntimeEvent(conversation, agent, draft(RuntimeRunStatus.Started).payload,
            RunId("command"), RunId("server-run")))
        val replacement = session.owner(conversation, agent)
        assertSame(first, replacement)
        assertEquals(ActiveTurnPhase.Thinking, replacement.state.value.phase)
        val identity = ActiveTurnIdentity("command", "runtime:command")
        var aborts = 0
        replacement.interrupt(identity, "press-one") { aborts++; true }
        runCurrent()
        replacement.interrupt(identity, "press-two") { aborts++; true }
        assertEquals(ActiveTurnPhase.Idle, replacement.state.value.phase)
        assertEquals(1, aborts)
        session.onEvent(ActiveTurnRuntimeEvent(conversation, agent, draft(RuntimeRunStatus.Started).payload,
            RunId("server-run"), RunId("server-run")))
        assertEquals(ActiveTurnPhase.Idle, replacement.state.value.phase, "Late alias start must remain retired")
        session.close()
    }

    @Test
    fun restorationReconcilesBeforeExposingBusyAndDoesNotDispatchEffects() = runTest {
        var reads = 0
        val session = ActiveTurnSession(backgroundScope, readRuns = { c, a, run ->
            assertEquals(conversation, c)
            assertEquals(agent, a)
            assertEquals(null, run)
            reads++
            listOf(Run("restored-run", agent.value, conversationId = conversation.value, status = "running"))
        })
        session.reconcile(conversation, agent)
        val owner = session.owner(conversation, agent)
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
        assertEquals("restored-run", owner.state.value.runId)
        assertEquals(1, reads)
        session.close()
    }

    @Test
    fun terminalQueryForOldTurnCannotCloseItsReplacement() = runTest {
        val response = CompletableDeferred<List<Run>>()
        val session = ActiveTurnSession(backgroundScope, readRuns = { _, _, run ->
            assertEquals(RunId("server-run"), run)
            response.await()
        })
        session.onEvent(ActiveTurnRuntimeEvent(conversation, agent, draft(RuntimeRunStatus.Started).payload,
            RunId("old"), RunId("server-run")))
        val query = launch { session.reconcile(conversation, agent) }
        runCurrent()
        session.onEvent(ActiveTurnRuntimeEvent(conversation, agent, draft(RuntimeRunStatus.Started).payload,
            RunId("new"), RunId("new-server")))
        response.complete(listOf(Run("server-run", agent.value, conversationId = conversation.value, status = "completed")))
        query.join()
        assertEquals("new", session.owner(conversation, agent).state.value.runId)
        assertEquals(ActiveTurnPhase.Thinking, session.owner(conversation, agent).state.value.phase)
        session.delete(conversation)
        session.onEvent(ActiveTurnRuntimeEvent(conversation, agent, draft(RuntimeRunStatus.Started).payload,
            RunId("after-delete"), RunId("later-server")))
        assertEquals(ActiveTurnPhase.Idle, session.owner(conversation, agent).state.value.phase)
        assertTrue(session.states.value.values.all { it.phase == ActiveTurnPhase.Idle })
        session.close()
    }

    @Test
    fun boundRegistryCannotResurrectBusyAfterLocalClear() = runTest {
        val session = ActiveTurnSession(backgroundScope)
        val owner = session.owner(conversation, agent)
        val registry = ConversationRunRegistry()
        val publisher = ConversationRunPhasePublisher(registry)
        publisher.bindActiveTurn(owner)
        session.onEvent(ActiveTurnRuntimeEvent(conversation, agent, draft(RuntimeRunStatus.Started).payload, RunId("command")))
        publisher.onEvent(RunScope(conversation.value, agent.value), draft(RuntimeRunStatus.Started).payload)
        runCurrent()
        assertTrue(registry.runs.value.getValue(conversation.value).running)
        val identity = ActiveTurnIdentity("command", "runtime:command")
        owner.interrupt(identity, "first") { true }
        runCurrent()
        owner.interrupt(identity, "second") { true }
        runCurrent()
        publisher.onEvent(RunScope(conversation.value, agent.value), draft(RuntimeRunStatus.Started).payload)
        assertEquals(RunPhase.IDLE, registry.runs.value.getValue(conversation.value).phase)
        registry.publish(registry.stateOf(conversation.value, agent.value).copy(phase = RunPhase.WORKING))
        assertEquals(RunPhase.IDLE, registry.runs.value.getValue(conversation.value).phase,
            "An independently published detail cannot override the locally closed owner")
        publisher.clear(conversation.value)
        assertEquals(RunPhase.IDLE, registry.runs.value.getValue(conversation.value).phase)
        session.close()
    }

    @Test
    fun secondStopBeforeCorrelationCancelsThePendingAbortWait() = runTest {
        val session = ActiveTurnSession(backgroundScope)
        val owner = session.owner(conversation, agent)
        session.onEvent(ActiveTurnRuntimeEvent(conversation, agent, draft(RuntimeRunStatus.Started).payload, RunId("command")))
        val identity = ActiveTurnIdentity("command", "runtime:command")
        var aborts = 0
        owner.interrupt(identity, "first") {
            session.serverRunId(conversation, identity)?.let { aborts++; true } ?: false
        }
        runCurrent()
        assertEquals(ActiveTurnPhase.Cancelling, owner.state.value.phase)
        owner.interrupt(identity, "second") { aborts++; true }
        runCurrent()
        session.onEvent(ActiveTurnRuntimeEvent(conversation, agent, draft(RuntimeRunStatus.Running).payload,
            RunId("command"), RunId("server-run")))
        runCurrent()
        assertEquals(0, aborts)
        assertEquals(ActiveTurnPhase.Idle, owner.state.value.phase)
        session.close()
    }

    @Test
    fun terminalReadbackClosesSameTurnAfterProgressChanges() = runTest {
        val response = CompletableDeferred<List<Run>>()
        val session = ActiveTurnSession(backgroundScope, readRuns = { c, a, run ->
            assertEquals(conversation, c)
            assertEquals(agent, a)
            assertEquals(RunId("server-run"), run)
            response.await()
        })
        session.onEvent(ActiveTurnRuntimeEvent(conversation, agent, draft(RuntimeRunStatus.Started).payload,
            RunId("command"), RunId("server-run")))
        val query = launch { session.reconcile(conversation, agent) }
        runCurrent()
        val owner = session.owner(conversation, agent)
        owner.progress(ActiveTurnIdentity("command", "runtime:command"), mapOf("call" to "Bash"))
        response.complete(listOf(Run("server-run", agent.value, conversationId = conversation.value, status = "completed")))
        query.join()
        assertEquals(ActiveTurnPhase.Idle, owner.state.value.phase,
            "Authoritative closure of the captured turn outranks its intervening progress")
        session.close()
    }

    private fun draft(status: RuntimeRunStatus) = RuntimeEventDraft(
        BackendId("backend"), RuntimeId("runtime"), agent,
        com.letta.mobile.runtime.ConversationId(conversation.value), RunId("server-run"),
        RuntimeEventSource.ExternalTransport, RuntimeEventPayload.RunLifecycleChanged(status),
    )
}
