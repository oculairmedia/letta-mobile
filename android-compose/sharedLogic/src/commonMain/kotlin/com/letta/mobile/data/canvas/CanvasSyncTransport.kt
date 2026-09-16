package com.letta.mobile.data.canvas

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val mutex = Mutex()
    private val flowsByCanvas = mutableMapOf<CanvasId, MutableSharedFlow<CanvasOp>>()

    override suspend fun publish(canvasId: CanvasId, op: CanvasOp) {
        val flow = mutex.withLock {
            flowsByCanvas.getOrPut(canvasId) {
                MutableSharedFlow(replay = 32, extraBufferCapacity = 64)
            }
        }
        flow.emit(op)
    }

    override fun subscribe(canvasId: CanvasId): Flow<CanvasOp> {
        val flow = synchronized(flowsByCanvas) {
            flowsByCanvas.getOrPut(canvasId) {
                MutableSharedFlow(replay = 32, extraBufferCapacity = 64)
            }
        }
        return flow.asSharedFlow()
    }
}
