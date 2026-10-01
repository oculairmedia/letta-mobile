package com.letta.mobile.data.canvas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.automerge.repo.DocumentId

/** Canvas view of the notebook repository. Canvas identity and metadata live in each notebook CRDT,
 * not in a second canvas database. A repository lock serializes claims and revision checks.
 *
 * Every call blocks (a cross-process file lock, Automerge reads awaited on futures), so each runs on
 * [Dispatchers.IO]: callers on the UI thread (a remote op landing on an open board) otherwise
 * stalled rendering for the whole read. Lookups match on the canvas metadata alone and decode only
 * the board they return. */
class NotebookCanvasDocumentStore(private val notebooks: NotebookLocalStore) :
    CanvasDocumentStore, CanvasDeletedElementStore, CanvasStorageHealth {
    override val storageFaults: StateFlow<List<CanvasStorageFault>> get() = notebooks.storageFaults

    private suspend fun <T> locked(action: () -> T): T = withContext(Dispatchers.IO) { notebooks.withCanvasLock(action) }

    /** The notebook holding the first canvas whose metadata matches; boards are not decoded. */
    private fun find(match: (CanvasDocument) -> Boolean): DocumentId? =
        notebooks.listDocuments().firstOrNull { id -> notebooks.canvasMetadata(id)?.let(match) == true }

    override suspend fun deletedElements(id: CanvasId): List<CanvasDeletedElement> = locked {
        find { it.id == id }?.let(notebooks::deletedBoardElements) ?: emptyList()
    }

    private fun all(): List<CanvasDocument> = notebooks.listDocuments().mapNotNull { notebooks.canvasDocument(it) }

    override suspend fun get(id: CanvasId): CanvasDocument? = locked {
        find { it.id == id }?.let(notebooks::canvasDocument)
    }

    override suspend fun getForConversation(conversationId: String): CanvasDocument? = locked {
        find { it.conversationId == conversationId }?.let(notebooks::canvasDocument)
    }

    override suspend fun upsert(doc: CanvasDocument) = locked {
        val target = find { it.id == doc.id } ?: notebooks.create(doc.title)
        notebooks.writeCanvas(target, doc, null)
        Unit
    }

    override suspend fun upsertIfRevision(doc: CanvasDocument, expectedRevision: Long): Boolean = locked {
        val target = find { it.id == doc.id } ?: return@locked false
        notebooks.writeCanvas(target, doc, expectedRevision)
    }

    override suspend fun createForConversationIfAbsent(doc: CanvasDocument): CanvasDocument = locked {
        val conversation = requireNotNull(doc.conversationId) { "createForConversationIfAbsent needs a conversationId" }
        find { it.conversationId == conversation }?.let(notebooks::canvasDocument) ?: run {
            require(find { it.id == doc.id } == null) { "Canvas ID already belongs to another conversation" }
            val target = notebooks.create(doc.title)
            notebooks.writeCanvas(target, doc, null)
            doc
        }
    }

    override suspend fun listForAgent(agentId: String): List<CanvasDocument> = locked {
        notebooks.listDocuments()
            .filter { id -> notebooks.canvasMetadata(id)?.agentId == agentId }
            .mapNotNull(notebooks::canvasDocument)
            .sortedByDescending { it.updatedAtEpochMs }
    }

    override suspend fun listAll(): List<CanvasDocument> = locked {
        all().sortedByDescending { it.updatedAtEpochMs }
    }
}
