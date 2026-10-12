package com.letta.mobile.data.document

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persistence contract for the snapshots of one kind of shared document.
 *
 * The canvas's [com.letta.mobile.data.canvas.CanvasDocumentStore] is this contract plus its
 * conversation lookups; its notebook implementation keeps each snapshot in an Automerge document.
 */
interface DocumentStore<Id, D : DocumentSnapshot<Id>> {
    suspend fun get(id: Id): D?

    /** Writes [doc] whatever is stored: creates it, or replaces the stored revision. */
    suspend fun upsert(doc: D)

    /**
     * Persists [doc] only if the stored document's revision is still [expectedRevision]. The
     * compare and the write are one atomic step, so two writers that both read revision N cannot
     * both land: exactly one sees `true`, the other sees `false` and must re-read.
     *
     * A document that does not exist yet has no revision to compare, so this returns `false` for
     * it; use [upsert] to create.
     */
    suspend fun upsertIfRevision(doc: D, expectedRevision: Long): Boolean

    /** Every stored document, most recently updated first: the library view. */
    suspend fun listAll(): List<D>
}

/**
 * Thread-safe in-memory [DocumentStore], for tests and hosts without storage. Open so a document
 * kind's own store can add its lookups over the same map ([withDocuments]) without copying the
 * revision check.
 */
open class InMemoryDocumentStore<Id, D : DocumentSnapshot<Id>> : DocumentStore<Id, D> {
    private val mutex = Mutex()
    private val documents = mutableMapOf<Id, D>()

    /** Runs [action] over the stored documents under the store's lock. */
    protected suspend fun <T> withDocuments(action: (MutableMap<Id, D>) -> T): T = mutex.withLock { action(documents) }

    override suspend fun get(id: Id): D? = withDocuments { it[id] }

    override suspend fun upsert(doc: D) = withDocuments { it[doc.id] = doc }

    override suspend fun upsertIfRevision(doc: D, expectedRevision: Long): Boolean = withDocuments { stored ->
        val current = stored[doc.id]
        if (current == null || current.revision != expectedRevision) {
            false
        } else {
            stored[doc.id] = doc
            true
        }
    }

    override suspend fun listAll(): List<D> = withDocuments { stored ->
        stored.values.sortedByDescending { it.updatedAtEpochMs }
    }
}
