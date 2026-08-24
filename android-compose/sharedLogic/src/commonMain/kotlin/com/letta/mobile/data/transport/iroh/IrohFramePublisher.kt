package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

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
    scope: CoroutineScope,
    bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
) {
    private val canonicalEvents = MutableSharedFlow<TransportFrameEvent>(
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frameEvents: SharedFlow<TransportFrameEvent> = canonicalEvents.asSharedFlow()

    /** A derived projection; [canonicalEvents] is the only publication source. */
    val events: SharedFlow<ServerFrame> = canonicalEvents
        .map { event -> event.frame}
        .shareIn(scope, started = SharingStarted.Eagerly, replay = 0)

    fun publish(frame: ServerFrame) {
        canonicalEvents.tryEmit(TransportFrameEvent(frame = frame))
    }

    companion object {
        const val DEFAULT_BUFFER_CAPACITY = 64
    }
}
