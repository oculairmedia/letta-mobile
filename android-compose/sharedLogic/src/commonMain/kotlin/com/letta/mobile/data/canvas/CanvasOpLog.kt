package com.letta.mobile.data.canvas

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Append-only log of [CanvasOp] operations for a canvas document.
 */
interface CanvasOpLog {
    suspend fun append(canvasId: CanvasId, op: CanvasOp)
    suspend fun getOps(canvasId: CanvasId, sinceLamport: Long = 0L): List<CanvasOp>
    suspend fun has(canvasId: CanvasId, opId: String): Boolean
    fun observe(canvasId: CanvasId): Flow<CanvasOp>
}

/**
 * Thread-safe in-memory implementation of [CanvasOpLog].
 */
class InMemoryCanvasOpLog : CanvasOpLog {
    private val mutex = Mutex()
    private val opsByCanvas = mutableMapOf<CanvasId, MutableList<CanvasOp>>()
    private val opIdsByCanvas = mutableMapOf<CanvasId, MutableSet<String>>()
    // Reached from the non-suspending observe path as well, so it cannot live behind the mutex.
    // See [getOrCreate].
    private val flowsByCanvas = MutableStateFlow<Map<CanvasId, MutableSharedFlow<CanvasOp>>>(emptyMap())

    private fun flowFor(canvasId: CanvasId): MutableSharedFlow<CanvasOp> =
        flowsByCanvas.getOrCreate(canvasId) { MutableSharedFlow(replay = 16, extraBufferCapacity = 64) }

    override suspend fun append(canvasId: CanvasId, op: CanvasOp) {
        val appended = mutex.withLock {
            val opIds = opIdsByCanvas.getOrPut(canvasId) { mutableSetOf() }
            if (!opIds.add(op.opId)) {
                false // Already present, idempotent ignore
            } else {
                opsByCanvas.getOrPut(canvasId) { mutableListOf() }.add(op)
                true
            }
        }
        // Outside the lock: emit suspends once the buffer fills, and a collector that needs this
        // same log would then be waiting on a lock its publisher is still holding.
        if (appended) flowFor(canvasId).emit(op)
    }

    override suspend fun getOps(canvasId: CanvasId, sinceLamport: Long): List<CanvasOp> = mutex.withLock {
        val list = opsByCanvas[canvasId] ?: return emptyList()
        list.filter { it.lamport > sinceLamport }
    }

    override suspend fun has(canvasId: CanvasId, opId: String): Boolean = mutex.withLock {
        opIdsByCanvas[canvasId]?.contains(opId) == true
    }

    override fun observe(canvasId: CanvasId): Flow<CanvasOp> = flowFor(canvasId).asSharedFlow()
}
