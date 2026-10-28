package com.letta.mobile.data.canvas.notes

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookLocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.automerge.repo.DocumentId

/**
 * Adapter for a trusted local-owned [notebooks] store, never a remote authorization boundary.
 * IDs are lowercase hex DocumentId bytes; after compaction the original ID remains the note's
 * logical identity and the store resolves it to the current storage generation.
 * The store's serialized document transaction guards stale Markdown. Its filesystem lock covers
 * this adapter's mutation and flush, but does not make independent repositories merge disk state
 * or lock remote peers. Later peer merges follow Automerge; explicit refresh discovers them.
 */
class LocalNotebookNoteBackend(private val notebooks: NotebookLocalStore) : NotebookNoteBackend {
    override suspend fun load(id: NotebookDocumentId): NotebookNoteLoad = try {
        locked { snapshot(id)?.let(NotebookNoteLoad::Found) ?: NotebookNoteLoad.NotFound }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expectedFailure: Exception) {
        NotebookNoteLoad.Unavailable(expectedFailure.message ?: "Local notebook read failed")
    }

    override suspend fun save(id: NotebookDocumentId, expected: String, replacement: String): NotebookNoteSave = guarded {
        val documentId = notebooks.resolveStorageId(storageId(id))
            ?: return@guarded NotebookNoteSave.Unavailable("Local notebook not found")
        val before = snapshot(id) ?: return@guarded NotebookNoteSave.Unavailable("Local notebook not found")
        if (!notebooks.replaceMarkdown(documentId, expected, replacement)) {
            return@guarded snapshot(id)?.let { NotebookNoteSave.Conflict(it) }
                ?: NotebookNoteSave.Unavailable("Local notebook not found")
        }
        // Capture the accepted text, not a later peer merge. The title is not edited by this API.
        persist(before.copy(markdown = replacement), documentId)
    }

    override suspend fun flush(applied: NotebookNoteSnapshot): NotebookNoteSave = guarded {
        val documentId = notebooks.resolveStorageId(storageId(applied.id))
            ?: return@guarded NotebookNoteSave.Unavailable("Local notebook not found")
        persist(applied, documentId)
    }

    private fun persist(applied: NotebookNoteSnapshot, id: DocumentId): NotebookNoteSave {
        return try {
            if (!notebooks.persistCanvas(id)) {
                NotebookNoteSave.PendingDurability(applied, "Local notebook persistence failed; retry to flush")
            } else {
                persistedResult(applied)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: InterruptedException) {
            throw error
        } catch (expectedFailure: Exception) {
            NotebookNoteSave.PendingDurability(applied, expectedFailure.message ?: "Local notebook persistence failed")
        }
    }

    private fun persistedResult(applied: NotebookNoteSnapshot): NotebookNoteSave {
        val current = snapshot(applied.id)
        return when {
            current == null -> NotebookNoteSave.Unavailable("Local notebook disappeared after persistence")
            current.markdown != applied.markdown -> NotebookNoteSave.Conflict(current, durable = applied)
            else -> NotebookNoteSave.Saved(applied)
        }
    }

    private fun snapshot(id: NotebookDocumentId): NotebookNoteSnapshot? = notebooks.read(storageId(id))?.let {
        NotebookNoteSnapshot(it.id, it.title, it.markdown)
    }

    private fun storageId(id: NotebookDocumentId): DocumentId {
        require(id.value.length == DOCUMENT_HEX_LENGTH && id.value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Notebook ID must be the local store's lowercase hex DocumentId"
        }
        return DocumentId.fromBytes(id.value.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    }

    private suspend fun <T> locked(action: () -> T): T = runInterruptible(Dispatchers.IO) {
        notebooks.withCanvasLock(action)
    }

    private suspend fun guarded(action: () -> NotebookNoteSave): NotebookNoteSave = try {
        locked(action)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expectedFailure: Exception) {
        NotebookNoteSave.Unavailable(expectedFailure.message ?: "Local notebook write failed")
    }

    private companion object {
        const val DOCUMENT_HEX_LENGTH = 32
    }
}
