package com.letta.mobile.data.session

import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.api.FrameCollectorOverflowAwareChannelTransport
import com.letta.mobile.data.transport.api.FrameCollectorOverflowIncident
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionScopedChannelTransportRecoveryTest {

    @Test
    fun `overflow reconciles once reattaches and preserves subsequent frame once`() = runTest {
        val transport = OverflowAwareFakeTransport()
        val fixture = fixture(transport, this) { _, _ -> RecentMessagesReconcileOutcome.Applied(3) }
        val received = mutableListOf<String>()
        val projection = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.proxy.events.collect { received += it.id }
        }
        runCurrent()

        transport.emitFrame(assistantFrame("before"))
        transport.overflow(EVENTS, conversationId = "conv-1")
        runCurrent()
        transport.emitFrame(assistantFrame("after"))
        runCurrent()

        assertEquals(listOf("conv-1" to 1L), fixture.reconcileCalls)
        assertEquals(listOf("before", "after"), received)
        assertEquals(2, transport.eventsProjection.collectCount)

        // The retained incident is replayed when the graph collection is rebuilt.
        fixture.graphFlow.value = fixture.graph
        runCurrent()
        assertEquals(1, fixture.reconcileCalls.size)

        projection.cancelAndJoin()
        fixture.proxy.close()
    }

    @Test
    fun `graph switch cancels old recovery and ordinary cancellation never retries`() = runTest {
        val oldTransport = OverflowAwareFakeTransport()
        val newTransport = OverflowAwareFakeTransport()
        val reconcileStarted = CompletableDeferred<Unit>()
        val allowReconcile = CompletableDeferred<Unit>()
        val fixture = fixture(oldTransport, this) { _, _ ->
            reconcileStarted.complete(Unit)
            allowReconcile.await()
            RecentMessagesReconcileOutcome.Applied(0)
        }
        val projection = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.proxy.events.collect()
        }
        runCurrent()

        oldTransport.overflow(EVENTS, conversationId = "conv-old")
        reconcileStarted.await()
        val nextGraph = graph(id = 2L, transport = newTransport)
        fixture.graphFlow.value = nextGraph
        runCurrent()
        allowReconcile.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, oldTransport.eventsProjection.collectCount)
        assertEquals(1, newTransport.eventsProjection.collectCount)
        oldTransport.replayIncident()
        runCurrent()
        assertEquals(listOf("conv-old" to 1L), fixture.reconcileCalls)

        projection.cancelAndJoin()
        fixture.proxy.close()
    }

    @Test
    fun `same conversation incidents serialize and duplicate id is ignored`() = runTest {
        val transport = OverflowAwareFakeTransport()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        val fixture = fixture(transport, this) { _, _ ->
            active++
            maxActive = maxOf(maxActive, active)
            if (!firstEntered.isCompleted) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
            active--
            RecentMessagesReconcileOutcome.Applied(0)
        }

        transport.emitIncident(incident(id = 11L, conversationId = "conv-1"))
        firstEntered.await()
        transport.emitIncident(incident(id = 12L, conversationId = "conv-1"))
        transport.emitIncident(incident(id = 12L, conversationId = "conv-1"))
        runCurrent()
        assertEquals(1, fixture.reconcileCalls.size)

        releaseFirst.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, fixture.reconcileCalls.size)
        assertEquals(1, maxActive)
        fixture.proxy.close()
    }

    @Test
    fun `different conversations recover concurrently`() = runTest {
        val transport = OverflowAwareFakeTransport()
        val release = CompletableDeferred<Unit>()
        val entered = mutableSetOf<String>()
        val fixture = fixture(transport, this) { conversationId, _ ->
            entered += conversationId
            release.await()
            RecentMessagesReconcileOutcome.Applied(0)
        }

        transport.emitIncident(incident(id = 31L, conversationId = "conv-a"))
        transport.emitIncident(incident(id = 32L, conversationId = "conv-b"))
        runCurrent()

        assertEquals(setOf("conv-a", "conv-b"), entered)
        release.complete(Unit)
        advanceUntilIdle()
        fixture.proxy.close()
    }

    @Test
    fun `invalid stale failed skipped and thrown incidents are bounded`() = runTest {
        val transport = OverflowAwareFakeTransport()
        val outcomes = ArrayDeque<Any>(listOf(
            RecentMessagesReconcileOutcome.Skipped("busy"),
            RecentMessagesReconcileOutcome.Failed(IllegalStateException("server failed")),
            RecentMessagesReconcileOutcome.Failed(IllegalStateException("server failed again")),
            RecentMessagesReconcileOutcome.Failed(IllegalStateException("server still failed")),
            IllegalArgumentException("throwing"),
        ))
        val fixture = fixture(transport, this) { _, _ ->
            when (val next = outcomes.removeFirst()) {
                is Throwable -> throw next
                else -> next as RecentMessagesReconcileOutcome
            }
        }
        val observed = mutableListOf<FrameCollectorOverflowRecoveryEvent>()
        val monitor = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.proxy.recoveryEvents.collect { observed += it }
        }

        transport.emitIncident(incident(id = 1L, conversationId = ""))
        transport.emitIncident(incident(id = 2L, conversationId = "conv-stale", generation = 0L))
        transport.emitIncident(incident(id = 3L, conversationId = "conv-skip"))
        transport.emitIncident(incident(id = 4L, conversationId = "conv-failed"))
        transport.emitIncident(incident(id = 5L, conversationId = "conv-throw"))
        advanceUntilIdle()

        assertEquals(6, fixture.reconcileCalls.size)
        assertTrue(observed.any { it.subscriptionId == 1L && it.outcome is FrameCollectorOverflowRecoveryOutcome.InvalidIncident })
        assertTrue(observed.any { it.subscriptionId == 2L && it.outcome is FrameCollectorOverflowRecoveryOutcome.InvalidIncident })
        val terminals = observed.filter { it.outcome !is FrameCollectorOverflowRecoveryOutcome.Started }
            .associateBy { it.subscriptionId }
        assertTrue(terminals.keys.containsAll(setOf(1L, 2L)))

        monitor.cancelAndJoin()
        fixture.proxy.close()
    }

    @Test
    fun `slow recovery monitor cannot block incidents and close cancels in flight reconcile`() = runTest {
        val transport = OverflowAwareFakeTransport()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val fixture = fixture(transport, this) { _, _ ->
            calls.incrementAndGet()
            started.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                cancelled.complete(Unit)
            }
            RecentMessagesReconcileOutcome.Applied(0)
        }
        val slowMonitor = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.proxy.recoveryEvents.collect { delay(Long.MAX_VALUE) }
        }

        transport.emitIncident(incident(id = 20L, conversationId = "conv-close"))
        started.await()
        (21L..70L).forEach { id -> transport.emitIncident(incident(id = id, conversationId = "")) }
        runCurrent()
        assertEquals(1, calls.get())

        fixture.proxy.close()
        cancelled.await()
        slowMonitor.cancelAndJoin()
    }

    private fun fixture(
        transport: OverflowAwareFakeTransport,
        testScope: kotlinx.coroutines.test.TestScope,
        reconcile: suspend (String, Long) -> RecentMessagesReconcileOutcome,
    ): Fixture {
        val graph = graph(id = 1L, transport = transport)
        val graphFlow = MutableStateFlow(graph)
        val manager = mockk<SessionManager>(relaxed = true)
        every { manager.current } answers { graphFlow.value }
        every { manager.currentGraph } returns graphFlow
        val reconcileCalls = mutableListOf<Pair<String, Long>>()
        val proxy = SessionScopedChannelTransport(
            sessionManager = manager,
            proxyScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScope.testScheduler)),
            overflowReconciler = FrameCollectorOverflowReconciler { conversationId, generation ->
                reconcileCalls += conversationId to generation
                reconcile(conversationId, generation)
            },
        )
        testScope.runCurrent()
        return Fixture(proxy, graph, graphFlow, reconcileCalls)
    }

    private fun graph(id: Long, transport: OverflowAwareFakeTransport): SessionGraph =
        mockk<SessionGraph>(relaxed = true) {
            every { this@mockk.id } returns id
            every { channelTransport } returns transport
        }

    private fun incident(
        id: Long,
        conversationId: String,
        generation: Long = 1L,
        identity: String = EVENTS,
    ) = FrameCollectorOverflowIncident(
        subscriptionId = id,
        subscriptionIdentity = identity,
        capacity = 64,
        frameType = "assistant_message",
        conversationId = conversationId,
        connectionGeneration = generation,
    )

    private fun assistantFrame(id: String) = ServerFrame.AssistantMessage(
        id = id,
        ts = "2026-08-26T00:00:00Z",
        conversationId = "conv-1",
        content = "payload-$id",
    )

    private data class Fixture(
        val proxy: SessionScopedChannelTransport,
        val graph: SessionGraph,
        val graphFlow: MutableStateFlow<SessionGraph>,
        val reconcileCalls: MutableList<Pair<String, Long>>,
    )

    private class FakeOverflowCancellation(
        val identity: String,
        val generation: Long,
    ) : CancellationException("test overflow detach")

    @OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
    private class RestartableProjection<T> : SharedFlow<T> {
        private var current = ProjectionGeneration<T>()
        var collectCount: Int = 0
            private set

        override val replayCache: List<T> get() = emptyList()

        override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<T>): Nothing {
            val generation = current
            collectCount++
            channelFlow {
                val values = launch { generation.values.collect { send(it) } }
                val cancelled = generation.closed.await()
                values.cancelAndJoin()
                throw cancelled
            }.collect(collector)
            error("unreachable")
        }

        fun emit(value: T) {
            check(current.values.tryEmit(value))
        }

        fun detach(identity: String, generation: Long) {
            val previous = current
            current = ProjectionGeneration()
            previous.closed.complete(FakeOverflowCancellation(identity, generation))
        }

        private class ProjectionGeneration<T> {
            val values = MutableSharedFlow<T>(extraBufferCapacity = 16)
            val closed = CompletableDeferred<CancellationException>()
        }
    }

    private class OverflowAwareFakeTransport : NoOpChannelTransport(), FrameCollectorOverflowAwareChannelTransport {
        private val overflowFlow = MutableSharedFlow<FrameCollectorOverflowIncident>(
            replay = 1,
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val eventsProjection = RestartableProjection<ServerFrame>()
        private val frameProjection = RestartableProjection<TransportFrameEvent>()
        override val collectorOverflows: SharedFlow<FrameCollectorOverflowIncident> = overflowFlow
        override var frameCollectorConnectionGeneration: Long = 1L
        override val events: SharedFlow<ServerFrame> = eventsProjection
        override val frameEvents: SharedFlow<TransportFrameEvent> = frameProjection

        override fun isFrameCollectorOverflowCancellation(
            subscriptionIdentity: String,
            connectionGeneration: Long,
            cancellation: CancellationException,
        ): Boolean = cancellation is FakeOverflowCancellation &&
            cancellation.identity == subscriptionIdentity &&
            cancellation.generation == connectionGeneration

        fun emitFrame(frame: ServerFrame) = eventsProjection.emit(frame)
        fun emitIncident(incident: FrameCollectorOverflowIncident) {
            check(overflowFlow.tryEmit(incident))
        }
        fun replayIncident() {
            overflowFlow.replayCache.firstOrNull()?.let { check(overflowFlow.tryEmit(it)) }
        }
        fun overflow(identity: String, conversationId: String) {
            val incident = FrameCollectorOverflowIncident(
                subscriptionId = nextId++,
                subscriptionIdentity = identity,
                capacity = 64,
                frameType = "assistant_message",
                conversationId = conversationId,
                connectionGeneration = frameCollectorConnectionGeneration,
            )
            emitIncident(incident)
            if (identity == EVENTS) eventsProjection.detach(identity, frameCollectorConnectionGeneration)
        }

        companion object { var nextId = 1L }
    }

    private companion object {
        const val EVENTS = "events"
    }
}
