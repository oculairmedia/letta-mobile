package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-qygvv.14: what each recorded turn shape must do beyond reaching the same terminal:
 * the node answers the App Server exactly like a direct client would, and the phone sees the turn.
 */
class IrohBridgeParityScenarioTest {

    @Test
    fun autoAllowedControlRequestIsAllowedOnBothPaths() = runTest {
        val fixture = BridgeParityFixtures.TOOL_CALL_AUTO_ALLOWED
        val recording = fixture.load()
        val direct = directRun(recording, fixture)
        val bridge = bridgeRun(recording, fixture)

        for (server in listOf(direct.server, bridge.upstream)) {
            val answer = server.approvalAnswers().single()
            assertEquals("perm-call_parity_allow_1", answer.requestId)
            assertTrue(answer.decision is AppServerApprovalResponseDecision.Allow, "decision ${answer.decision}")
        }
        // Under Unrestricted the approval card is suppressed: the phone gets the call as a tool_call_message.
        assertTrue(bridge.phone.sawDelta("tool_call_message", "call_parity_allow_1"), bridge.kinds())
        assertTrue(bridge.phone.sawDelta("tool_return_message", "call_parity_allow_1"), bridge.kinds())
    }

    @Test
    fun deniedApprovalReachesTheServerAsTheUsersDenial() = runTest {
        val fixture = BridgeParityFixtures.APPROVAL_DENIED
        val recording = fixture.load()
        val direct = directRun(recording, fixture)
        val bridge = bridgeRun(recording, fixture)

        for (server in listOf(direct.server, bridge.upstream)) {
            val answer = server.approvalAnswers().single()
            assertEquals("perm-call_parity_denied_1", answer.requestId)
            assertEquals(AppServerApprovalResponseDecision.Deny(DenyApprovalsDriver.DENIAL), answer.decision)
        }
        assertTrue(bridge.phone.sawDelta("approval_request_message", "call_parity_denied_1"), bridge.kinds())
        val toolReturn = bridge.phone.stream.last { it.kind == "tool_return_message" }
        assertEquals("error", toolReturn.delta()?.parityString("status"), "the denial reaches the phone as an error return")
    }

    @Test
    fun externalToolIsAnsweredAndThePhoneSeesTheCall() = runTest {
        val fixture = BridgeParityFixtures.EXTERNAL_TOOL
        val recording = fixture.load()
        val direct = directRun(recording, fixture)
        val bridge = bridgeRun(recording, fixture)

        for (server in listOf(direct.server, bridge.upstream)) {
            val response = server.commands.filterIsInstance<AppServerCommand.ExternalToolCallResponse>().single()
            assertEquals("ext-parity-canvas-1", response.requestId)
        }
        assertTrue(
            bridge.phone.sawDelta("tool_call_message", "call_parity_canvas_1") ||
                bridge.phone.sawDelta("approval_request_message", "call_parity_canvas_1"),
            bridge.kinds(),
        )
        assertTrue(bridge.phone.sawDelta("tool_return_message", "call_parity_canvas_1"), bridge.kinds())
    }

    @Test
    fun terminalLoopErrorFailsThePhoneWithAReasonAndClearsPresence() = runTest {
        val fixture = BridgeParityFixtures.LOOP_ERROR
        val recording = fixture.load()
        val direct = directRun(recording, fixture).outcome
        val bridge = bridgeRun(recording, fixture)

        assertEquals(RuntimeRunStatus.Failed, direct.status)
        assertEquals(RuntimeRunStatus.Failed, bridge.outcome.status)
        assertFalse(bridge.outcome.reason.isNullOrBlank(), "the phone's failure carries the error")
        assertFalse(bridge.outcome.busyAfter, "the phone's lease is released")
        val finished = bridge.phone.stream.last { it.type == "turn_finished" }
        assertEquals("error", finished.json.parityString("stop_reason"))
        assertFalse(finished.json.parityString("error").isNullOrBlank(), "turn_finished carries the error")
        val lastLoop = bridge.phone.stream.last { it.type == "update_loop_status" }
        assertEquals("WAITING_ON_INPUT", lastLoop.loopStatus, "presence clears on the phone")
    }

    @Test
    fun abortCancelsThenResumesTheParkedQueueOnBothPaths() = runTest {
        val fixture = BridgeParityFixtures.ABORT_THEN_RESUME
        val recording = fixture.load()
        val direct = directRun(recording, fixture)
        val bridge = bridgeRun(recording, fixture)

        // The phone's own status is GateCheck.TerminalStatus (waived for letta-mobile-qygvv.28).
        assertEquals(RuntimeRunStatus.Cancelled, direct.outcome.status)
        for (server in listOf(direct.server, bridge.upstream)) {
            val control = server.commands.filter { it is AppServerCommand.AbortMessage || it is AppServerCommand.ResumeQueue }
            assertEquals(
                listOf(AppServerCommand.AbortMessage::class, AppServerCommand.ResumeQueue::class),
                control.map { it::class },
                "abort, then resume the other viewer's parked input",
            )
        }
        assertEquals("cancelled", bridge.phone.stream.last { it.type == "turn_finished" }.json.parityString("stop_reason"))
    }

    private fun RecordedAppServerClient.approvalAnswers(): List<AppServerInputPayload.ApprovalResponse> =
        commands.filterIsInstance<AppServerCommand.Input>().mapNotNull { it.payload as? AppServerInputPayload.ApprovalResponse }

    private fun WireFrame.delta() = json["delta"] as? JsonObject

    private fun FakePhoneLink.sawDelta(kind: String, toolCallId: String): Boolean =
        stream.any { it.kind == kind && it.delta().toString().contains(toolCallId) }

    private fun BridgeRun.kinds(): String = phone.stream.map { it.kind }.toString()
}
