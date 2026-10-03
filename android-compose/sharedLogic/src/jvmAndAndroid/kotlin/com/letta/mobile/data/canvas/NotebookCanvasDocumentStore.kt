package com.letta.mobile.data.canvas

import com.letta.mobile.util.Telemetry
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Which notebook last held each canvas. Only a hint: a hit is checked against that notebook's
     * metadata (one small read) and falls back to the full scan if it no longer matches, so a
     * remote edit or a set-aside document can never make it answer wrongly.
     */
    private val notebookOf = ConcurrentHashMap<CanvasId, DocumentId>()

    /** The notebook holding the first canvas whose metadata matches; boards are not decoded. */
    private fun find(match: (CanvasDocument) -> Boolean): DocumentId? =
        notebooks.listDocuments().firstOrNull { id -> notebooks.canvasMetadata(id)?.let(match) == true }

    /** [find] by canvas id, answering from [notebookOf] when its hint still holds. */
    private fun findCanvas(id: CanvasId): DocumentId? {
        notebookOf[id]?.let { hint ->
            if (notebooks.canvasMetadata(hint)?.id == id) return hint
            notebookOf.remove(id, hint)
        }
        return find { it.id == id }?.also { notebookOf[id] = it }
    }

    /**
     * A write the store refused because it is read-only. The board already shows why (a
     * READ_ONLY fault, logged at ERROR when it was raised); the session keeps the edit in memory.
     */
    private fun refused(canvasId: CanvasId, error: NotebookReadOnlyException) {
        Telemetry.event(
            NotebookStorageFaults.TAG, "write_refused",
            "canvasId" to canvasId.value, "reason" to error.message,
            level = Telemetry.Level.WARN,
        )
    }

    override suspend fun deletedElements(id: CanvasId): List<CanvasDeletedElement> = locked {
        findCanvas(id)?.let(notebooks::deletedBoardElements) ?: emptyList()
    }

    private fun all(): List<CanvasDocument> = notebooks.listDocuments().mapNotNull { notebooks.canvasDocument(it) }

    override suspend fun get(id: CanvasId): CanvasDocument? = locked {
        findCanvas(id)?.let(notebooks::canvasDocument)
    }

    override suspend fun getForConversation(conversationId: String): CanvasDocument? = locked {
        find { it.conversationId == conversationId }?.let(notebooks::canvasDocument)
    }

    override suspend fun upsert(doc: CanvasDocument) = locked {
        try {
            val target = findCanvas(doc.id) ?: notebooks.create(doc.title)
            notebookOf[doc.id] = target
            notebooks.writeCanvas(target, doc, null)
        } catch (error: NotebookReadOnlyException) {
            refused(doc.id, error)
        }
        Unit
    }

    override suspend fun upsertIfRevision(doc: CanvasDocument, expectedRevision: Long): Boolean = locked {
        val target = findCanvas(doc.id) ?: return@locked false
        try {
            notebooks.writeCanvas(target, doc, expectedRevision)
        } catch (error: NotebookReadOnlyException) {
            refused(doc.id, error)
            false
        }
    }

    override suspend fun createForConversationIfAbsent(doc: CanvasDocument): CanvasDocument = locked {
        val conversation = requireNotNull(doc.conversationId) { "createForConversationIfAbsent needs a conversationId" }
        find { it.conversationId == conversation }?.let(notebooks::canvasDocument) ?: run {
            require(findCanvas(doc.id) == null) { "Canvas ID already belongs to another conversation" }
            try {
                val target = notebooks.create(doc.title)
                notebookOf[doc.id] = target
                notebooks.writeCanvas(target, doc, null)
            } catch (error: NotebookReadOnlyException) {
                refused(doc.id, error)
            }
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
