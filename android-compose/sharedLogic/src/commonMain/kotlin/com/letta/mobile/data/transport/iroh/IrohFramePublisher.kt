package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import kotlinx.coroutines.channels.BufferOverflow
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
    private val _canonicalEvents = MutableSharedFlow<TransportFrameEvent>(
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frameEvents: SharedFlow<TransportFrameEvent> = _canonicalEvents.asSharedFlow()

    private val _events = MutableSharedFlow<ServerFrame>(
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<ServerFrame> = _events.asSharedFlow()

    fun publish(frame: ServerFrame) {
        val event = TransportFrameEvent(frame = frame)
        _canonicalEvents.tryEmit(event)
        _events.tryEmit(frame)
    }

    companion object {
        const val DEFAULT_BUFFER_CAPACITY = 64
    }
}
