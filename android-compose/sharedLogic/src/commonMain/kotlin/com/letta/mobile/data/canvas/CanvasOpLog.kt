package com.letta.mobile.data.canvas

import com.letta.mobile.data.document.InMemoryOpLog
import com.letta.mobile.data.document.OpLog
import kotlinx.coroutines.flow.Flow

/**
 * Append-only log of [CanvasOp] operations for a canvas document: the shared-document [OpLog]
 * for canvases.
 */
@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE") // canvas callers and implementers name the id canvasId
interface CanvasOpLog : OpLog<CanvasId, CanvasOp> {
    override suspend fun append(canvasId: CanvasId, op: CanvasOp)
    override suspend fun getOps(canvasId: CanvasId, sinceLamport: Long): List<CanvasOp>
    override suspend fun has(canvasId: CanvasId, opId: String): Boolean
    override fun observe(canvasId: CanvasId): Flow<CanvasOp>
}

/**
 * Thread-safe in-memory implementation of [CanvasOpLog].
 */
@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE") // keeps the canvas's canvasId names for named callers
class InMemoryCanvasOpLog : InMemoryOpLog<CanvasId, CanvasOp>(), CanvasOpLog {
    override suspend fun append(canvasId: CanvasId, op: CanvasOp) = super.append(canvasId, op)

    override suspend fun getOps(canvasId: CanvasId, sinceLamport: Long): List<CanvasOp> = super.getOps(canvasId, sinceLamport)

    override suspend fun has(canvasId: CanvasId, opId: String): Boolean = super.has(canvasId, opId)

    override fun observe(canvasId: CanvasId): Flow<CanvasOp> = super.observe(canvasId)
}
