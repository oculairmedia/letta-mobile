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
        val documentId = notebooks.resolveStorageId(notebookStorageId(id))
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
        val documentId = notebooks.resolveStorageId(notebookStorageId(applied.id))
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
            else -> NotebookNoteSave.Saved(current)
        }
    }

    private fun snapshot(id: NotebookDocumentId): NotebookNoteSnapshot? = notebooks.read(notebookStorageId(id))?.let {
        NotebookNoteSnapshot(it.id, it.title, it.markdown)
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

}

/** Decode the exact logical notebook ID expected by the existing local repository. */
internal fun notebookStorageId(id: NotebookDocumentId): DocumentId {
    return requireNotNull(notebookStorageIdOrNull(id)) { "Notebook ID must be the local store's lowercase hex DocumentId" }
}

/** [notebookStorageId], or null when [id] cannot name a local notebook (see [namesLocalNotebook]). */
internal fun notebookStorageIdOrNull(id: NotebookDocumentId): DocumentId? {
    if (!namesLocalNotebook(id)) return null
    return DocumentId.fromBytes(id.value.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
}

/** Whether [id] has the only form a local notebook ID takes: a DocumentId's 16 bytes as 32 lowercase hex digits. */
internal fun namesLocalNotebook(id: NotebookDocumentId): Boolean {
    return id.value.length == LOCAL_NOTEBOOK_ID_CHARS && id.value.all(::isLowercaseHexDigit)
}

private fun isLowercaseHexDigit(char: Char): Boolean {
    return char in '0'..'9' || char in 'a'..'f'
}

private const val LOCAL_NOTEBOOK_ID_CHARS = 32

/** The inverse of [notebookStorageId]: a storage DocumentId as the notebook ID notes and references use. */
internal fun notebookDocumentId(id: DocumentId): NotebookDocumentId =
    NotebookDocumentId(id.getBytes().joinToString("") { byte -> (byte.toInt() and 255).toString(16).padStart(2, '0') })
