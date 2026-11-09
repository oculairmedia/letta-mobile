package com.letta.mobile.data.presence

import com.letta.mobile.data.serialization.LettaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class ActiveTurnContractTest {
    @Test
    fun fixtureDecodesRoundTripsAndArbitratesEveryBoundary() {
        val cases = LettaJson.wire.decodeFromString<List<TurnCase>>(activeTurnContractText)
        assertEquals(7, cases.size)
        cases.forEach { case ->
            var state = case.initial
            case.steps.forEachIndexed { index, step ->
                state = arbitrateActiveTurn(state, step.candidate, step.boundary)
                assertEquals(step.expected, state, "${case.name} step $index")
                assertEquals(state, LettaJson.persisted.decodeFromString<ActiveTurnState>(
                    LettaJson.persisted.encodeToString(state),
                ))
            }
        }
    }

    @Test
    fun conversationDeletionClearsActiveTurnEvenWithoutRunOrTurnCorrelation() {
        val active = ActiveTurnState(
            conversationId = com.letta.mobile.data.model.ConversationId("conv-1"),
            agentId = com.letta.mobile.data.model.AgentId("agent-1"),
            runId = "run-1",
            turnId = "turn-1",
            phase = ActiveTurnPhase.ToolExecuting,
            activeTool = "Bash",
            subagentCount = 1,
            startedAtEpochMs = 1234L,
        )
        val deleteCandidate = ActiveTurnState(
            conversationId = com.letta.mobile.data.model.ConversationId("conv-1"),
            agentId = com.letta.mobile.data.model.AgentId("agent-1"),
            runId = null,
            turnId = null,
        )
        val result = arbitrateActiveTurn(active, deleteCandidate, ActiveTurnBoundary.Delete)
        assertEquals(ActiveTurnPhase.Idle, result.phase)
        assertEquals(null, result.activeTool)
        assertEquals(0, result.subagentCount)
        assertEquals(null, result.startedAtEpochMs)
    }

    @Test
    fun idleCandidateOnProgressOrApprovalResolvedNeverRetainsStartTime() {
        val awaiting = ActiveTurnState(
            conversationId = com.letta.mobile.data.model.ConversationId("conv-1"),
            agentId = com.letta.mobile.data.model.AgentId("agent-1"),
            runId = "run-1",
            turnId = "turn-1",
            phase = ActiveTurnPhase.AwaitingInput,
            startedAtEpochMs = 1234L,
        )
        val idleCandidate = ActiveTurnState(
            conversationId = com.letta.mobile.data.model.ConversationId("conv-1"),
            agentId = com.letta.mobile.data.model.AgentId("agent-1"),
            runId = "run-1",
            turnId = "turn-1",
            phase = ActiveTurnPhase.Idle,
            startedAtEpochMs = null,
        )
        val resolved = arbitrateActiveTurn(awaiting, idleCandidate, ActiveTurnBoundary.ApprovalResolved)
        assertEquals(ActiveTurnPhase.Idle, resolved.phase)
        assertEquals(null, resolved.startedAtEpochMs)

        val thinking = awaiting.copy(phase = ActiveTurnPhase.Thinking)
        val progressed = arbitrateActiveTurn(thinking, idleCandidate, ActiveTurnBoundary.Progress)
        assertEquals(ActiveTurnPhase.Thinking, progressed.phase, "progress cannot force idle; terminal boundary is required")
        assertEquals(1234L, progressed.startedAtEpochMs)
    }

    @Serializable
    private data class TurnCase(val name: String, val initial: ActiveTurnState, val steps: List<Step>)

    @Serializable
    private data class Step(
        val boundary: ActiveTurnBoundary,
        val candidate: ActiveTurnState,
        val expected: ActiveTurnState,
    )
}
