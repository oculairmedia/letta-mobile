package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The scope-mismatched terminal path reads stop reasons through [lifecycleStatusFromTerminal]. It
 * used to treat every stop_reason other than cancelled/error as Completed, so an approval pause
 * arriving with a mismatched agent scope settled the turn before the approval was answered.
 */
class TurnRunIdGateTerminalTest {
    @Test
    fun requiresApprovalIsNotALifecycleTerminal() {
        assertNull(stop("requires_approval").lifecycleStatusFromTerminal())
    }

    @Test
    fun unrecognisedStopReasonIsNotALifecycleTerminal() {
        assertNull(stop("tool_use").lifecycleStatusFromTerminal())
    }

    @Test
    fun terminalReasonsMatchTheMapper() {
        assertEquals(RuntimeRunStatus.Completed, stop("end_turn").lifecycleStatusFromTerminal())
        assertEquals(RuntimeRunStatus.Completed, stop("max_steps").lifecycleStatusFromTerminal())
        assertEquals(RuntimeRunStatus.Cancelled, stop("cancelled").lifecycleStatusFromTerminal())
        assertEquals(RuntimeRunStatus.Failed, stop("llm_api_error").lifecycleStatusFromTerminal())
    }

    private fun stop(reason: String): AppServerReceivedFrame {
        val delta = buildJsonObject {
            put("message_type", "stop_reason")
            put("stop_reason", reason)
        }
        val frame = AppServerInboundFrame.StreamDelta(
            runtime = AppServerRuntimeScope("agent-1", "conv-1"),
            eventSeq = 1,
            emittedAt = "2026-09-15T00:00:00Z",
            idempotencyKey = "evt-1",
            delta = delta,
        )
        return AppServerReceivedFrame(channel = AppServerChannel.Stream, frame = frame, raw = buildJsonObject { put("type", "stream_delta") })
    }
}
