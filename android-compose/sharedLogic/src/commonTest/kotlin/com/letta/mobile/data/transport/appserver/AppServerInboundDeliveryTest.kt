package com.letta.mobile.data.transport.appserver

import com.letta.mobile.data.controller.RuntimeSyncScheduler
import com.letta.mobile.data.controller.RuntimeSyncScheduler.SyncReason
import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.controller.fanout.RuntimeEventFanout
import com.letta.mobile.data.controller.fanout.StreamIntegrityMonitor
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.runtime.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * letta-mobile-by4vm.5: frames that arrive in order on the one App Server socket reach the router,
 * the fanout and the gap detector in that order, while control frames still never wait for anyone
 * to consume the deltas ahead of them. Frames are decoded with [AppServerProtocol] so each carries
 * the lane label production gives it (`turn_finished` and `control_request` are Control,
 * `stream_delta` and `update_loop_status` are Stream).
 *
 * The old path (two lane jobs merged by `mergedFrames`) gave no order between lanes, so these
 * orderings held only when the scheduler happened to run the lanes in arrival order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerInboundDeliveryTest {

    @Test
    fun turnFinishedAfterTheLastDeltaIsDeliveredAfterItAndTheDeltaIsKept() = runTest {
        val delivery = AppServerInboundDelivery()
        val router = AppServerRuntimeEventRouter()
        router.attach(backgroundScope, delivery.inboundFrames)
        val (_, events) = router.subscribe(AgentId(AGENT), ConversationId(CONVERSATION))
        // A fake turn subscriber: like the engine, it stops at the authoritative terminal.
        val turn = async { events.untilTurnFinished().toList() }
        runCurrent()

        val session = delivery.open(backgroundScope)
        session.accept(decode(delta(seq = 1, text = "Hel")))
        session.accept(decode(delta(seq = 2, text = "lo")))
        session.accept(decode(turnFinished(seq = 3)))
        runCurrent()

        val seen = turn.await()
        assertEquals(listOf("stream_delta", "stream_delta", "turn_finished"), seen.map { it.frame.type })
        assertEquals(listOf(1L, 2L, 3L), seen.map { it.eventSeq() })
    }

    @Test
    fun inOrderSocketSequenceMixingLanesReportsNoGap() = runTest {
        val syncs = runSequenceThroughMonitor(
            listOf(
                delta(seq = 1, text = "a"),
                loopStatus(seq = 2),
                delta(seq = 3, text = "b"),
                turnFinished(seq = 4),
                loopStatus(seq = 5),
                delta(seq = 6, text = "c"),
                turnFinished(seq = 7),
            ),
        )
        assertEquals(emptyList(), syncs, "an in-order socket never looks like a lost frame")
    }

    @Test
    fun aRealHoleInTheSequenceIsStillReportedAsAGap() = runTest {
        val syncs = runSequenceThroughMonitor(
            listOf(delta(seq = 1, text = "a"), delta(seq = 2, text = "b"), turnFinished(seq = 4)),
        )
        assertEquals(listOf(SyncReason.Gap), syncs, "the harness does detect a real gap")
    }

    /**
     * The bound: a control frame queued behind a delta flood waits only for the fanout to ROUTE
     * those deltas. Nobody consumes them (their subscriber is stalled and gets detached by the
     * fanout), no virtual time passes, and no queue overflows.
     */
    @Test
    fun deltaFloodDoesNotHoldUpAControlRequestBeyondRouting() = runTest {
        val delivery = AppServerInboundDelivery()
        val router = AppServerRuntimeEventRouter()
        router.attach(backgroundScope, delivery.inboundFrames)
        router.subscribe(AgentId(AGENT), ConversationId(CONVERSATION), "stalled-delta-reader")
        val (_, approvals) = router.subscribe(AgentId(OTHER_AGENT), ConversationId(OTHER_CONVERSATION), "approver")
        val approval = CompletableDeferred<AppServerReceivedFrame>()
        backgroundScope.launch { approvals.collect { approval.complete(it) } }
        runCurrent()

        val session = delivery.open(backgroundScope)
        val socketReader = launch {
            for (seq in 1..FLOOD_SIZE) {
                session.accept(decode(delta(seq = seq.toLong(), text = "x")))
                // The real read loop suspends on the socket between frames.
                yield()
            }
            session.accept(decode(controlRequest()))
        }
        runCurrent()

        assertTrue(socketReader.isCompleted, "no delivery queue overflowed during the flood")
        assertTrue(approval.isCompleted, "the control_request was routed right after the deltas")
        assertEquals("control_request", approval.await().frame.type)
        assertEquals(0L, currentTime, "nothing waited on a timer")
        assertEquals(1, router.subscriberCount(), "the stalled delta reader was detached, not waited on")
    }

    @Test
    fun laneViewsCarryTheSameSequenceAndAStalledStreamViewHoldsUpNothingElse() = runTest {
        val delivery = AppServerInboundDelivery()
        val ordered = collectInBackground(delivery.inboundFrames)
        val control = collectInBackground(delivery.controlFrames)
        val stalled = CompletableDeferred<Unit>()
        backgroundScope.launch { delivery.streamFrames.collect { stalled.await() } }
        runCurrent()

        val session = delivery.open(backgroundScope)
        val frames = buildList {
            repeat(RuntimeEventFanout.SUBSCRIBER_BUFFER_CAPACITY) { add(delta(seq = it + 1L, text = "x")) }
            add(turnFinished(seq = RuntimeEventFanout.SUBSCRIBER_BUFFER_CAPACITY + 1L))
        }
        frames.forEach { session.accept(decode(it)) }
        runCurrent()

        assertEquals(frames.size, ordered.size, "the ordered path got every frame")
        assertEquals(frames.indices.map { it + 1L }, ordered.map { it.eventSeq() })
        assertEquals(listOf("turn_finished"), control.map { it.frame.type })
        stalled.complete(Unit)
    }

    private suspend fun TestScope.runSequenceThroughMonitor(sequence: List<String>): List<SyncReason> {
        val delivery = AppServerInboundDelivery()
        val router = AppServerRuntimeEventRouter()
        val syncs = mutableListOf<SyncReason>()
        val scheduler = RuntimeSyncScheduler(
            runtimes = router::watchedRuntimes,
            sync = { _, reason -> syncs += reason },
            clock = { 0L },
        )
        router.bindStreamIntegrity(StreamIntegrityMonitor(scheduler, detectGaps = true))
        router.attach(backgroundScope, delivery.inboundFrames)
        router.subscribe(AgentId(AGENT), ConversationId(CONVERSATION))
        runCurrent()

        val session = delivery.open(backgroundScope)
        sequence.forEach { session.accept(decode(it)) }
        runCurrent()
        scheduler.tick()
        return syncs
    }

    private fun TestScope.collectInBackground(flow: Flow<AppServerReceivedFrame>): List<AppServerReceivedFrame> {
        val frames = mutableListOf<AppServerReceivedFrame>()
        backgroundScope.launch { flow.catch { }.collect { frames += it } }
        return frames
    }

    private fun Flow<AppServerReceivedFrame>.untilTurnFinished(): Flow<AppServerReceivedFrame> =
        transformWhile {
            emit(it)
            it.frame !is AppServerInboundFrame.TurnFinished
        }

    private fun AppServerReceivedFrame.eventSeq(): Long = when (val f = frame) {
        is AppServerInboundFrame.StreamDelta -> f.eventSeq
        is AppServerInboundFrame.TurnFinished -> f.eventSeq
        else -> error("no event_seq on ${f.type}")
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-1"
        const val OTHER_AGENT = "agent-2"
        const val OTHER_CONVERSATION = "conv-2"
        const val FLOOD_SIZE = 10_000
        const val RUNTIME = """"runtime":{"agent_id":"$AGENT","conversation_id":"$CONVERSATION"}"""

        fun decode(json: String): AppServerReceivedFrame = AppServerProtocol.decodeFrame(json)

        fun envelope(seq: Long) = """$RUNTIME,"event_seq":$seq,"emitted_at":"t","idempotency_key":"k-$seq""""

        fun delta(seq: Long, text: String) =
            """{"type":"stream_delta",${envelope(seq)},"delta":{"message_type":"assistant_message","content":"$text"}}"""

        fun loopStatus(seq: Long) =
            """{"type":"update_loop_status",${envelope(seq)},"loop_status":{"status":"PROCESSING_API_RESPONSE"}}"""

        fun turnFinished(seq: Long) =
            """{"type":"turn_finished",${envelope(seq)},"turn_id":"turn-1","stop_reason":"end_turn"}"""

        fun controlRequest() =
            """{"type":"control_request","request_id":"perm-1","request":{"subtype":"can_use_tool","tool_name":"Bash"},""" +
                """"agent_id":"$OTHER_AGENT","conversation_id":"$OTHER_CONVERSATION"}"""
    }
}
