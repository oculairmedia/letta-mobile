package com.letta.mobile.data.timeline

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-qygvv.24: on every settled turn the Pixel logged `timeline.duplicateKeyDropped` for
 * `run-local-run-N` and `msg-cm-android-<otid>` with `sources=live+settled`. The settled rows adopt
 * the live rows' keys (#1673) while the overlay still holds the turn, so the list must hide the live
 * copies against the very snapshot it keys by. Each frame here is one read of both sources, as the
 * lazy list now takes it: unique keys, no guard drop, and the settled rows under the live keys.
 */
class LiveSettledKeyOverlapTest {
    private var ownedTelemetryHistory: AutoCloseable? = null

    @kotlin.test.BeforeTest
    fun installOwnedTelemetryHistory() {
        ownedTelemetryHistory = com.letta.mobile.util.Telemetry.installRecorder(com.letta.mobile.util.TelemetryRecorder())
    }

    @kotlin.test.AfterTest
    fun detachOwnedTelemetryHistory() {
        ownedTelemetryHistory?.close()
    }

    @Test fun settledTwinReplacesLiveRowUnderItsKeyAtEveryStep() = runBlocking {
        val harness = OverlapHarness.open(OverlapTurn.durable)
        try {
            harness.streamTurn(OverlapTurn.live)
            val streaming = harness.frame()
            val liveKeys = streaming.live.map { it.key }
            assertEquals("run-${OverlapTurn.LIVE_RUN}-from-ui-msg-9180002:REASONING", liveKeys.first())
            assertTrue(liveKeys.last().startsWith("msg-") && OverlapTurn.OTID in liveKeys.last(), "echo key: $liveKeys")
            assertFrameClean(streaming)

            harness.reconcile()
            val landed = harness.frame()
            // Both sources hold the turn under the same keys: the shape the device guard caught
            // when the live rows were filtered against the previous snapshot.
            assertEquals(liveKeys, landed.live.map { it.key })
            assertEquals(liveKeys, landed.settledKeys)
            // Read once, the settled rows take the live rows' slots and the live copies step aside.
            assertFrameClean(landed)
            assertEquals(emptyList(), landed.rows.live)
            assertEquals(liveKeys, landed.keys)

            harness.drainOverlay()
            val drained = harness.frame()
            assertFrameClean(drained)
            assertEquals(liveKeys, drained.keys)
        } finally {
            harness.close()
        }
    }

    @Test fun fullTurnThroughOverlayDrainRecordsZeroGuardDrops() = runBlocking {
        val harness = OverlapHarness.open(OverlapTurn.durable)
        try {
            harness.streamTurn(OverlapTurn.live)
            val keys = harness.frame().live.mapTo(mutableSetOf()) { it.key }
            val before = guardDrops(keys)
            val frames = mutableListOf(harness.frame())
            harness.reconcile()
            frames += harness.frame()
            harness.drainOverlay()
            frames += harness.frame()

            assertEquals(0, frames.sumOf { it.rows.duplicates.size })
            assertEquals(before, guardDrops(keys), "the guard dropped a row during the turn")
        } finally {
            harness.close()
        }
    }

    @Test fun settledAliasSurvivesLiveClearAndLaterDurableRevision() = runBlocking {
        val recorder = TimelineFrameRecorder.open(OverlapTurn.scope)
        try {
            recorder.stream(OverlapTurn.live)
            val live = recorder.awaitFrameContaining(OverlapTurn.reply.id).rows.single { OverlapTurn.reply.id in it.messages }
            val durable = OverlapTurn.durable.map { message ->
                when (message) {
                    is com.letta.mobile.data.model.AssistantMessage -> message.copy(id = "canonical-reply")
                    is com.letta.mobile.data.model.ReasoningMessage -> message.copy(id = "canonical-thought")
                    else -> message
                }
            }
            recorder.settle(durable)
            val settled = recorder.awaitFrameContaining("canonical-reply").rows.single { "canonical-reply" in it.messages }
            assertEquals(live.key, settled.key, "settlement must retain the first live row key")
            recorder.reconcile(durable + com.letta.mobile.data.model.AssistantMessage(
                id = "later-reply", contentRaw = kotlinx.serialization.json.JsonPrimitive("Later durable revision"),
                date = "2026-09-25T16:00:00Z",
            ))
            val later = recorder.awaitFrameContaining("canonical-reply").rows.single { "canonical-reply" in it.messages }
            assertEquals(live.key, later.key, "publication clearance must not erase the presentation alias")
            assertTrue(later.liveNames.containsAll(live.messages), "later generations lost the resident member aliases: ${later.liveNames}")
        } finally {
            recorder.close()
        }
    }

    private fun assertFrameClean(frame: OverlapFrame) {
        assertEquals(emptyMap(), frame.rows.duplicates, "guard dropped rows: $frame")
        val keys = frame.keys.filterNotNull()
        assertEquals(keys.size, keys.toSet().size, "repeated key in $keys")
    }
}
