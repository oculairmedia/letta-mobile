package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
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
        val projection = ServerFrameSharedFlow(canonicalEvents = canonical)
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

    @Test
    fun stalledCollectorDetachesNormallyWithoutBlockingHealthyCollector() = runTest(UnconfinedTestDispatcher()) {
        val publisher = IrohFramePublisher(bufferCapacity = 2)
        val stallGate = CompletableDeferred<Unit>()
        val slowReceived = mutableListOf<String>()
        val slowJob = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.frameEvents.collect { event ->
                slowReceived += event.frame.id
                if (slowReceived.size == 1) stallGate.await()
            }
        }
        val healthyReceived = mutableListOf<String>()
        val healthyJob = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.events.collect { healthyReceived += it.id }
        }

        val expected = (1..6).map { "msg-$it" }
        expected.forEach { id ->
            publisher.publish(assistantFrame(id))
            runCurrent()
        }

        assertEquals(expected, healthyReceived)
        assertEquals(listOf("msg-1"), slowReceived)
        assertTrue(slowJob.isActive, "detachment completes after the stalled callback resumes")

        stallGate.complete(Unit)
        slowJob.join()
        assertTrue(slowJob.isCancelled, "detachment uses normal coroutine cancellation")
        assertTrue(coroutineContext.isActive, "collector-local overflow must not cancel the publisher owner")
        healthyJob.cancelAndJoin()
    }

    @Test
    fun productionCapacityBurstEmitsOneOverflowEventAndPreservesHealthyHistory() = runTest(UnconfinedTestDispatcher()) {
        val publisher = IrohFramePublisher()
        val stallGate = CompletableDeferred<Unit>()
        val stalledJob = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.events.collect { stallGate.await() }
        }
        val overflowEvents = mutableListOf<FrameCollectorOverflowEvent>()
        val overflowJob = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.collectorOverflows.collect { overflowEvents += it }
        }
        val healthy = mutableListOf<String>()
        val healthyJob = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.frameEvents.collect { healthy += it.frame.id }
        }

        val expected = (1..129).map { "frame-$it" }
        expected.forEach { id -> publisher.publish(assistantFrame(id)) }
        runCurrent()

        assertEquals(expected, healthy)
        assertEquals(expected.size, healthy.toSet().size)
        assertEquals(1, overflowEvents.size)
        assertEquals(IrohFramePublisher.EVENTS_SUBSCRIPTION, overflowEvents.single().subscriptionIdentity)
        assertEquals(IrohFramePublisher.DEFAULT_BUFFER_CAPACITY, overflowEvents.single().capacity)
        assertEquals("assistant_message", overflowEvents.single().frameType)
        assertEquals("conv-1", overflowEvents.single().conversationId)

        stallGate.complete(Unit)
        stalledJob.join()
        assertTrue(stalledJob.isCancelled, "detachment uses normal coroutine cancellation")

        val late = mutableListOf<String>()
        val lateJob = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.events.collect { late += it.id }
        }
        assertEquals(emptyList(), late, "detached history must not replay")
        publisher.publish(assistantFrame("after-burst"))
        runCurrent()
        assertEquals(listOf("after-burst"), late)
        assertEquals(expected + "after-burst", healthy)

        lateJob.cancelAndJoin()
        healthyJob.cancelAndJoin()
        overflowJob.cancelAndJoin()
    }

    @Test
    fun cancellationDoesNotEmitOverflowEvent() = runTest(UnconfinedTestDispatcher()) {
        val publisher = IrohFramePublisher(bufferCapacity = 1)
        val overflowEvents = mutableListOf<FrameCollectorOverflowEvent>()
        val overflowJob = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.collectorOverflows.collect { overflowEvents += it }
        }
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.events.collect { /* drain */ }
        }

        collector.cancelAndJoin()
        (1..3).forEach { publisher.publish(assistantFrame("after-cancel-$it")) }
        runCurrent()

        assertEquals(emptyList(), overflowEvents)
        overflowJob.cancelAndJoin()
    }

    @Test
    fun concurrentOverflowEventsRemainOnePerDetachedSubscription() = runTest(UnconfinedTestDispatcher()) {
        val publisher = IrohFramePublisher(bufferCapacity = 1)
        val stallGate = CompletableDeferred<Unit>()
        val overflowEvents = mutableListOf<FrameCollectorOverflowEvent>()
        val overflowJob = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.collectorOverflows.collect { overflowEvents += it }
        }
        val stalledJobs = (1..8).map {
            launch(start = CoroutineStart.UNDISPATCHED) {
                publisher.events.collect { stallGate.await() }
            }
        }

        (1..3).forEach { publisher.publish(assistantFrame("overflow-$it")) }
        runCurrent()

        assertEquals(8, overflowEvents.size)
        assertEquals(8, overflowEvents.map { it.subscriptionId }.toSet().size)
        assertTrue(overflowEvents.all { it.subscriptionIdentity == IrohFramePublisher.EVENTS_SUBSCRIPTION })
        assertTrue(overflowEvents.all { it.capacity == 1 })

        stallGate.complete(Unit)
        stalledJobs.forEach { it.join() }
        assertTrue(stalledJobs.all { it.isCancelled })
        overflowJob.cancelAndJoin()
    }

    @Test
    fun overflowProvenanceSurvivesUntilRecoveryObserverAttaches() = runTest(UnconfinedTestDispatcher()) {
        val publisher = IrohFramePublisher(bufferCapacity = 1)
        val stallGate = CompletableDeferred<Unit>()
        val stalledJob = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.frameEvents.collect { stallGate.await() }
        }

        (1..3).forEach { publisher.publish(assistantFrame("offline-overflow-$it")) }
        runCurrent()

        val observed = mutableListOf<FrameCollectorOverflowEvent>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.collectorOverflows.collect {
                observed += it
            }
        }
        runCurrent()

        assertEquals(1, observed.size)
        assertEquals(IrohFramePublisher.FRAME_EVENTS_SUBSCRIPTION, observed.single().subscriptionIdentity)
        assertEquals("conv-1", observed.single().conversationId)

        stallGate.complete(Unit)
        stalledJob.join()
        observer.cancelAndJoin()
    }

    @Test
    fun cancellationAndReconnectHaveNoReplayDuplicatesOrRegistrationRace() = runTest(UnconfinedTestDispatcher()) {
        val publisher = IrohFramePublisher(bufferCapacity = 2)
        val first = mutableListOf<String>()
        val firstJob = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.events.collect { first += it.id }
        }
        publisher.publish(assistantFrame("before-disconnect"))
        runCurrent()
        firstJob.cancelAndJoin()

        publisher.publish(assistantFrame("during-gap"))
        val reconnected = mutableListOf<String>()
        val reconnectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.events.collect { reconnected += it.id }
        }
        publisher.publish(assistantFrame("after-reconnect"))
        runCurrent()

        assertEquals(listOf("before-disconnect"), first)
        assertEquals(listOf("after-reconnect"), reconnected)
        reconnectJob.cancelAndJoin()
    }

    @Test
    fun bothDestinationsReceiveIdenticalOrderAcrossInterleavedPublishers() = runTest(UnconfinedTestDispatcher()) {
        val publisher = IrohFramePublisher(bufferCapacity = 16)
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        val firstJob = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.events.collect { first += it.id }
        }
        val secondJob = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.frameEvents.collect { second += it.frame.id }
        }

        val odd = launch { (1..9 step 2).forEach { publisher.publish(assistantFrame("msg-$it")) } }
        val even = launch { (2..10 step 2).forEach { publisher.publish(assistantFrame("msg-$it")) } }
        odd.join()
        even.join()
        runCurrent()

        assertEquals(10, first.size)
        assertEquals(first, second, "the publication mutex defines one order shared by every destination")
        assertEquals(10, first.toSet().size, "no duplicate frame is delivered")
        firstJob.cancelAndJoin()
        secondJob.cancelAndJoin()
    }

    private fun assistantFrame(id: String) = ServerFrame.AssistantMessage(
        id = id,
        ts = "2026-08-23T00:00:00Z",
        conversationId = "conv-1",
        content = id,
    )
}
