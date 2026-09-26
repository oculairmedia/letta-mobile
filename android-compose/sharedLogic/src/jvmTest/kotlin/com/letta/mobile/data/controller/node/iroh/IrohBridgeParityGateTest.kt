package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * letta-mobile-qygvv.14: the bridge parity gate. A recorded App Server turn must end the same way
 * for a phone behind the Iroh node (BridgeRun) as for a client on the App Server itself (DirectRun),
 * with the same protocol frames and without leaning on the engine's 1.5 s terminal settle window.
 * Each [GateCheck] runs over every fixture except where the fixture names the bead that waives it.
 */
class IrohBridgeParityGateTest {

    @Test fun bothRunsEnd() = runTest { assertGate(GateCheck.BothRunsEnd) }

    @Test fun bridgeRunReachesTheDirectRunTerminalStatus() = runTest { assertGate(GateCheck.TerminalStatus) }

    @Test fun bridgeRunReachesTheDirectRunTerminalReason() = runTest { assertGate(GateCheck.TerminalReason) }

    @Test fun bridgeRunReachesTheDirectRunTerminalRunId() = runTest { assertGate(GateCheck.TerminalRunId) }

    @Test fun directRunEndsOnTheRecordedTurnFinishedRun() = runTest { assertGate(GateCheck.DirectMatchesRecording) }

    @Test fun bridgeTerminalFramesKeepAppServerOrder() = runTest { assertGate(GateCheck.TerminalOrder) }

    @Test fun inputAcceptedArrivesBeforeTheFirstStreamFrame() = runTest { assertGate(GateCheck.InputAcceptedFirst) }

    @Test fun bridgeLeaseReleasesOnTurnFinishedWithoutTheSettleWindow() = runTest { assertGate(GateCheck.NoSettleWindow) }

    @Test fun bridgeCarriesEveryAppServerFrameKind() = runTest { assertGate(GateCheck.FrameKinds) }

    @Test fun everyRunsUsageAndStopReasonReachThePhone() = runTest { assertGate(GateCheck.RunTailsReachPhone) }

    @Test fun usageStatisticsPrecedeStopReasonPerRun() = runTest { assertGate(GateCheck.TailOrder) }

    @Test fun toolCallArgumentsReachThePhoneAsJsonObjects() = runTest { assertGate(GateCheck.ToolArgumentsAreObjects) }

    @Test fun eachToolCallReachesThePhoneAsOneCallAndOneReturn() = runTest { assertGate(GateCheck.OneToolRowPerCall) }

    @Test fun queueUpdatesAfterTurnFinishedReachThePhone() = runTest { assertGate(GateCheck.QueueAfterTurn) }

    /**
     * letta-mobile-1n5py / qygvv.4: an input the App Server queued reaches the phone as queued, and
     * the `update_queue` that dequeues it is relayed, so the phone's lease leaves its queued wait.
     */
    @Test
    fun queuedInputReachesThePhoneWithItsQueueUpdates() = runTest {
        val fixture = BridgeParityFixtures.QUEUED
        val phone = bridgeRun(fixture.load(), fixture).phone

        assertEquals("queued", phone.control.single().json.parityString("disposition"))
        val dequeued = phone.stream.filter { it.type == "update_queue" }.any { frame ->
            frame.json["removed"].toString().contains(fixture.clientMessageId) &&
                frame.json["removed"].toString().contains("dequeued")
        }
        assertTrue(dequeued, "the dequeue of this input must reach the phone: ${phone.stream.map { it.kind }}")
        assertTrue(
            phone.stream.none { it.json["delta"]?.toString()?.contains("local-run-40") == true },
            "the turn ahead's frames are not this phone's turn",
        )
    }

    @Test
    fun failedRelayAnswersTheInputWithARejection() = runTest {
        val fixture = BridgeParityFixtures.THINKING
        val recording = fixture.load()
        val phone = FakePhoneLink(recording.runtime, fixture.clientMessageId, PHONE_REQUEST_ID, backgroundScope)
        val busy = controllerRunning { flow { error("Another turn is already active") } }
        var failure: Throwable? = null
        relayTurn(busy, turnCommandFor(recording.runtime, fixture.clientMessageId), phone.protocol) {
            failure = it
        }
        assertNotNull(failure)
        val ack = phone.control.single()
        assertEquals("input_accepted", ack.type)
        assertEquals(false, (ack.json["accepted"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals("Another turn is already active", ack.json.parityString("error"))
    }

    private suspend fun TestScope.assertGate(check: GateCheck) {
        val failures = BridgeParityFixtures.ALL.filterNot { check in it.divergences }.mapNotNull { fixture ->
            check.verify(parityRuns(fixture))?.let { "$fixture: $it" }
        }
        assertTrue(failures.isEmpty(), "$check diverged:\n${failures.joinToString("\n")}")
    }
}
