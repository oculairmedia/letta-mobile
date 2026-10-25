package com.letta.mobile.data.canvas.notes

import com.letta.mobile.data.canvas.NotebookDocumentId
import kotlinx.coroutines.CompletableDeferred

internal class NoteOperationGate {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    suspend fun await() {
        entered.complete(Unit)
        release.await()
    }
}

internal class NoteTestBackend(id: NotebookDocumentId) : NotebookNoteBackend {
    var source = NotebookNoteSnapshot(id, "Note", "original")
    var durable = source
    var persist = true
    var saves = 0
    var flushes = 0
    var loads = 0
    var saveGate: NoteOperationGate? = null
    var afterApplyGate: NoteOperationGate? = null
    var flushGate: NoteOperationGate? = null
    var afterPersistGate: NoteOperationGate? = null
    var loadGate: NoteOperationGate? = null
    var loadResult: NotebookNoteLoad? = null
    var saveResult: NotebookNoteSave? = null

    override suspend fun load(id: NotebookDocumentId): NotebookNoteLoad {
        check(id == source.id)
        loads++
        loadGate?.await()
        return loadResult ?: NotebookNoteLoad.Found(source)
    }

    override suspend fun save(id: NotebookDocumentId, expected: String, replacement: String): NotebookNoteSave {
        check(id == source.id)
        saves++
        saveGate?.await()
        saveResult?.let { return it }
        if (source.markdown != expected) return NotebookNoteSave.Conflict(source)
        source = source.copy(markdown = replacement)
        afterApplyGate?.await()
        return persistence(source)
    }

    override suspend fun flush(applied: NotebookNoteSnapshot): NotebookNoteSave {
        flushes++
        flushGate?.await()
        return persistence(applied)
    }

    private suspend fun persistence(applied: NotebookNoteSnapshot): NotebookNoteSave {
        if (persist) {
            durable = source
            afterPersistGate?.await()
        }
        return when {
            !persist -> NotebookNoteSave.PendingDurability(applied, "disk failed")
            source.markdown != applied.markdown -> NotebookNoteSave.Conflict(source, durable = applied)
            else -> NotebookNoteSave.Saved(applied)
        }
    }
}
