package com.letta.mobile.data.canvas

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which canvases are archived: set aside from the library's everyday list without being deleted,
 * the way conversations are archived.
 *
 * Kept apart from [CanvasDocument] on purpose. Archiving is how this device files its library,
 * not part of the canvas: an open [CanvasSession] writes its document back on every edit from the
 * copy it holds, so a flag on the document would be quietly undone by the next stroke.
 */
interface CanvasArchiveStore {
    suspend fun archivedIds(): Set<CanvasId>

    suspend fun setArchived(id: CanvasId, archived: Boolean)
}

/** A [CanvasArchiveStore] that lasts as long as the process: tests, and hosts without storage. */
class InMemoryCanvasArchiveStore : CanvasArchiveStore {
    private val mutex = Mutex()
    private val ids = mutableSetOf<CanvasId>()

    override suspend fun archivedIds(): Set<CanvasId> = mutex.withLock { ids.toSet() }

    override suspend fun setArchived(id: CanvasId, archived: Boolean) {
        mutex.withLock { if (archived) ids += id else ids -= id }
    }
}

/** Which part of the library a list shows, as the conversation list's Active / Archived / All. */
enum class CanvasArchiveFilter { ACTIVE, ARCHIVED, ALL }

/** The canvas library as a list shows it. */
object CanvasLibrary {
    /** [documents] narrowed to [filter], order kept, given which are [archived]. */
    fun filter(
        documents: List<CanvasDocument>,
        archived: Set<CanvasId>,
        filter: CanvasArchiveFilter,
    ): List<CanvasDocument> = when (filter) {
        CanvasArchiveFilter.ACTIVE -> documents.filter { it.id !in archived }
        CanvasArchiveFilter.ARCHIVED -> documents.filter { it.id in archived }
        CanvasArchiveFilter.ALL -> documents
    }
}
