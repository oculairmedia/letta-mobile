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
            val recording = fixture.load()
            val direct = directRun(recording, fixture)
            val bridge = bridgeRun(recording, fixture).outcome
            assertNotNull(direct.status, "$fixture: direct run never ended")
            assertEquals(direct.status, bridge.status, "$fixture: terminal status")
            assertEquals(direct.reason, bridge.reason, "$fixture: stop reason")
            assertEquals(direct.runId, bridge.runId, "$fixture: run id")
        }
    }

    @Test
    fun bridgeTerminalFramesKeepAppServerOrder() = runTest {
        for (fixture in FIXTURES) {
            val recording = fixture.load()
            val stream = bridgeRun(recording, fixture).phone.stream
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
            val log = bridgeRun(fixture.load(), fixture).phone.log
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
            val bridge = bridgeRun(fixture.load(), fixture).outcome
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
            val recording = fixture.load()
            val recorded = recording.parsedFrames.mapNotNull { WireFrame(WireChannel.Stream, it).kind }.toSet()
            val bridged = bridgeRun(recording, fixture).phone.stream.mapNotNull { it.kind }.toSet()
            val missing = recorded - bridged - OBSERVER_ONLY_KINDS
            assertTrue(missing.isEmpty(), "$fixture: the bridge dropped $missing (bridged $bridged)")
        }
    }

    /**
     * letta-mobile-1n5py / qygvv.4: an input the App Server queued reaches the phone as queued, and
     * the `update_queue` that dequeues it is relayed, so the phone's lease leaves its queued wait.
     */
    @Test
    fun queuedInputReachesThePhoneWithItsQueueUpdates() = runTest {
        val fixture = FIXTURES.single { it.clientMessageId == QUEUED_CLIENT_MESSAGE_ID }
        val phone = bridgeRun(fixture.load(), fixture).phone

        assertEquals("queued", phone.control.single().json.parityString("disposition"))
        val dequeued = phone.stream.filter { it.type == "update_queue" }.any { frame ->
            frame.json["removed"].toString().contains(QUEUED_CLIENT_MESSAGE_ID) &&
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
        val fixture = FIXTURES.first()
        val recording = fixture.load()
        val phone = FakePhoneLink(recording.runtime, fixture.clientMessageId, REQUEST_ID, backgroundScope)
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

    private suspend fun TestScope.directRun(recording: AppServerRecording, fixture: ParityFixture): EngineOutcome {
        val client = RecordedAppServerClient(recording.ackJson, recording.frames, backgroundScope)
        return runEngineOn(client, turnCommandFor(recording.runtime, fixture.clientMessageId))
    }

    private class BridgeRun(val phone: FakePhoneLink, val outcome: EngineOutcome)

    /** The node relays the recording to a fake phone, whose capture then drives a second engine. */
    private suspend fun TestScope.bridgeRun(recording: AppServerRecording, fixture: ParityFixture): BridgeRun {
        val upstream = RecordedAppServerClient(recording.ackJson, recording.frames, backgroundScope)
        val nodeEngine = AppServerTurnEngine(client = upstream, turnIdleTimeoutMs = 600_000, nowMs = { testScheduler.currentTime })
        val command = turnCommandFor(recording.runtime, fixture.clientMessageId)
        val phone = FakePhoneLink(recording.runtime, fixture.clientMessageId, REQUEST_ID, backgroundScope)
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

    /** One recorded turn and the client message id its input carried. */
    private data class ParityFixture(val resource: String, val clientMessageId: String) {
        fun load(): AppServerRecording = AppServerRecording.load(resource)

        override fun toString(): String = resource
    }

    private companion object {
        val FIXTURES = listOf(
            ParityFixture("appserver/bridge-parity/thinking-turn.jsonl", "cm-parity-thinking-1"),
            // letta-mobile-1n5py: an input queued behind another viewer's turn, then dequeued and run.
            ParityFixture("appserver/bridge-parity/queued-turn.jsonl", QUEUED_CLIENT_MESSAGE_ID),
        )
        const val QUEUED_CLIENT_MESSAGE_ID = "cm-parity-queued-1"
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
