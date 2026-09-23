package com.letta.mobile.data.canvas

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Transport contract for real-time synchronization of [CanvasOp]s across peers.
 */
interface CanvasSyncTransport {
    suspend fun publish(canvasId: CanvasId, op: CanvasOp)
    fun subscribe(canvasId: CanvasId): Flow<CanvasOp>

    /**
     * Whether [canvasId] is shared right now. A transport that never leaves the process keeps the
     * default, [CanvasSyncHealth.LocalOnly]: it has no way to claim otherwise.
     */
    fun health(canvasId: CanvasId): StateFlow<CanvasSyncHealth> = LOCAL_PROCESS_ONLY.health

    /**
     * Hands every remote op for [canvasId] to [apply], one at a time, until cancelled; returns only
     * when cancelled. [apply] returns once the op is applied and persisted, which lets a transport
     * that records progress durably (a host cursor) record it only after the op is safe - never
     * before, where a crash would skip it for good. The default collects [subscribe].
     */
    suspend fun deliverTo(canvasId: CanvasId, apply: suspend (CanvasOp) -> Unit) {
        subscribe(canvasId).collect { apply(it) }
    }
}

private val LOCAL_PROCESS_ONLY = LocalOnlyCanvasSyncHealth("Not connected to a host; this canvas stays on this device")

/**
 * Loopback in-memory transport multiplexing ops across sessions on the same topic/canvasId.
 * Used for local multi-window collaboration, multi-client simulation, and integration tests.
 */
class LoopbackCanvasSyncTransport : CanvasSyncTransport {
    /** Sessions in this process only: never anything but local, whatever it is asked to report. */
    val localHealth = LocalOnlyCanvasSyncHealth("Local only: sessions in this process share, nothing leaves it")

    override fun health(canvasId: CanvasId): StateFlow<CanvasSyncHealth> = localHealth.health

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
