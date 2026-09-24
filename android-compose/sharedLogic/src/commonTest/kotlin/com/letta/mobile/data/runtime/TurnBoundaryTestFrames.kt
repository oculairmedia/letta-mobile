package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerLoopStatus
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * letta-mobile-qygvv.2: shared stream-frame fixtures for the turn boundary tests. Frames are built
 * from typed values (a run, a stop reason, a loop state) so tests never pass loose wire strings.
 */

private val boundaryTestRuntime = AppServerRuntimeScope("agent-1", "conv-1")
private const val BOUNDARY_TEST_EMITTED_AT = "2026-09-24T00:00:00Z"

internal enum class TestStopReason(val wire: String) {
    EndTurn("end_turn"),
    RequiresApproval("requires_approval"),
    Cancelled("cancelled"),
}

internal enum class TestLoopState(val wire: String) {
    WaitingOnInput("WAITING_ON_INPUT"),
    WaitingOnApproval("WAITING_ON_APPROVAL"),
    ProcessingApiResponse("PROCESSING_API_RESPONSE"),
    ;

    /** An `update_loop_status` frame in this state, with [activeRuns] still running. */
    fun frame(activeRuns: List<TestRun> = emptyList()) = AppServerInboundFrame.UpdateLoopStatus(
        runtime = boundaryTestRuntime,
        eventSeq = 3,
        emittedAt = BOUNDARY_TEST_EMITTED_AT,
        idempotencyKey = "loop:$wire",
        loopStatus = AppServerLoopStatus(status = wire, activeRunIds = activeRuns.mapNotNull { it.id }),
    )
}

/** The frames one server run streams; a null [id] models a `turn_finished` without `run_id`. */
internal data class TestRun(val id: String?) {
    fun assistantDelta() = delta("assistant_message")

    fun stopDelta(reason: TestStopReason = TestStopReason.EndTurn) =
        delta("stop_reason") { put("stop_reason", reason.wire) }

    fun loopErrorDelta(terminal: Boolean) = delta("loop_error") {
        put("message", "retrying provider")
        put("is_terminal", terminal)
    }

    /** `turn_finished` for this run's turn number [turn] (turn id `turn-<turn>`). */
    fun turnFinished(turn: Int, reason: TestStopReason = TestStopReason.EndTurn) = AppServerInboundFrame.TurnFinished(
        runtime = boundaryTestRuntime,
        eventSeq = 2,
        emittedAt = BOUNDARY_TEST_EMITTED_AT,
        idempotencyKey = "turn_finished:turn-$turn",
        turnId = "turn-$turn",
        stopReason = reason.wire,
        runId = id,
    )

    private fun delta(messageType: String, extra: JsonObjectBuilder.() -> Unit = {}) = AppServerInboundFrame.StreamDelta(
        runtime = boundaryTestRuntime,
        eventSeq = 1,
        emittedAt = BOUNDARY_TEST_EMITTED_AT,
        idempotencyKey = "delta-$messageType-$id",
        delta = buildJsonObject {
            put("message_type", messageType)
            put("run_id", id)
            extra()
        },
    )
}

/** Decides [frame] as the stream channel delivers it, for a lease on [leaseRun]. */
internal fun TurnBoundaryGate.decideFrame(
    frame: AppServerInboundFrame,
    leaseRun: TestRun? = null,
    approvalOutstanding: Boolean = false,
): TurnBoundaryDecision = decide(
    TurnBoundaryInput(
        received = AppServerReceivedFrame(
            channel = AppServerChannel.Stream,
            frame = frame,
            raw = buildJsonObject { put("type", frame.type ?: "") },
        ),
        leaseRunId = leaseRun?.id,
        approvalOutstanding = approvalOutstanding,
    ),
)
