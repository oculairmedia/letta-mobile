package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.api.FrameCollectorOverflowIncident
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class FrameCollectorDetachedCancellation(
    val subscriptionId: Long,
    val subscriptionIdentity: String,
    val connectionGeneration: Long,
) : CancellationException("Iroh frame collector detached after overflow")

/**
 * Single-source canonical publisher for Iroh transport frames.
 *
 * Each collector owns an independent bounded queue. Publishing is serialized,
 * so every attached collector observes the same order, while a stalled
 * collector cannot backpressure healthy collectors or the ingest path. A queue
 * that fills is detached and failed explicitly instead of silently evicting an
 * older frame. The retained overflow event gives the session lifecycle owner
 * the exact subscription and conversation provenance required to reconcile.
 *
 * Replay remains zero: registering after a frame was published never receives
 * that old frame. Registration, publication, overflow removal, and cancellation
 * removal all share one mutex, closing the attach/detach versus fan-out races.
 */
internal class IrohFramePublisher(
    bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
    private val connectionGeneration: () -> Long = { 0L },
) {
    private val overflowEvents = BoundedOverflowBroadcast(DEFAULT_OVERFLOW_EVENT_CAPACITY)
    private val canonicalEvents = BoundedFrameBroadcast(
        bufferCapacity = bufferCapacity,
        onOverflow = overflowEvents::publish,
        connectionGeneration = connectionGeneration,
    )

    val collectorOverflows: SharedFlow<FrameCollectorOverflowEvent> = overflowEvents

    val frameEvents: SharedFlow<TransportFrameEvent> =
        NamedTransportFrameSharedFlow(FRAME_EVENTS_SUBSCRIPTION, canonicalEvents)

    /** A synchronous projection; collecting it directly collects [canonicalEvents]. */
    val events: SharedFlow<ServerFrame> =
        ServerFrameSharedFlow(EVENTS_SUBSCRIPTION, canonicalEvents)

    /** Publishes once to every currently attached collector without awaiting one collector's drain. */
    suspend fun publish(frame: ServerFrame) {
        canonicalEvents.publish(TransportFrameEvent(frame = frame))
    }

    companion object {
        const val DEFAULT_BUFFER_CAPACITY = 64
        const val DEFAULT_OVERFLOW_EVENT_CAPACITY = 64
        const val EVENTS_SUBSCRIPTION = "events"
        const val FRAME_EVENTS_SUBSCRIPTION = "frameEvents"
    }
}

/** Recovery provenance emitted once when a corrupt bounded subscription is detached. */
internal typealias FrameCollectorOverflowEvent = FrameCollectorOverflowIncident

/**
 * Bounded recovery provenance with a retained latest incident.
 *
 * Unlike a replay-zero MutableSharedFlow, an incident published before the
 * lifecycle owner attaches cannot disappear. Slow telemetry observers are
 * disconnected rather than backpressuring frame ingest; provenance remains
 * queryable through [replayCache] for reconciliation.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class BoundedOverflowBroadcast(
    private val bufferCapacity: Int,
) : SharedFlow<FrameCollectorOverflowEvent> {
    private val mutex = Mutex()
    private val subscriptions = mutableListOf<Channel<FrameCollectorOverflowEvent>>()
    private val latest = atomic<FrameCollectorOverflowEvent?>(null)

    init {
        require(bufferCapacity > 0) { "bufferCapacity must be positive" }
    }

    override val replayCache: List<FrameCollectorOverflowEvent>
        get() = latest.value?.let(::listOf).orEmpty()

    suspend fun publish(event: FrameCollectorOverflowEvent) {
        val overflowed = mutex.withLock {
            latest.value = event
            val failed = subscriptions.filter { it.trySend(event).isFailure }
            subscriptions.removeAll(failed)
            failed
        }
        overflowed.forEach { it.close() }
    }

    override suspend fun collect(collector: FlowCollector<FrameCollectorOverflowEvent>): Nothing {
        val subscription = Channel<FrameCollectorOverflowEvent>(bufferCapacity)
        mutex.withLock {
            latest.value?.let { subscription.trySend(it) }
            subscriptions += subscription
        }
        try {
            while (true) {
                val event = subscription.receiveCatching().getOrNull()
                    ?: throw CancellationException("Iroh overflow observer detached")
                collector.emit(event)
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { subscriptions.remove(subscription) }
                subscription.cancel()
            }
        }
    }
}

private fun ServerFrame.overflowConversationId(): String = (when (this) {
    is ServerFrame.A2ui -> conversationId
    is ServerFrame.UserActionOutcome -> conversationId
    is ServerFrame.Error -> conversationId
    is ServerFrame.TurnStarted -> conversationId
    is ServerFrame.UserMessage -> conversationId
    is ServerFrame.AssistantMessage -> conversationId
    is ServerFrame.ReasoningMessage -> conversationId
    is ServerFrame.ToolCallMessage -> conversationId
    is ServerFrame.ToolReturnMessage -> conversationId
    else -> null
}).orEmpty()

private fun ServerFrame.overflowFrameType(): String = when (this) {
    is ServerFrame.A2ui -> type
    is ServerFrame.UserActionOutcome -> type
    is ServerFrame.Error -> type
    is ServerFrame.TurnStarted -> type
    is ServerFrame.UserMessage -> type
    is ServerFrame.AssistantMessage -> type
    is ServerFrame.ReasoningMessage -> type
    is ServerFrame.ToolCallMessage -> type
    is ServerFrame.ToolReturnMessage -> type
    else -> this::class.simpleName ?: "server_frame"
}

/**
 * A replay-zero broadcast with one bounded channel per collector.
 *
 * [publish] never suspends on a destination channel. Full destinations are
 * atomically detached before their channel is closed, preventing subsequent
 * frames from entering an already-invalid stream.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class BoundedFrameBroadcast(
    private val bufferCapacity: Int,
    private val onOverflow: suspend (FrameCollectorOverflowEvent) -> Unit,
    private val connectionGeneration: () -> Long,
) : SharedFlow<TransportFrameEvent> {
    private val mutex = Mutex()
    private val subscriptions = mutableListOf<FrameSubscription>()
    private var nextSubscriptionId = 1L

    init {
        require(bufferCapacity > 0) { "bufferCapacity must be positive" }
    }

    override val replayCache: List<TransportFrameEvent> = emptyList()

    suspend fun publish(event: TransportFrameEvent) {
        val overflowed = mutex.withLock {
            val failed = mutableListOf<FrameSubscription>()
            subscriptions.forEach { subscription ->
                if (subscription.frames.trySend(event).isFailure) failed += subscription
            }
            subscriptions.removeAll(failed)
            failed
        }
        withContext(NonCancellable) {
            overflowed.forEach { subscription ->
                try {
                    onOverflow(
                        FrameCollectorOverflowEvent(
                            subscriptionId = subscription.id,
                            subscriptionIdentity = subscription.identity,
                            capacity = bufferCapacity,
                            frameType = event.frame.overflowFrameType(),
                            conversationId = event.frame.overflowConversationId(),
                            connectionGeneration = subscription.connectionGeneration,
                        ),
                    )
                } finally {
                    subscription.frames.close()
                }
            }
        }
    }

    suspend fun collect(
        identity: String,
        collector: FlowCollector<TransportFrameEvent>,
    ): Nothing {
        val subscription = mutex.withLock {
            FrameSubscription(
                id = nextSubscriptionId++,
                identity = identity,
                connectionGeneration = connectionGeneration(),
                frames = Channel(bufferCapacity),
            ).also { subscriptions += it }
        }
        try {
            while (true) {
                val result = subscription.frames.receiveCatching()
                val event = result.getOrNull()
                if (event != null) {
                    collector.emit(event)
                } else {
                    throw FrameCollectorDetachedCancellation(
                        subscriptionId = subscription.id,
                        subscriptionIdentity = subscription.identity,
                        connectionGeneration = subscription.connectionGeneration,
                    )
                }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { subscriptions.remove(subscription) }
                subscription.frames.cancel()
            }
        }
    }

    override suspend fun collect(collector: FlowCollector<TransportFrameEvent>): Nothing =
        collect("canonical", collector)

    private class FrameSubscription(
        val id: Long,
        val identity: String,
        val connectionGeneration: Long,
        val frames: Channel<TransportFrameEvent>,
    )
}

/** Names a canonical projection without adding another queue. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class NamedTransportFrameSharedFlow(
    private val identity: String,
    private val canonicalEvents: BoundedFrameBroadcast,
) : SharedFlow<TransportFrameEvent> {
    override val replayCache: List<TransportFrameEvent>
        get() = canonicalEvents.replayCache

    override suspend fun collect(collector: FlowCollector<TransportFrameEvent>): Nothing =
        canonicalEvents.collect(identity, collector)
}

/** Maps the canonical flow inline without owning a coroutine, buffer, or queue. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class ServerFrameSharedFlow(
    private val identity: String = IrohFramePublisher.EVENTS_SUBSCRIPTION,
    private val canonicalEvents: SharedFlow<TransportFrameEvent>,
) : SharedFlow<ServerFrame> {
    override val replayCache: List<ServerFrame>
        get() = canonicalEvents.replayCache.map(TransportFrameEvent::frame)

    override suspend fun collect(collector: FlowCollector<ServerFrame>): Nothing =
        if (canonicalEvents is BoundedFrameBroadcast) {
            canonicalEvents.collect(identity) { event -> collector.emit(event.frame) }
        } else {
            canonicalEvents.collect { event -> collector.emit(event.frame) }
        }
}
