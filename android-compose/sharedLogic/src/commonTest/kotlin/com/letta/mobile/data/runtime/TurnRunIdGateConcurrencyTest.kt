package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-by4vm.8: frames are accepted on the inbound collector while the turn coroutine
 * promotes run ids, so the gate's superseded set is read and written concurrently. After the
 * promotions settle, every superseded run must be rejected and only the live run accepted.
 */
class TurnRunIdGateConcurrencyTest {
    @Test
    fun concurrentAcceptsDuringPromotionsSettleOnTheLatestRun() = runTest {
        withContext(Dispatchers.Default) {
            repeat(REPEATS) { round -> assertRoundSettles(round) }
        }
    }

    private suspend fun assertRoundSettles(round: Int) = coroutineScope {
        val slot = TurnLeaseSlot(TurnRuntimeKey("agent-1", "conv-$round"))
        slot.lease = TurnLease(
            token = LEASE_TOKEN,
            runtimeId = "rt",
            agentId = "agent-1",
            conversationId = "conv-$round",
            acquiredAtMs = 0L,
            phase = TurnLeasePhase.Starting,
            runId = run(0),
        )
        slot.owner = AppServerTurnEngine.ActiveTurnOwner(
            runId = run(0),
            runtimeId = "rt",
            agentId = "agent-1",
            conversationId = "conv-$round",
            acquiredAtMs = 0L,
            lastTerminal = null,
        )
        val gate = slot.runIdGate
        gate.beginLease(LEASE_TOKEN)
        val promoter = launch { (1..PROMOTIONS).forEach { gate.promote(run(it), LEASE_TOKEN) } }
        (1..ACCEPTORS).map { worker ->
            async {
                repeat(ACCEPTS_PER_WORKER) { i ->
                    gate.beginLease(LEASE_TOKEN)
                    gate.accepts(frame(run((worker + i) % (PROMOTIONS + 1))), LEASE_TOKEN)
                }
            }
        }.awaitAll()
        promoter.join()
        (0 until PROMOTIONS).forEach {
            assertFalse(gate.accepts(frame(run(it)), LEASE_TOKEN), "round $round ${run(it)}")
        }
        assertTrue(gate.accepts(frame(run(PROMOTIONS)), LEASE_TOKEN))
    }

    private fun run(index: Int) = "run-$index"

    private fun frame(runId: String): AppServerReceivedFrame {
        val delta = buildJsonObject {
            put("message_type", "assistant_message")
            put("run_id", runId)
        }
        val streamDelta = AppServerInboundFrame.StreamDelta(
            runtime = AppServerRuntimeScope("agent-1", "conv-1"),
            eventSeq = 1,
            emittedAt = "2026-10-14T00:00:00Z",
            idempotencyKey = "evt-$runId",
            delta = delta,
        )
        val raw = buildJsonObject { put("type", "stream_delta") }
        return AppServerReceivedFrame(channel = AppServerChannel.Stream, frame = streamDelta, raw = raw)
    }

    private companion object {
        const val LEASE_TOKEN = 7L
        const val REPEATS = 20
        const val PROMOTIONS = 200
        const val ACCEPTORS = 8
        const val ACCEPTS_PER_WORKER = 300
    }
}
