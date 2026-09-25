package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.runtime.AppServerTurnEngine
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
 */
class IrohBridgeParityGateTest {

    @Test
    fun bridgeRunReachesTheDirectRunTerminal() = runTest {
        for (fixture in FIXTURES) {
            val recording = AppServerRecording.load(fixture)
            val direct = directRun(recording)
            val bridge = bridgeRun(recording).outcome
            assertNotNull(direct.status, "$fixture: direct run never ended")
            assertEquals(direct.status, bridge.status, "$fixture: terminal status")
            assertEquals(direct.reason, bridge.reason, "$fixture: stop reason")
            assertEquals(direct.runId, bridge.runId, "$fixture: run id")
        }
    }

    @Test
    fun bridgeTerminalFramesKeepAppServerOrder() = runTest {
        for (fixture in FIXTURES) {
            val recording = AppServerRecording.load(fixture)
            val stream = bridgeRun(recording).phone.stream
            val usage = stream.indexOfLast { it.kind == "usage_statistics" }
            val stop = stream.indexOfLast { it.kind == "stop_reason" }
            val idle = stream.indexOfLast { it.type == "update_loop_status" && it.loopStatus == WAITING_ON_INPUT }
            val finished = stream.indexOfLast { it.type == "turn_finished" }
            assertTrue(usage in 0 until stop, "$fixture: usage before stop_reason in ${stream.map { it.kind }}")
            assertTrue(stop < idle, "$fixture: stop_reason before idle loop status in ${stream.map { it.kind }}")
            assertTrue(idle < finished, "$fixture: idle loop status before turn_finished in ${stream.map { it.kind }}")
            assertEquals(recordedStopReason(recording), stream[finished].json.parityString("stop_reason"))
            assertEquals(recordedRunId(recording), stream[finished].json.parityString("run_id"))
        }
    }

    @Test
    fun inputAcceptedArrivesBeforeTheFirstStreamFrame() = runTest {
        for (fixture in FIXTURES) {
            val log = bridgeRun(AppServerRecording.load(fixture)).phone.log
            val ack = log.first()
            assertEquals(WireChannel.Control, ack.channel, "$fixture: first frame ${ack.json}")
            assertEquals("input_accepted", ack.type)
            assertEquals(REQUEST_ID, ack.json.parityString("request_id"))
            assertEquals(true, (ack.json["accepted"] as? JsonPrimitive)?.booleanOrNull)
            assertEquals(1, log.count { it.channel == WireChannel.Control }, "$fixture: exactly one control frame")
        }
    }

    @Test
    fun bridgeLeaseReleasesOnTurnFinishedWithoutTheSettleWindow() = runTest {
        for (fixture in FIXTURES) {
            val bridge = bridgeRun(AppServerRecording.load(fixture)).outcome
            assertNotNull(bridge.status, "$fixture: bridge run never ended")
            assertTrue(
                bridge.elapsedMs < AppServerTurnEngine.DEFAULT_TERMINAL_SETTLE_QUIET_MS,
                "$fixture: phone lease waited ${bridge.elapsedMs} ms, i.e. on the settle window",
            )
        }
    }

    @Test
    fun bridgeCarriesEveryAppServerFrameKind() = runTest {
        for (fixture in FIXTURES) {
            val recording = AppServerRecording.load(fixture)
            val recorded = recording.parsedFrames.mapNotNull { WireFrame(WireChannel.Stream, it).kind }.toSet()
            val bridged = bridgeRun(recording).phone.stream.mapNotNull { it.kind }.toSet()
            val missing = recorded - bridged - OBSERVER_ONLY_KINDS
            assertTrue(missing.isEmpty(), "$fixture: the bridge dropped $missing (bridged $bridged)")
        }
    }

    @Test
    fun failedRelayAnswersTheInputWithARejection() = runTest {
        val recording = AppServerRecording.load(FIXTURES.first())
        val phone = FakePhoneLink(recording.runtime, CLIENT_MESSAGE_ID, REQUEST_ID, backgroundScope)
        val busy = controllerRunning { flow { error("Another turn is already active") } }
        var failure: Throwable? = null
        relayTurn(busy, turnCommandFor(recording.runtime, CLIENT_MESSAGE_ID), phone.protocol) {
            failure = it
        }
        assertNotNull(failure)
        val ack = phone.control.single()
        assertEquals("input_accepted", ack.type)
        assertEquals(false, (ack.json["accepted"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals("Another turn is already active", ack.json.parityString("error"))
    }

    private suspend fun TestScope.directRun(recording: AppServerRecording): EngineOutcome {
        val client = RecordedAppServerClient(recording.ackJson, recording.frames, backgroundScope)
        return runEngineOn(client, turnCommandFor(recording.runtime, CLIENT_MESSAGE_ID))
    }

    private class BridgeRun(val phone: FakePhoneLink, val outcome: EngineOutcome)

    /** The node relays the recording to a fake phone, whose capture then drives a second engine. */
    private suspend fun TestScope.bridgeRun(recording: AppServerRecording): BridgeRun {
        val upstream = RecordedAppServerClient(recording.ackJson, recording.frames, backgroundScope)
        val nodeEngine = AppServerTurnEngine(client = upstream, turnIdleTimeoutMs = 600_000, nowMs = { testScheduler.currentTime })
        val command = turnCommandFor(recording.runtime, CLIENT_MESSAGE_ID)
        val phone = FakePhoneLink(recording.runtime, CLIENT_MESSAGE_ID, REQUEST_ID, backgroundScope)
        phone.relay(controllerRunning { nodeEngine.runTurn(it) }, command)
        val captured = RecordedAppServerClient(
            ackJson = phone.control.single().json.toString(),
            frames = phone.stream.map { it.json.toString() },
            scope = backgroundScope,
        )
        return BridgeRun(phone, runEngineOn(captured, command))
    }

    private fun recordedTurnFinished(recording: AppServerRecording) =
        recording.parsedFrames.last { it.parityString("type") == "turn_finished" }

    private fun recordedStopReason(recording: AppServerRecording) =
        recordedTurnFinished(recording).parityString("stop_reason")

    private fun recordedRunId(recording: AppServerRecording) = recordedTurnFinished(recording).parityString("run_id")

    private companion object {
        val FIXTURES = listOf("appserver/bridge-parity/thinking-turn.jsonl")
        const val CLIENT_MESSAGE_ID = "cm-parity-thinking-1"
        const val REQUEST_ID = "phone-req-1"
        const val WAITING_ON_INPUT = "WAITING_ON_INPUT"

        /**
         * App Server frame kinds a phone behind the node does not get, each with its reason. Keep this
         * small: a new kind the bridge drops must fail here, not vanish silently.
         */
        val OBSERVER_ONLY_KINDS = setOf(
            // Device presence of the node's own App Server connection, not of the phone's turn.
            "update_device_status",
        )
    }
}
