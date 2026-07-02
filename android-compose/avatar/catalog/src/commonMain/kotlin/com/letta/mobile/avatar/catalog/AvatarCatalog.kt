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
 * All mutations persist through the [store] before the public [entries] flow
 * updates, so observers never see state that would be lost on restart.
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

    /** Insert [model], replacing any existing entry with the same id. */
    suspend fun upsert(model: AvatarModel) {
        require(model.id.isNotBlank()) { "Catalog entries need a non-blank id" }
        mutex.withLock {
            val next = (_entries.value.filterNot { it.id == model.id } + model)
                .sortedForCatalog()
            store.save(next)
            _entries.value = next
        }
    }

    /** Remove the entry with [id]; returns whether anything was removed. */
    suspend fun remove(id: String): Boolean =
        mutex.withLock {
            val current = _entries.value
            val next = current.filterNot { it.id == id }
            if (next.size == current.size) return@withLock false
            store.save(next)
            _entries.value = next
            true
        }

    fun get(id: String): AvatarModel? = _entries.value.firstOrNull { it.id == id }

    private fun List<AvatarModel>.sortedForCatalog(): List<AvatarModel> =
        sortedBy { it.displayName.lowercase() }
}
