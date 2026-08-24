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
        val result = startTurn()
        assertTrue(result is IrohTryStartResult.Started)
        assertEquals("conv-1", result.turn.conversationId)
        assertEquals("turn-1", result.turn.turnId)
        assertEquals("run-1", result.turn.runId)
        assertTrue(registry.hasActiveTurn("conv-1"))
        assertTrue(registry.hasAnyActiveTurn)
    }

    @Test
    fun tryStartRejectsWhenTurnAlreadyActive() {
        val first = startTurn()
        assertTrue(first is IrohTryStartResult.Started)
        val second = startTurn(turnId = "turn-2", initialRunId = "run-2")
        assertTrue(second is IrohTryStartResult.Busy)
        assertEquals("turn-1", second.activeTurn.turnId)
        assertEquals("turn-2", second.rejectedTurnId)
    }

    @Test
    fun promoteRunIdUpdatesSyntheticToRealRunId() {
        val start = startTurn(initialRunId = "iroh-run-synth-1")
        assertTrue(start is IrohTryStartResult.Started)
        assertTrue(registry.promoteRunId("conv-1", "turn-1", "real-run-123"))
        assertEquals("real-run-123", registry.getActiveTurn("conv-1")?.runId)
        assertFalse(registry.promoteRunId("conv-1", "turn-1", "real-run-456"))
    }

    @Test
    fun publishTerminalClaimsExactlyOnceAndRetires() {
        val start = startTurn(initialRunId = "real-run-1")
        assertTrue(start is IrohTryStartResult.Started)
        val turn = start.turn
        assertTrue(registry.publishTerminal(turn, status = "completed", source = "engine"))
        assertTrue(turn.hasTerminal)
        assertEquals("engine", turn.terminalSource)
        assertTrue(turn.terminalReached.isCompleted)
        assertNull(registry.getActiveTurn("conv-1"))
        assertFalse(registry.hasActiveTurn("conv-1"))
        assertTrue(registry.isRetiredRun("real-run-1"))
        assertFalse(registry.publishTerminal(turn, status = "completed", source = "observer"))
    }

    @Test
    fun finishWithMatchingTokenRemovesTurn() {
        val start = startTurn()
        assertTrue(start is IrohTryStartResult.Started)
        assertTrue(registry.finish(start.turn.token))
        assertNull(registry.getActiveTurn("conv-1"))
    }

    @Test
    fun clearCancelsJobsAndCompletesActiveTurns() {
        val start = startTurn()
        assertTrue(start is IrohTryStartResult.Started)
        val job = Job()
        registry.registerSendJob("conv-1", job)
        registry.clear()
        assertTrue(job.isCancelled)
        assertTrue(start.turn.terminalReached.isCompleted)
        assertFalse(registry.hasAnyActiveTurn)
        assertEquals(0, registry.activeTurnsCount())
        assertEquals(0, registry.activeSendJobsCount())
    }

    private fun startTurn(
        turnId: String = "turn-1",
        initialRunId: String = "run-1",
    ): IrohTryStartResult = registry.tryStart(
        token = IrohTurnToken("conv-1", 1L, turnId),
        initialRunId = initialRunId,
        agentId = "agent-1",
    )
}
