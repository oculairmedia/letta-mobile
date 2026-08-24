package com.letta.mobile.data.transport.iroh

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IrohTurnRegistryTest {

    private val registry = IrohTurnRegistry()

    @Test
    fun tryStartRegistersNewTurnWhenIdle() {
        val result = registry.tryStart(
            conversationId = "conv-1",
            turnId = "turn-1",
            initialRunId = "run-1",
            agentId = "agent-1",
            generation = 1L,
        )
        assertTrue(result is IrohTryStartResult.Started)
        assertEquals("conv-1", result.turn.conversationId)
        assertEquals("turn-1", result.turn.turnId)
        assertEquals("run-1", result.turn.runId)
        assertTrue(registry.hasActiveTurn("conv-1"))
        assertTrue(registry.hasAnyActiveTurn)
    }

    @Test
    fun tryStartRejectsWhenTurnAlreadyActive() {
        val first = registry.tryStart(
            conversationId = "conv-1",
            turnId = "turn-1",
            initialRunId = "run-1",
            agentId = "agent-1",
            generation = 1L,
        )
        assertTrue(first is IrohTryStartResult.Started)

        val second = registry.tryStart(
            conversationId = "conv-1",
            turnId = "turn-2",
            initialRunId = "run-2",
            agentId = "agent-1",
            generation = 1L,
        )
        assertTrue(second is IrohTryStartResult.Busy)
        assertEquals("turn-1", second.activeTurn.turnId)
        assertEquals("turn-2", second.rejectedTurnId)
    }

    @Test
    fun promoteRunIdUpdatesSyntheticToRealRunId() {
        val start = registry.tryStart(
            conversationId = "conv-1",
            turnId = "turn-1",
            initialRunId = "iroh-run-synth-1",
            agentId = "agent-1",
            generation = 1L,
        )
        assertTrue(start is IrohTryStartResult.Started)

        val promoted = registry.promoteRunId("conv-1", "turn-1", "real-run-123")
        assertTrue(promoted)
        assertEquals("real-run-123", registry.getActiveTurn("conv-1")?.runId)

        val secondPromotion = registry.promoteRunId("conv-1", "turn-1", "real-run-456")
        assertFalse(secondPromotion)
    }

    @Test
    fun publishTerminalClaimsExactlyOnceAndRetires() {
        val start = registry.tryStart(
            conversationId = "conv-1",
            turnId = "turn-1",
            initialRunId = "real-run-1",
            agentId = "agent-1",
            generation = 1L,
        )
        assertTrue(start is IrohTryStartResult.Started)
        val turn = start.turn

        val published = registry.publishTerminal(turn, status = "completed", source = "engine")
        assertTrue(published)
        assertTrue(turn.hasTerminal)
        assertEquals("engine", turn.terminalSource)
        assertTrue(turn.terminalReached.isCompleted)
        assertNull(registry.getActiveTurn("conv-1"))
        assertFalse(registry.hasActiveTurn("conv-1"))
        assertTrue(registry.isRetiredRun("real-run-1"))

        val secondPublish = registry.publishTerminal(turn, status = "completed", source = "observer")
        assertFalse(secondPublish)
    }

    @Test
    fun finishWithMatchingTokenRemovesTurn() {
        val start = registry.tryStart(
            conversationId = "conv-1",
            turnId = "turn-1",
            initialRunId = "run-1",
            agentId = "agent-1",
            generation = 1L,
        )
        assertTrue(start is IrohTryStartResult.Started)

        val finished = registry.finish(start.turn.token)
        assertTrue(finished)
        assertNull(registry.getActiveTurn("conv-1"))
    }

    @Test
    fun clearCancelsJobsAndCompletesActiveTurns() {
        val start = registry.tryStart(
            conversationId = "conv-1",
            turnId = "turn-1",
            initialRunId = "run-1",
            agentId = "agent-1",
            generation = 1L,
        )
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
}
