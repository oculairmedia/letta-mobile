package com.letta.mobile.data.canvas

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Transport contract for real-time synchronization of [CanvasOp]s across peers.
 */
interface CanvasSyncTransport {
    suspend fun publish(canvasId: CanvasId, op: CanvasOp)
    fun subscribe(canvasId: CanvasId): Flow<CanvasOp>
}

/**
 * Loopback in-memory transport multiplexing ops across sessions on the same topic/canvasId.
 * Used for local multi-window collaboration, multi-client simulation, and integration tests.
 */
class LoopbackCanvasSyncTransport : CanvasSyncTransport {
    // One source of truth for both paths: the publisher and the subscriber must reach the same
    // flow, or the subscriber silently receives nothing. See [getOrCreate].
    private val flowsByCanvas = MutableStateFlow<Map<CanvasId, MutableSharedFlow<CanvasOp>>>(emptyMap())

    private fun flowFor(canvasId: CanvasId): MutableSharedFlow<CanvasOp> =
        flowsByCanvas.getOrCreate(canvasId) { MutableSharedFlow(replay = 32, extraBufferCapacity = 64) }

    override suspend fun publish(canvasId: CanvasId, op: CanvasOp) {
        flowFor(canvasId).emit(op)
    }

    override fun subscribe(canvasId: CanvasId): Flow<CanvasOp> = flowFor(canvasId).asSharedFlow()
}
