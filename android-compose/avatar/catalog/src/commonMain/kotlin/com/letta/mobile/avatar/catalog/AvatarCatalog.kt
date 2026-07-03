package com.letta.mobile.avatar.catalog

import com.letta.mobile.avatar.core.AvatarModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persistence boundary for the catalog. Implementations are dumb byte-level
 * stores; ordering, dedup, and mutation rules live in [AvatarCatalog].
 */
interface AvatarCatalogStore {
    suspend fun load(): List<AvatarModel>
    suspend fun save(entries: List<AvatarModel>)
}

/** Volatile store — tests and previews. */
class InMemoryAvatarCatalogStore(
    initial: List<AvatarModel> = emptyList(),
) : AvatarCatalogStore {
    private var entries: List<AvatarModel> = initial.toList()

    override suspend fun load(): List<AvatarModel> = entries

    override suspend fun save(entries: List<AvatarModel>) {
        this.entries = entries.toList()
    }
}

/**
 * Local/offline avatar catalog: the app-facing registry of imported avatars.
 * Entries are [AvatarModel]s — asset locations plus license/provenance — with
 * the full normalized metadata living in each entry's manifest file.
 *
 * Mutation semantics:
 * - Every mutation RELOADS from the [store] and merges against the persisted
 *   contents inside the catalog lock, so an `upsert` on a freshly-constructed
 *   catalog never clobbers an existing `catalog.json`, and interleaved writers
 *   against the same store lose at most their own in-flight entry rather than
 *   each other's. (True multi-process transactionality would need store-level
 *   locking; a catalog directory still assumes cooperating writers.)
 * - Persistence happens BEFORE the public [entries] flow updates, so observers
 *   never see state that would be lost on restart.
 */
class AvatarCatalog(
    private val store: AvatarCatalogStore,
) {
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<AvatarModel>>(emptyList())

    /** Catalog entries, sorted by display name (case-insensitive). */
    val entries: StateFlow<List<AvatarModel>> = _entries.asStateFlow()

    /** Load (or re-load) the catalog from the store. */
    suspend fun refresh() {
        mutex.withLock {
            _entries.value = store.load().sortedForCatalog()
        }
    }

    /** Insert [model], replacing any persisted entry with the same id. */
    suspend fun upsert(model: AvatarModel) {
        require(model.id.isNotBlank()) { "Catalog entries need a non-blank id" }
        mutex.withLock {
            val persisted = store.load()
            val next = (persisted.filterNot { it.id == model.id } + model)
                .sortedForCatalog()
            store.save(next)
            _entries.value = next
        }
    }

    /** Remove the entry with [id]; returns whether anything was removed. */
    suspend fun remove(id: String): Boolean =
        mutex.withLock {
            val persisted = store.load()
            val next = persisted.filterNot { it.id == id }
            if (next.size == persisted.size) {
                _entries.value = persisted.sortedForCatalog()
                return@withLock false
            }
            store.save(next)
            _entries.value = next.sortedForCatalog()
            true
        }

    /**
     * Look up an entry in the last-observed snapshot ([entries]); call
     * [refresh] first if another writer may have changed the store.
     */
    fun get(id: String): AvatarModel? = _entries.value.firstOrNull { it.id == id }

    private fun List<AvatarModel>.sortedForCatalog(): List<AvatarModel> =
        sortedBy { it.displayName.lowercase() }
}
