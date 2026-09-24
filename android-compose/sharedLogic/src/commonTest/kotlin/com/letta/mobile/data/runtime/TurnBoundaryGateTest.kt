package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerLoopStatus
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** letta-mobile-qygvv.2: the per-key decisions behind authoritative turn boundaries. */
class TurnBoundaryGateTest {
    private val gate = TurnBoundaryGate().also { it.beginLease(1) }

    @Test
    fun turnFinishedIsAuthoritativeOncePerTurnId() {
        assertEquals(TurnBoundaryDecision.ProjectAuthoritative, gate.decide(turnFinished("turn-1", "run-1"), "run-1", false))
        val replay = assertIs<TurnBoundaryDecision.Drop>(gate.decide(turnFinished("turn-1", "run-1"), "run-1", false))
        assertEquals("duplicate_turn_id", replay.reason)
    }

    @Test
    fun turnFinishedForASupersededRunIsIgnored() {
        val decision = assertIs<TurnBoundaryDecision.Drop>(gate.decide(turnFinished("turn-1", "run-old"), "run-new", false))
        assertEquals("superseded_run", decision.reason)
    }

    @Test
    fun turnFinishedWithoutRunIdOrLeaseRunIsAccepted() {
        assertEquals(TurnBoundaryDecision.ProjectAuthoritative, gate.decide(turnFinished("turn-1", null), "run-1", false))
        assertEquals(TurnBoundaryDecision.ProjectAuthoritative, gate.decide(turnFinished("turn-2", "run-2"), null, false))
    }

    @Test
    fun terminalsForASettledRunCannotEndTheNextLease() {
        gate.noteSettled("run-1")
        gate.beginLease(2)
        assertIs<TurnBoundaryDecision.Drop>(gate.decide(turnFinished("turn-1", "run-1"), null, false))
        assertIs<TurnBoundaryDecision.Drop>(gate.decide(delta("stop_reason", "run-1", stopReason = "end_turn"), null, false))
        // Non-terminal frames of that run still project.
        assertEquals(TurnBoundaryDecision.Project, gate.decide(delta("assistant_message", "run-1"), null, false))
    }

    @Test
    fun idleLoopStatusBeforeEvidenceDoesNotComplete() {
        assertEquals(TurnBoundaryDecision.Project, gate.decide(loopStatus("WAITING_ON_INPUT"), null, false))
    }

    @Test
    fun idleLoopStatusAfterEvidenceCompletes() {
        gate.decide(delta("assistant_message", "run-1"), null, false)
        val idle = assertIs<TurnBoundaryDecision.LoopIdle>(gate.decide(loopStatus("WAITING_ON_INPUT"), "run-1", false))
        assertEquals(RuntimeRunStatus.Completed, idle.status)
    }

    @Test
    fun idleLoopStatusAfterAbortCancels() {
        gate.decide(delta("assistant_message", "run-1"), null, false)
        gate.noteAbortRequested()
        val idle = assertIs<TurnBoundaryDecision.LoopIdle>(gate.decide(loopStatus("WAITING_ON_INPUT"), "run-1", false))
        assertEquals(RuntimeRunStatus.Cancelled, idle.status)
    }

    @Test
    fun approvalWaitAndActiveRunsNeverComplete() {
        gate.decide(delta("stop_reason", "run-1", stopReason = "requires_approval"), null, false)
        assertEquals(TurnBoundaryDecision.Project, gate.decide(loopStatus("WAITING_ON_APPROVAL"), "run-1", false))
        assertEquals(TurnBoundaryDecision.Project, gate.decide(loopStatus("WAITING_ON_INPUT", listOf("run-1")), "run-1", false))
        assertEquals(TurnBoundaryDecision.Project, gate.decide(loopStatus("WAITING_ON_INPUT"), "run-1", true))
    }

    @Test
    fun newLeaseResetsEvidenceAndAbort() {
        gate.decide(delta("assistant_message", "run-1"), null, false)
        gate.noteAbortRequested()
        gate.beginLease(2)
        assertEquals(TurnBoundaryDecision.Project, gate.decide(loopStatus("WAITING_ON_INPUT"), null, false))
        gate.decide(delta("assistant_message", "run-2"), null, false)
        val idle = assertIs<TurnBoundaryDecision.LoopIdle>(gate.decide(loopStatus("WAITING_ON_INPUT"), "run-2", false))
        assertEquals(RuntimeRunStatus.Completed, idle.status)
    }

    private fun received(frame: AppServerInboundFrame) =
        AppServerReceivedFrame(channel = AppServerChannel.Stream, frame = frame, raw = buildJsonObject { put("type", frame.type ?: "") })

    private fun turnFinished(turnId: String, runId: String?) = received(
        AppServerInboundFrame.TurnFinished(
            runtime = runtime,
            eventSeq = 5,
            emittedAt = "2026-09-24T00:00:00Z",
            idempotencyKey = "turn_finished:$turnId",
            turnId = turnId,
            stopReason = "end_turn",
            runId = runId,
        ),
    )

    private fun loopStatus(status: String, active: List<String> = emptyList()) = received(
        AppServerInboundFrame.UpdateLoopStatus(
            runtime = runtime,
            eventSeq = 4,
            emittedAt = "2026-09-24T00:00:00Z",
            idempotencyKey = "loop:4",
            loopStatus = AppServerLoopStatus(status = status, activeRunIds = active),
        ),
    )

    private fun delta(messageType: String, runId: String, stopReason: String? = null) = received(
        AppServerInboundFrame.StreamDelta(
            runtime = runtime,
            eventSeq = 3,
            emittedAt = "2026-09-24T00:00:00Z",
            idempotencyKey = "delta:3",
            delta = buildJsonObject {
                put("message_type", messageType)
                put("run_id", runId)
                stopReason?.let { put("stop_reason", it) }
            },
        ),
    )

    private companion object {
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
    }
}
