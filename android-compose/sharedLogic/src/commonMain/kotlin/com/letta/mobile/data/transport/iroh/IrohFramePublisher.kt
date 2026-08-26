package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Single-source canonical publisher for Iroh transport frames.
 *
 * Encapsulates:
 * - A single canonical [TransportFrameEvent] [SharedFlow]
 * - Derived [ServerFrame] projection for [events]
 * - Bounded, LOSSLESS broadcast: the buffer is finite but overflow SUSPENDS
 *   the producer instead of silently evicting buffered frames
 * - Structural isolation preventing split histories across asymmetric consumers
 *
 * letta-mobile-p0gc (Pixel ANR causal slice B): this publisher used
 * `extraBufferCapacity = 64` + [BufferOverflow.DROP_OLDEST] + `tryEmit`.
 * When a consumer stalled on Main awaiting a timeline ack, older buffered
 * frames — typically the active run's ToolCall/ToolReturn frames — were
 * silently evicted: tool cards never rendered and returns lost their call
 * correlation, with no error anywhere. Now [publish] is a **suspending**
 * emit over a bounded buffer:
 *
 * - A stalled subscriber applies backpressure to producers (all of which
 *   already run on background dispatchers via suspend paths — never Main),
 *   so no frame is dropped while ANY attached consumer is still draining.
 * - Delivery per subscriber stays strictly ordered and exactly-once.
 * - The buffer bound (64) caps memory; producers pause rather than evict.
 *
 * Replay remains 0: a late subscriber never receives past frames (same
 * contract as before — see IrohFrameFlowDropTest).
 */
internal class IrohFramePublisher(
    bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
) {
    private val canonicalEvents = MutableSharedFlow<TransportFrameEvent>(
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val frameEvents: SharedFlow<TransportFrameEvent> = canonicalEvents.asSharedFlow()

    /** A synchronous projection; collecting it directly collects [canonicalEvents]. */
    val events: SharedFlow<ServerFrame> = ServerFrameSharedFlow(canonicalEvents)

    /**
     * Lossless publish. Suspends while the bounded buffer is full for the
     * slowest attached consumer (backpressure) instead of dropping frames.
     */
    suspend fun publish(frame: ServerFrame) {
        canonicalEvents.emit(TransportFrameEvent(frame = frame))
    }

    companion object {
        const val DEFAULT_BUFFER_CAPACITY = 64
    }
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
