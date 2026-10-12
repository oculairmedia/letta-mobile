package com.letta.mobile.data.document

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Append-only log of the ops applied to each document of one kind. Appending an op id the log
 * already holds is an idempotent no-op, which is what makes redelivery safe.
 */
interface OpLog<Id, Op : DocumentOp> {
    suspend fun append(documentId: Id, op: Op)

    /** The ops on [documentId] with a Lamport clock above [sinceLamport]. */
    suspend fun getOps(documentId: Id, sinceLamport: Long = 0L): List<Op>

    suspend fun has(documentId: Id, opId: String): Boolean

    /** Ops as they are appended (a short replay for late collectors). */
    fun observe(documentId: Id): Flow<Op>
}

/**
 * An [OpLog] that can let go of ops another place keeps: once a [SharedDocument] has archived a
 * stretch of history ([OpArchive]) and continued from the snapshot it led to, the log drops that
 * stretch so it stays bounded. A log that cannot ([OpLog] alone) simply keeps everything.
 *
 * [has] must keep answering true for dropped op ids, or a redelivered archived op would be applied
 * a second time; ids are small next to the ops, so remembering them is the cheap part of the bound.
 */
interface CompactableOpLog<Id, Op : DocumentOp> : OpLog<Id, Op> {
    suspend fun dropArchived(documentId: Id, opIds: Set<String>)
}

/**
 * Thread-safe in-memory [CompactableOpLog], for tests and transient documents. Open so a document
 * kind can name its own in-memory log (the canvas's [com.letta.mobile.data.canvas.InMemoryCanvasOpLog]).
 */
open class InMemoryOpLog<Id, Op : DocumentOp> : CompactableOpLog<Id, Op> {
    private val mutex = Mutex()
    private val opsByDocument = mutableMapOf<Id, MutableList<Op>>()
    private val opIdsByDocument = mutableMapOf<Id, MutableSet<String>>()

    // Reached from the non-suspending observe path as well, so it cannot live behind the mutex.
    // See [getOrCreate].
    private val flowsByDocument = MutableStateFlow<Map<Id, MutableSharedFlow<Op>>>(emptyMap())

    private fun flowFor(documentId: Id): MutableSharedFlow<Op> =
        flowsByDocument.getOrCreate(documentId) { MutableSharedFlow(replay = 16, extraBufferCapacity = 64) }

    override suspend fun append(documentId: Id, op: Op) {
        val appended = mutex.withLock {
            val opIds = opIdsByDocument.getOrPut(documentId) { mutableSetOf() }
            opIds.add(op.opId) && opsByDocument.getOrPut(documentId) { mutableListOf() }.add(op)
        }
        // Outside the lock: emit suspends once the buffer fills, and a collector that needs this
        // same log would then be waiting on a lock its publisher is still holding.
        if (appended) flowFor(documentId).emit(op)
    }

    override suspend fun getOps(documentId: Id, sinceLamport: Long): List<Op> = mutex.withLock {
        opsByDocument[documentId]?.filter { it.lamport > sinceLamport }.orEmpty()
    }

    override suspend fun has(documentId: Id, opId: String): Boolean = mutex.withLock {
        opIdsByDocument[documentId]?.contains(opId) == true
    }

    override fun observe(documentId: Id): Flow<Op> = flowFor(documentId).asSharedFlow()

    override suspend fun dropArchived(documentId: Id, opIds: Set<String>) {
        mutex.withLock { opsByDocument[documentId]?.removeAll { it.opId in opIds } }
    }
}
