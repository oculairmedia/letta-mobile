package com.letta.mobile.data.canvas

import com.letta.mobile.data.document.InMemoryOpLog
import com.letta.mobile.data.document.OpLog
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.CanvasOpId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Append-only log of [CanvasOp] operations for a canvas document: the shared-document [OpLog]
 * for canvases.
 *
 * The log is also the write-ahead journal of the stored document. The
 * document lives in another store (the Automerge notebook) with no transaction shared with the
 * log, so a [CanvasSession] appends an op first, writes the document, and only then [settle]s the
 * op. An op appended but never settled - the process died, or the document write failed, in
 * between - is [unsettled]: the next session on the canvas folds it into the document before
 * anything else happens. A log that cannot journal keeps the defaults and reports nothing pending.
 */
@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE") // canvas callers and implementers name the id canvasId
interface CanvasOpLog : OpLog<CanvasId, CanvasOp> {
    override suspend fun append(canvasId: CanvasId, op: CanvasOp)
    override suspend fun getOps(canvasId: CanvasId, sinceLamport: Long): List<CanvasOp>
    /** Whether [canvasId]'s log holds op [opId] (dropped or not). */
    suspend fun has(canvasId: CanvasId, opId: CanvasOpId): Boolean

    /** The generic [OpLog] lookup, which [com.letta.mobile.data.document.SharedDocument] calls with the op's wire id. */
    override suspend fun has(documentId: CanvasId, opId: String): Boolean = has(documentId, CanvasOpId(opId))

    override fun observe(canvasId: CanvasId): Flow<CanvasOp>

    /** Ops on [canvasId] appended but not yet [settle]d, in the order they were appended. */
    suspend fun unsettled(canvasId: CanvasId): List<CanvasOp> = emptyList()

    /** Records that the stored document of [canvasId] now holds [opIds]. Idempotent. */
    suspend fun settle(canvasId: CanvasId, opIds: Collection<CanvasOpId>) = Unit

    /**
     * Whether this log has let go of settled history of [canvasId] (a bounded log past its budget):
     * its ops then no longer rebuild the stored board from nothing, only the board itself does
     * ([CanvasSnapshotSeed]). A log that keeps everything never has.
     */
    suspend fun hasDroppedHistory(canvasId: CanvasId): Boolean = false
}

/**
 * Thread-safe in-memory implementation of [CanvasOpLog], journal included.
 */
@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE") // keeps the canvas's canvasId names for named callers
class InMemoryCanvasOpLog : InMemoryOpLog<CanvasId, CanvasOp>(), CanvasOpLog {
    private val journalLock = Mutex()
    private val pending = mutableMapOf<CanvasId, LinkedHashMap<String, CanvasOp>>()

    override suspend fun append(canvasId: CanvasId, op: CanvasOp) {
        if (super<InMemoryOpLog>.has(canvasId, op.opId)) return
        journalLock.withLock { pending.getOrPut(canvasId) { LinkedHashMap() }[op.opId] = op }
        super.append(canvasId, op)
    }

    override suspend fun getOps(canvasId: CanvasId, sinceLamport: Long): List<CanvasOp> = super.getOps(canvasId, sinceLamport)

    override suspend fun has(canvasId: CanvasId, opId: CanvasOpId): Boolean = super<InMemoryOpLog>.has(canvasId, opId.value)

    // Both supertypes implement the generic lookup; the in-memory log's own index answers it.
    override suspend fun has(documentId: CanvasId, opId: String): Boolean = super<InMemoryOpLog>.has(documentId, opId)

    override fun observe(canvasId: CanvasId): Flow<CanvasOp> = super.observe(canvasId)

    override suspend fun unsettled(canvasId: CanvasId): List<CanvasOp> =
        journalLock.withLock { pending[canvasId]?.values?.toList().orEmpty() }

    override suspend fun settle(canvasId: CanvasId, opIds: Collection<CanvasOpId>) {
        journalLock.withLock { pending[canvasId]?.keys?.removeAll(opIds.mapTo(mutableSetOf()) { it.value }) }
    }

    /** Puts a stored log back: [ops] in their order, those not in [settled] pending again. */
    internal suspend fun restore(canvasId: CanvasId, ops: List<CanvasOp>, settled: Set<String>) {
        journalLock.withLock {
            val journal = pending.getOrPut(canvasId) { LinkedHashMap() }
            ops.filterNot { it.opId in settled }.forEach { journal[it.opId] = it }
        }
        super.restore(canvasId, ops)
    }
}
