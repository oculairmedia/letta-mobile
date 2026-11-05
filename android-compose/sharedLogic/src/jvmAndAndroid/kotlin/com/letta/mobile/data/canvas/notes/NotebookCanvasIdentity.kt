package com.letta.mobile.data.canvas.notes

import com.letta.mobile.data.canvas.CanvasNotebookIdentity
import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * [CanvasNotebookIdentity] over the notebook repository a canvas document store reads: a
 * canvas's notebook is the one whose canvas metadata carries its ID. Reads take the repository's
 * canvas lock, as the store's do, and run on [Dispatchers.IO].
 */
class NotebookCanvasIdentity(private val notebooks: NotebookLocalStore) : CanvasNotebookIdentity {
    /** The notebook's current storage generation; a note opened by an earlier ID still resolves to it. */
    override suspend fun notebookOf(canvasId: CanvasId): NotebookDocumentId? = locked {
        notebooks.listDocuments().firstOrNull { notebooks.canvasMetadata(it)?.id == canvasId }?.let(::notebookDocumentId)
    }

    override suspend fun canvasOf(notebook: NotebookDocumentId): CanvasId? = locked {
        val storage = runCatching { notebookStorageId(notebook) }.getOrNull() ?: return@locked null
        notebooks.resolveStorageId(storage)?.let(notebooks::canvasMetadata)?.id
    }

    private suspend fun <T> locked(action: () -> T): T = runInterruptible(Dispatchers.IO) { notebooks.withCanvasLock(action) }
}
