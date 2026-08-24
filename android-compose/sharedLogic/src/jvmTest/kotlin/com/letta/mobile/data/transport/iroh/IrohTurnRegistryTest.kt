package com.letta.mobile.data.transport.iroh

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IrohTurnRegistryTest {
    private val registry = IrohTurnRegistry()

    @Test
    fun tryStartRegistersNewTurnWhenIdle() {
        val result = registry.tryStart(request())
        assertTrue(result is IrohTryStartResult.Started)
        assertEquals("conv-1", result.turn.conversationId)
        assertEquals("turn-1", result.turn.turnId)
        assertEquals("run-1", result.turn.runId)
        assertTrue(registry.hasActiveTurn(conversationId()))
        assertTrue(registry.hasAnyActiveTurn)
    }

    @Test
    fun tryStartRejectsWhenTurnAlreadyActive() {
        val first = registry.tryStart(request())
        assertTrue(first is IrohTryStartResult.Started)

        val second = registry.tryStart(request(turnId = "turn-2", runId = "run-2"))
        assertTrue(second is IrohTryStartResult.Busy)
        assertEquals("turn-1", second.activeTurn.turnId)
        assertEquals("turn-2", second.rejectedToken.turnId.value)
    }

    @Test
    fun promoteRunIdUpdatesSyntheticToRealRunId() {
        val start = registry.tryStart(request(runId = "iroh-run-synth-1"))
        assertTrue(start is IrohTryStartResult.Started)

        val promoted = registry.promoteRunId(IrohRunPromotion(start.turn.token, IrohRunId("real-run-123")))
        assertTrue(promoted)
        assertEquals("real-run-123", registry.getActiveTurn(conversationId())?.runId)

        val secondPromotion = registry.promoteRunId(IrohRunPromotion(start.turn.token, IrohRunId("real-run-456")))
        assertFalse(secondPromotion)
    }

    @Test
    fun claimedTerminalStaysActiveUntilOwnerPublishesAndRetires() {
        val start = registry.tryStart(request())
        assertTrue(start is IrohTryStartResult.Started)
        val turn = start.turn
        val enginePublication = publication(turn, IrohTerminalSource.Engine)

        assertTrue(registry.claimTerminal(enginePublication))
        assertTrue(turn.hasTerminal)
        assertEquals(IrohTerminalSource.Engine, turn.terminalSource)
        assertFalse(turn.terminalReached.isCompleted)
        assertEquals(turn, registry.getActiveTurn(conversationId()))
        assertTrue(registry.hasActiveTurn(conversationId()))
        assertFalse(registry.claimTerminal(publication(turn, IrohTerminalSource.Observer)))

        assertTrue(registry.retireClaimed(enginePublication))
        assertTrue(turn.terminalReached.isCompleted)
        assertNull(registry.getActiveTurn(conversationId()))
        assertFalse(registry.hasActiveTurn(conversationId()))
        assertTrue(registry.isRetiredRun(IrohRunId("run-1")))
    }

    @Test
    fun finishWithMatchingTokenRemovesTurn() {
        val start = registry.tryStart(request())
        assertTrue(start is IrohTryStartResult.Started)

        assertTrue(registry.finish(start.turn.token))
        assertNull(registry.getActiveTurn(conversationId()))
    }

    @Test
    fun clearCancelsJobsAndCompletesActiveTurns() {
        val start = registry.tryStart(request())
        assertTrue(start is IrohTryStartResult.Started)
        val job = Job()
        registry.registerSendJob(IrohSendJobRegistration(conversationId(), job))

        registry.clear()

        assertTrue(job.isCancelled)
        assertTrue(start.turn.terminalReached.isCompleted)
        assertFalse(registry.hasAnyActiveTurn)
        assertEquals(0, registry.activeTurnsCount())
        assertEquals(0, registry.activeSendJobsCount())
    }

    @Test
    fun disconnectClaimsTerminalBeforeCancellingJob() {
        val start = registry.tryStart(request())
        assertTrue(start is IrohTryStartResult.Started)
        val job = Job()
        registry.registerSendJob(IrohSendJobRegistration(conversationId(), job))

        val claimed = registry.claimDisconnectTerminals()
        registry.cancelSendJobs()

        assertEquals(listOf(start.turn), claimed)
        assertEquals(IrohTerminalSource.Disconnect, start.turn.terminalSource)
        assertTrue(job.isCancelled)
        assertTrue(registry.retireClaimed(publication(start.turn, IrohTerminalSource.Disconnect)))
        assertTrue(start.turn.terminalReached.isCompleted)
        assertFalse(registry.hasAnyActiveTurn)
    }

    private fun conversationId() = IrohConversationId("conv-1")

    private fun request(
        turnId: String = "turn-1",
        runId: String = "run-1",
    ) = IrohTurnRequest(
        token = IrohTurnToken(conversationId(), generation = 1L, turnId = IrohTurnId(turnId)),
        runId = IrohRunId(runId),
        agentId = IrohAgentId("agent-1"),
    )

    private fun publication(turn: IrohActiveTurn, source: IrohTerminalSource) = IrohTerminalPublication(
        turn = turn,
        status = IrohTerminalStatus("completed"),
        source = source,
    )
}
