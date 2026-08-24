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
 * - Non-suspending, bounded broadcast with [BufferOverflow.DROP_OLDEST]
 * - Structural isolation preventing split histories across asymmetric consumers
 */
internal class IrohFramePublisher(
    bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
) {
    private val canonicalEvents = MutableSharedFlow<TransportFrameEvent>(
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frameEvents: SharedFlow<TransportFrameEvent> = canonicalEvents.asSharedFlow()

    /** A synchronous projection; collecting it directly collects [canonicalEvents]. */
    val events: SharedFlow<ServerFrame> = ServerFrameSharedFlow(canonicalEvents)

    fun publish(frame: ServerFrame) {
        canonicalEvents.tryEmit(TransportFrameEvent(frame = frame))
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
