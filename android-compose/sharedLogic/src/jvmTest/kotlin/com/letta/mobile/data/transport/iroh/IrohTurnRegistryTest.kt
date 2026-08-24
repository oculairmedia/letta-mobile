package com.letta.mobile.data.transport.iroh

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IrohTurnRegistryTest {
    private val registry = IrohTurnRegistry()
    private fun start(turnId: String = "turn-1", runId: String = "run-1") = registry.tryStart(
        IrohTurnRegistration(IrohTurnToken("conv-1", 1L, turnId), runId, "agent-1"),
    )

    @Test fun tryStartRegistersNewTurnWhenIdle() {
        val result = start()
        assertTrue(result is IrohTryStartResult.Started)
        assertEquals("conv-1", result.turn.conversationId)
        assertEquals("turn-1", result.turn.turnId)
        assertEquals("run-1", result.turn.runId)
        assertTrue(registry.hasActiveTurn("conv-1")); assertTrue(registry.hasAnyActiveTurn)
    }

    @Test fun tryStartRejectsWhenTurnAlreadyActive() {
        assertTrue(start() is IrohTryStartResult.Started)
        val second = start("turn-2", "run-2")
        assertTrue(second is IrohTryStartResult.Busy)
        assertEquals("turn-1", second.activeTurn.turnId); assertEquals("turn-2", second.rejectedTurnId)
    }

    @Test fun promoteRunIdUpdatesSyntheticToRealRunId() {
        assertTrue(start(runId = "iroh-run-synth-1") is IrohTryStartResult.Started)
        assertTrue(registry.promoteRunId("conv-1", "turn-1", "real-run-123"))
        assertEquals("real-run-123", registry.getActiveTurn("conv-1")?.runId)
        assertFalse(registry.promoteRunId("conv-1", "turn-1", "real-run-456"))
    }

    @Test fun publishTerminalClaimsExactlyOnceAndRetires() {
        val turn = (start(runId = "real-run-1") as IrohTryStartResult.Started).turn
        assertTrue(registry.publishTerminal(turn, "completed", "engine"))
        assertTrue(turn.hasTerminal); assertEquals("engine", turn.terminalSource); assertTrue(turn.terminalReached.isCompleted)
        assertNull(registry.getActiveTurn("conv-1")); assertFalse(registry.hasActiveTurn("conv-1")); assertTrue(registry.isRetiredRun("real-run-1"))
        assertFalse(registry.publishTerminal(turn, "completed", "observer"))
    }

    @Test fun finishWithMatchingTokenRemovesTurn() {
        val start = start() as IrohTryStartResult.Started
        assertTrue(registry.finish(start.turn.token)); assertNull(registry.getActiveTurn("conv-1"))
    }

    @Test fun clearCancelsJobsAndCompletesActiveTurns() {
        val start = start() as IrohTryStartResult.Started
        val job = Job(); registry.registerSendJob("conv-1", job); registry.clear()
        assertTrue(job.isCancelled); assertTrue(start.turn.terminalReached.isCompleted); assertFalse(registry.hasAnyActiveTurn)
        assertEquals(0, registry.activeTurnsCount()); assertEquals(0, registry.activeSendJobsCount())
    }
}
