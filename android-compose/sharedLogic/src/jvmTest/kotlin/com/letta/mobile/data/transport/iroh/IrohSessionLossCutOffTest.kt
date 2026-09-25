package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-qygvv.16: a turn in flight when its Iroh session closes (wrapper restart, redial)
 * ends within a bounded time with exactly one terminal, and its run stays open to the observer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IrohSessionLossCutOffTest {

    @Test
    fun turnWithoutOwnTerminalGetsOneSyntheticFailedTerminalWithinGrace() = runTest {
        val fixture = fixture()
        val turn = fixture.start()
        val job = fixture.registerSendJob(turn) { awaitCancellation() }

        fixture.cutOff.cutOff("connection_lost")
        advanceTimeBy(GRACE_MS - 1)
        assertTrue(fixture.frames.isEmpty(), "the engine keeps the whole grace window to end the turn itself")
        advanceTimeBy(2)
        runCurrent()

        assertEquals(listOf("failed"), fixture.turnDones().map { it.status })
        val error = fixture.frames.filterIsInstance<ServerFrame.Error>().single()
        assertEquals(TurnFailureNotices.CONNECTION_LOST_KIND, error.code)
        assertEquals(IrohTerminalSource.SessionLost, turn.terminalSource)
        assertTrue(job.isCancelled)
        assertFalse(fixture.registry.hasActiveTurn(CONVERSATION))
    }

    @Test
    fun engineTerminalInsideGraceIsTheOnlyTerminal() = runTest {
        val fixture = fixture()
        val turn = fixture.start()
        val job = fixture.registerSendJob(turn) {
            // The engine sees its stream detach and publishes its own terminal.
            delay(10)
            fixture.publishEngineTerminal(turn, "failed")
            awaitCancellation()
        }

        fixture.cutOff.cutOff("connection_lost")
        advanceUntilIdle()

        assertEquals(listOf("failed"), fixture.turnDones().map { it.status })
        assertEquals(IrohTerminalSource.Engine, turn.terminalSource)
        assertTrue(fixture.frames.none { it is ServerFrame.Error }, "no synthetic error after the engine's own terminal")
        assertTrue(job.isCancelled)
    }

    @Test
    fun terminalJustBeforeSessionLossIsNotDuplicated() = runTest {
        val fixture = fixture()
        val turn = fixture.start()
        val job = fixture.registerSendJob(turn) { awaitCancellation() }
        fixture.publishEngineTerminal(turn, "completed")
        advanceTimeBy(10)

        fixture.cutOff.cutOff("connection_lost")
        advanceUntilIdle()

        assertEquals(listOf("completed"), fixture.turnDones().map { it.status })
        assertTrue(job.isCancelled)
    }

    @Test
    fun cutOffRunStaysOpenToTheObserverAfterRedial() = runTest {
        val fixture = fixture()
        val turn = fixture.start()
        fixture.registerSendJob(turn) { awaitCancellation() }
        fixture.cutOff.cutOff("connection_lost")
        advanceUntilIdle()
        fixture.frames.clear()

        // The wrapper still runs the turn: after the redial its frames reach the observer.
        val observer = IrohObserverIngestor(
            scope = backgroundScope,
            turnRegistry = fixture.registry,
            connectionGeneration = { 2L },
            emitBoth = { fixture.frames += it },
            adminRpc = { _, _, _ -> AppServerInboundFrame.AdminRpcResponse("req", true, buildJsonObject { }, null) },
            recordFrameOwnership = { _, _ -> },
        )
        observer.ingestObserverFrame(ObserverFrameRequest(continuedAssistantDelta()))

        assertFalse(fixture.registry.isRetiredRun(IrohRunId(RUN_ID)), "a cut-off run is not fenced as retired")
        assertFalse(fixture.registry.hasActiveTurn(CONVERSATION), "no phantom turn is left behind")
        assertTrue(fixture.frames.isNotEmpty(), "the observer re-attaches to the still-running run")
        assertTrue(fixture.turnDones().isEmpty())
    }

    @Test
    fun normallyRetiredRunIsStillFencedFromTheObserver() = runTest {
        val fixture = fixture()
        val turn = fixture.start()
        fixture.publishEngineTerminal(turn, "completed")

        assertTrue(fixture.registry.isRetiredRun(IrohRunId(RUN_ID)))
    }

    private class Fixture(val scope: TestScope) {
        val registry = IrohTurnRegistry()
        val frames = CopyOnWriteArrayList<ServerFrame>()
        val cutOff = IrohSessionLossCutOff(scope, registry, emitBoth = { frames += it }, graceMs = GRACE_MS)

        fun start(): IrohActiveTurn {
            val request = IrohTurnRequest(
                token = IrohTurnToken(CONVERSATION, 1L, IrohTurnId("iroh-turn-1")),
                runId = IrohRunId(RUN_ID),
                agentId = IrohAgentId("agent-1"),
            )
            return (registry.tryStart(request) as IrohTryStartResult.Started).turn
        }

        fun registerSendJob(turn: IrohActiveTurn, body: suspend () -> Unit): Job {
            val job = scope.backgroundScope.launch { body() }
            turn.job = job
            registry.registerSendJob(IrohSendJobRegistration(turn.token.conversationId, job))
            return job
        }

        suspend fun publishEngineTerminal(turn: IrohActiveTurn, status: String) {
            val publication = IrohTerminalPublication(turn, IrohTerminalStatus(status), IrohTerminalSource.Engine)
            if (!registry.claimTerminal(publication)) return
            frames += ServerFrame.TurnDone(id = "td", ts = "ts", turnId = turn.turnId, runId = turn.runId, status = status)
            registry.retireClaimed(publication)
        }

        fun turnDones(): List<ServerFrame.TurnDone> = frames.filterIsInstance<ServerFrame.TurnDone>()
    }

    private fun TestScope.fixture() = Fixture(this)

    private fun continuedAssistantDelta() = AppServerProtocol.decodeFrame(
        """
        {"type":"stream_delta","runtime":{"agent_id":"agent-1","conversation_id":"${CONVERSATION.value}"},
         "event_seq":7,"emitted_at":"2026-09-25T00:00:07Z","idempotency_key":"evt-continued",
         "delta":{"id":"letta-msg-9","message_type":"assistant_message","content":"still going","run_id":"$RUN_ID"}}
        """.trimIndent(),
        AppServerChannel.Stream,
    )

    private companion object {
        const val GRACE_MS = 2_000L
        const val RUN_ID = "local-run-37"
        val CONVERSATION = IrohConversationId("conv-1")
    }
}
