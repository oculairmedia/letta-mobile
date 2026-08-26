package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-c4igq.8 / letta-mobile-53k65.9: characterizes transport frame publication,
 * flow drop protection, and single-source canonical frame distribution.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IrohFrameFlowDropTest {

    @Test
    fun synchronousProjectionMapsCanonicalReplayCache() {
        val canonical = MutableSharedFlow<TransportFrameEvent>(replay = 1)
        val projection = ServerFrameSharedFlow(canonical)
        val frame = ServerFrame.AssistantMessage(
            id = "replayed-msg",
            ts = "2026-08-23T00:00:00Z",
            conversationId = "conv-1",
            content = "replayed",
        )

        canonical.tryEmit(TransportFrameEvent(frame))

        assertEquals(listOf(frame), projection.replayCache)
    }

    @Test
    fun replayZeroSharedFlowDropsFrameEmittedWithNoCollector() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
        flow.emit("frame-during-gap")
        val received = mutableListOf<String>()
        val collector = launch { flow.collect { received += it } }
        runCurrent()
        flow.emit("frame-after-attach")
        runCurrent()
        collector.cancel()
        assertEquals(listOf("frame-after-attach"), received)
    }

    @Test
    fun alwaysOnKeepAliveCollectorPreventsZeroSubscriberDrop() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
        val keepAlive = launch { flow.collect { /* drain */ } }
        runCurrent()
        assertTrue(flow.subscriptionCount.value >= 1, "keep-alive holds a subscription so emit never sees zero subscribers")
        val received = mutableListOf<String>()
        val real = launch { flow.collect { received += it } }
        runCurrent()
        flow.emit("live-frame")
        runCurrent()
        assertEquals(listOf("live-frame"), received)
        keepAlive.cancel(); real.cancel()
    }

    @Test
    fun replayStaysZeroSoNoStaleFrameIsRetained() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
        val keepAlive = launch { flow.collect { /* drain */ } }
        runCurrent()
        flow.emit("old-terminal")
        runCurrent()
        val late = mutableListOf<String>()
        val lateCollector = launch { flow.collect { late += it } }
        runCurrent()
        assertEquals(emptyList(), late)
        keepAlive.cancel(); lateCollector.cancel()
    }

    @Test
    fun singleSourcePublisherDeliversIdenticalOrderedFramesToBothProjections() = runTest(UnconfinedTestDispatcher()) {
        val publisher = IrohFramePublisher()
        val eventsReceived = mutableListOf<String>()
        val frameEventsReceived = mutableListOf<String>()

        val job1 = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.events.collect { eventsReceived += it.id }
        }
        val job2 = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.frameEvents.collect { frameEventsReceived += it.frame.id }
        }

        runCurrent()

        val count = 20
        for (i in 1..count) {
            publisher.publish(
                ServerFrame.AssistantMessage(
                    id = "msg-$i",
                    ts = "2026-08-23T00:00:0${i}Z",
                    conversationId = "conv-1",
                    content = "content-$i",
                ),
            )
        }

        runCurrent()

        val expectedIds = (1..count).map { "msg-$it" }
        assertEquals(expectedIds, eventsReceived)
        assertEquals(expectedIds, frameEventsReceived)

        job1.cancelAndJoin()
        job2.cancelAndJoin()
    }

    @Test
    fun asymmetricCancelledConsumerDoesNotSplitHistoryOrBlockSurvivingConsumer() = runTest(UnconfinedTestDispatcher()) {
        val publisher = IrohFramePublisher()
        val eventsReceived = mutableListOf<String>()
        val frameEventsReceived = mutableListOf<String>()

        val survivingJob = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.events.collect { eventsReceived += it.id }
        }
        val cancelledJob = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.frameEvents.collect { frameEventsReceived += it.frame.id }
        }

        runCurrent()

        // 1. Emit 5 items to both
        for (i in 1..5) {
            publisher.publish(
                ServerFrame.AssistantMessage(
                    id = "msg-$i",
                    ts = "2026-08-23T00:00:0${i}Z",
                    conversationId = "conv-1",
                    content = "content-$i",
                ),
            )
        }
        runCurrent()

        // 2. Cancel frameEvents consumer
        cancelledJob.cancel()
        runCurrent()

        // 3. Emit 5 more items — surviving events consumer must receive all 10 without disruption
        for (i in 6..10) {
            publisher.publish(
                ServerFrame.AssistantMessage(
                    id = "msg-$i",
                    ts = "2026-08-23T00:00:0${i}Z",
                    conversationId = "conv-1",
                    content = "content-$i",
                ),
            )
        }
        runCurrent()

        val allIds = (1..10).map { "msg-$it" }
        val first5Ids = (1..5).map { "msg-$it" }

        assertEquals(allIds, eventsReceived, "Surviving events collector must receive all 10 frames in order")
        assertEquals(first5Ids, frameEventsReceived, "Cancelled frameEvents collector only receives frames prior to cancellation")

        survivingJob.cancelAndJoin()
    }

    @Test
    fun publisherPreservesReplayZeroForLateSubscribers() = runTest(UnconfinedTestDispatcher()) {
        val publisher = IrohFramePublisher()

        // Emit frame with no subscribers
        publisher.publish(
            ServerFrame.AssistantMessage(
                id = "early-msg",
                ts = "2026-08-23T00:00:00Z",
                conversationId = "conv-1",
                content = "early",
            ),
        )
        runCurrent()

        val lateEvents = mutableListOf<String>()
        val lateFrameEvents = mutableListOf<String>()

        val job1 = launch { publisher.events.collect { lateEvents += it.id } }
        val job2 = launch { publisher.frameEvents.collect { lateFrameEvents += it.frame.id } }
        runCurrent()

        // Late subscribers must not receive past frames
        assertEquals(emptyList(), lateEvents, "Replay must be 0 on events")
        assertEquals(emptyList(), lateFrameEvents, "Replay must be 0 on frameEvents")

        // Fresh emission reaches both
        publisher.publish(
            ServerFrame.AssistantMessage(
                id = "fresh-msg",
                ts = "2026-08-23T00:00:01Z",
                conversationId = "conv-1",
                content = "fresh",
            ),
        )
        runCurrent()

        assertEquals(listOf("fresh-msg"), lateEvents)
        assertEquals(listOf("fresh-msg"), lateFrameEvents)

        job1.cancelAndJoin()
        job2.cancelAndJoin()
    }

    /**
     * letta-mobile-p0gc (causal slice B): a stalled subscriber must NEVER
     * silently evict frames — under the retired DROP_OLDEST + tryEmit
     * publisher, stalling one consumer past the 64-slot buffer dropped the
     * active run's ToolCall/ToolReturn frames for everyone downstream of the
     * buffer with zero signal. With suspending emit, the producer backs up
     * (on its own background dispatcher) and every distinct tool call/return
     * arrives EXACTLY ONCE, in order, call→return correlation intact, once
     * the stalled consumer resumes. Deterministic: virtual-time scheduler,
     * no real delays; the stall is an explicit gate.
     */
    @Test
    fun slowSubscriberBeyondBufferCapacityNeverDropsToolCallOrReturnFrames() =
        runTest(UnconfinedTestDispatcher()) {
            val publisher = IrohFramePublisher() // DEFAULT_BUFFER_CAPACITY = 64
            val stallGate = CompletableDeferred<Unit>()

            val total = 200 // well beyond the 64-slot bounded buffer (100 call→return pairs)
            val expectedIds = List(total) { i ->
                val c = i / 2
                if (i % 2 == 0) "toolcall-call-$c" else "toolreturn-call-$c"
            }

            val fastReceived = mutableListOf<String>()
            val fastJob = launch(start = CoroutineStart.UNDISPATCHED) {
                publisher.events.collect { fastReceived += it.id }
            }

            val slowReceived = mutableListOf<String>()
            val stallAfter = 8 // consume 8 events, then stall like a Main thread awaiting a timeline ack
            val slowJob = launch(start = CoroutineStart.UNDISPATCHED) {
                publisher.frameEvents.collect { event ->
                    slowReceived += event.frame.id
                    if (slowReceived.size == stallAfter) stallGate.await()
                }
            }

            val producerFinished = launch(start = CoroutineStart.UNDISPATCHED) {
                repeat(total) { i ->
                    val c = i / 2
                    publisher.publish(
                        if (i % 2 == 0) {
                            ServerFrame.ToolCallMessage(
                                id = "toolcall-call-$c",
                                ts = "2026-08-23T00:00:00Z",
                                agentId = "agent-1",
                                conversationId = "conv-1",
                                turnId = "turn-1",
                                runId = "run-1",
                                toolCall = com.letta.mobile.data.transport.ToolCallPayload(
                                    toolCallId = "call-$c",
                                    name = "Bash",
                                    arguments = "{}",
                                ),
                            )
                        } else {
                            ServerFrame.ToolReturnMessage(
                                id = "toolreturn-call-$c",
                                ts = "2026-08-23T00:00:01Z",
                                conversationId = "conv-1",
                                runId = "run-1",
                                toolCallId = "call-$c",
                            )
                        },
                    )
                }
            }

            // Producer ran UNDISPATCHED until first suspension.
            runCurrent()
            assertFalse(producerFinished.isCompleted, "producer must suspend on backpressure, not drop")
            assertTrue(
                slowReceived.size < total,
                "slow subscriber is still stalled at this point (received ${slowReceived.size})",
            )
            assertEquals(
                expectedIds.take(slowReceived.size),
                slowReceived,
                "frames received before the stall must already be in order",
            )

            // Release the stall: everything buffered plus everything still to
            // be produced must flow through, losslessly.
            stallGate.complete(Unit)
            producerFinished.join()

            assertEquals(expectedIds, fastReceived, "fast subscriber receives all $total frames in order")
            assertEquals(expectedIds, slowReceived, "slow subscriber receives ALL $total frames exactly once, in order")

            // Tool correlation: each return immediately follows its own call.
            slowReceived.chunked(2).forEachIndexed { pairIndex, pair ->
                assertEquals(2, pair.size)
                assertTrue(pair[0].startsWith("toolcall-") && pair[1].startsWith("toolreturn-"))
                assertEquals(
                    pair[0].removePrefix("toolcall-"),
                    pair[1].removePrefix("toolreturn-"),
                    "return must correlate to its call (pair $pairIndex)",
                )
            }

            fastJob.cancelAndJoin()
            slowJob.cancelAndJoin()
        }
}
