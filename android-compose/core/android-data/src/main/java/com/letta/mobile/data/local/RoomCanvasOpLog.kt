package com.letta.mobile.data.local

import com.letta.mobile.data.model.CanvasOpId
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Android Room-backed persistent implementation of [CanvasOpLog], journal included: rows are
 * appended unsettled and settled once the canvas document holding them is written
 * (letta-mobile-by4vm.22, see [CanvasOpLog.unsettled]).
 */
class RoomCanvasOpLog(
    private val dao: CanvasOpDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : CanvasOpLog {

    private val flowsByCanvas = ConcurrentHashMap<CanvasId, MutableSharedFlow<CanvasOp>>()

    override suspend fun append(canvasId: CanvasId, op: CanvasOp) {
        val entity = CanvasOpEntity.fromCanvasOp(canvasId, op, clock)
        val rowId = dao.insert(entity)
        if (rowId != -1L) {
            flowsByCanvas[canvasId]?.emit(op)
        }
    }

    override suspend fun getOps(canvasId: CanvasId, sinceLamport: Long): List<CanvasOp> {
        return dao.getSince(canvasId.value, sinceLamport).map { it.toCanvasOp() }
    }

    override suspend fun has(canvasId: CanvasId, opId: CanvasOpId): Boolean {
        return dao.has(canvasId.value, opId.value)
    }

    override suspend fun unsettled(canvasId: CanvasId): List<CanvasOp> =
        dao.getUnsettled(canvasId.value).map { it.toCanvasOp() }

    override suspend fun settle(canvasId: CanvasId, opIds: Collection<CanvasOpId>) {
        // SQLite caps bound parameters; a recovery after a long outage can settle many at once.
        opIds.map { it.value }.distinct().chunked(SETTLE_CHUNK).forEach { chunk -> dao.settle(canvasId.value, chunk) }
    }

    override fun observe(canvasId: CanvasId): Flow<CanvasOp> {
        val flow = flowsByCanvas.computeIfAbsent(canvasId) {
            MutableSharedFlow(replay = 16, extraBufferCapacity = 64)
        }
        return flow.asSharedFlow()
    }
}

private const val SETTLE_CHUNK = 500
