package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IrohFrameOverflowRecoveryTest {

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
            publisher.collectorOverflows.collect { observed += it }
        }
        runCurrent()

        assertEquals(1, observed.size)
        assertEquals(IrohFramePublisher.FRAME_EVENTS_SUBSCRIPTION, observed.single().subscriptionIdentity)
        assertEquals("conv-1", observed.single().conversationId)

        stallGate.complete(Unit)
        stalledJob.join()
        observer.cancelAndJoin()
    }

    private fun assistantFrame(id: String) = ServerFrame.AssistantMessage(
        id = id,
        ts = "2026-08-23T00:00:00Z",
        conversationId = "conv-1",
        content = id,
    )
}
