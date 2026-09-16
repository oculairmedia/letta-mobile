package com.letta.mobile.data.canvas

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    private val flowsByCanvas = mutableMapOf<CanvasId, MutableSharedFlow<CanvasOp>>()

    override suspend fun append(canvasId: CanvasId, op: CanvasOp) = mutex.withLock {
        val opIds = opIdsByCanvas.getOrPut(canvasId) { mutableSetOf() }
        if (!opIds.add(op.opId)) {
            // Already present, idempotent ignore
            return@withLock
        }
        val list = opsByCanvas.getOrPut(canvasId) { mutableListOf() }
        list.add(op)
        val flow = flowsByCanvas[canvasId]
        flow?.emit(op)
        Unit
    }

    override suspend fun getOps(canvasId: CanvasId, sinceLamport: Long): List<CanvasOp> = mutex.withLock {
        val list = opsByCanvas[canvasId] ?: return emptyList()
        list.filter { it.lamport > sinceLamport }
    }

    override suspend fun has(canvasId: CanvasId, opId: String): Boolean = mutex.withLock {
        opIdsByCanvas[canvasId]?.contains(opId) == true
    }

    override fun observe(canvasId: CanvasId): Flow<CanvasOp> {
        val flow = flowsByCanvas.getOrPut(canvasId) {
            MutableSharedFlow(replay = 16, extraBufferCapacity = 64)
        }
        return flow.asSharedFlow()
    }
}
