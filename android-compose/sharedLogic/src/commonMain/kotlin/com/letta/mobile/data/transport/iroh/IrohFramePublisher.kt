package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single-source canonical publisher for Iroh transport frames.
 *
 * Each collector owns an independent bounded queue. Publishing is serialized,
 * so every attached collector observes the same order, while a stalled
 * collector cannot backpressure healthy collectors or the ingest path. A queue
 * that fills is detached and failed explicitly instead of silently evicting an
 * older frame. The collector can then restart and reconcile through the normal
 * message-list hydration path.
 *
 * Replay remains zero: registering after a frame was published never receives
 * that old frame. Registration, publication, overflow removal, and cancellation
 * removal all share one mutex, closing the attach/detach versus fan-out races.
 */
internal class IrohFramePublisher(
    bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
) {
    private val canonicalEvents = BoundedFrameBroadcast(bufferCapacity)

    val frameEvents: SharedFlow<TransportFrameEvent> = canonicalEvents

    /** A synchronous projection; collecting it directly collects [canonicalEvents]. */
    val events: SharedFlow<ServerFrame> = ServerFrameSharedFlow(canonicalEvents)

    /** Publishes once to every currently attached collector without awaiting one collector's drain. */
    suspend fun publish(frame: ServerFrame) {
        canonicalEvents.publish(TransportFrameEvent(frame = frame))
    }

    companion object {
        const val DEFAULT_BUFFER_CAPACITY = 64
    }
}

/** Explicit signal that a collector must reconnect/reconcile; no frame is silently dropped. */
internal class FrameCollectorOverflowException : IllegalStateException(
    "Iroh frame collector exceeded its bounded queue; reconnect and reconcile",
)

/**
 * A replay-zero broadcast with one bounded channel per collector.
 *
 * [publish] never suspends on a destination channel. Full destinations are
 * atomically detached before their channel is failed, preventing subsequent
 * frames from entering an already-invalid stream.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class BoundedFrameBroadcast(
    private val bufferCapacity: Int,
) : SharedFlow<TransportFrameEvent> {
    private val mutex = Mutex()
    private val subscriptions = mutableListOf<FrameSubscription>()

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
        overflowed.forEach { subscription ->
            subscription.frames.close(FrameCollectorOverflowException())
        }
    }

    override suspend fun collect(collector: FlowCollector<TransportFrameEvent>): Nothing {
        val subscription = FrameSubscription(Channel(bufferCapacity))
        mutex.withLock { subscriptions += subscription }
        try {
            while (true) {
                val result = subscription.frames.receiveCatching()
                val event = result.getOrNull()
                if (event != null) {
                    collector.emit(event)
                } else {
                    throw result.exceptionOrNull()
                        ?: CancellationException("Iroh frame collector closed")
                }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { subscriptions.remove(subscription) }
                subscription.frames.cancel()
            }
        }
    }

    private class FrameSubscription(
        val frames: Channel<TransportFrameEvent>,
    )
}

/** Maps the canonical flow inline without owning a coroutine, buffer, or queue. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class ServerFrameSharedFlow(
    private val canonicalEvents: SharedFlow<TransportFrameEvent>,
) : SharedFlow<ServerFrame> {
    override val replayCache: List<ServerFrame>
        get() = canonicalEvents.replayCache.map(TransportFrameEvent::frame)

    override suspend fun collect(collector: FlowCollector<ServerFrame>): Nothing =
        canonicalEvents.collect { event -> collector.emit(event.frame) }
}
