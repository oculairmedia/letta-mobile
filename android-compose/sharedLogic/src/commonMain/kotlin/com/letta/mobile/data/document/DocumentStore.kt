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
 * Where an [InMemoryDocumentStore] keeps its documents beyond memory (the web's IndexedDB, through
 * a [com.letta.mobile.data.storage.KeyValueStore]). Neither call throws: a backing reports its own
 * failures as storage faults, and the store keeps working from memory.
 */
interface DocumentBacking<D> {
    /** Every stored document; what cannot be read is reported and left out. */
    suspend fun loadAll(): List<D>

    /** Stores [doc], replacing what is stored under its id. */
    suspend fun write(doc: D)
}

/**
 * Thread-safe [DocumentStore] held in memory, for tests, hosts without storage and, over a
 * [backing], hosts whose storage is a plain key-value store: the documents are read from it once,
 * on first use, and every write goes through to it. Open so a document kind's own store can add
 * its lookups over the same map ([withDocuments], [writeDocument]) without copying the revision check.
 */
open class InMemoryDocumentStore<Id, D : DocumentSnapshot<Id>>(
    private val backing: DocumentBacking<D>? = null,
) : DocumentStore<Id, D> {
    private val mutex = Mutex()
    private val documents = mutableMapOf<Id, D>()
    private var loaded = backing == null

    /** Runs [action] over the stored documents under the store's lock; [action] only reads. */
    protected suspend fun <T> withDocuments(action: (Map<Id, D>) -> T): T = mutex.withLock {
        ensureLoaded()
        action(documents)
    }

    /**
     * Runs [action] under the store's lock; the document it returns beside its result, if any, is
     * stored (in memory and in the backing).
     */
    protected suspend fun <T> writeDocument(action: (Map<Id, D>) -> Pair<T, D?>): T = mutex.withLock {
        ensureLoaded()
        val (result, written) = action(documents)
        if (written != null) {
            documents[written.id] = written
            backing?.write(written)
        }
        result
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        backing?.loadAll()?.forEach { documents[it.id] = it }
        loaded = true
    }

    override suspend fun get(id: Id): D? = withDocuments { it[id] }

    override suspend fun upsert(doc: D) = writeDocument { Unit to doc }

    override suspend fun upsertIfRevision(doc: D, expectedRevision: Long): Boolean = writeDocument { stored ->
        val current = stored[doc.id]
        if (current == null || current.revision != expectedRevision) false to null else true to doc
    }

    override suspend fun listAll(): List<D> = withDocuments { stored ->
        stored.values.sortedByDescending { it.updatedAtEpochMs }
    }
}
