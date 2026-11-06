package com.letta.mobile.data.canvas.authority

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.notes.NotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookNoteLoad
import com.letta.mobile.data.canvas.notes.NotebookNoteSave
import com.letta.mobile.data.canvas.notes.NotebookNoteSession
import com.letta.mobile.data.canvas.notes.NotebookNoteSnapshot
import com.letta.mobile.data.canvas.references.NotebookReferenceAccess
import com.letta.mobile.data.canvas.references.NotebookReferenceLoad
import com.letta.mobile.data.canvas.references.NotebookReferenceResolver
import com.letta.mobile.data.canvas.references.NotebookReferenceSession
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceOpen
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceOpener
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceSessions
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/**
 * A client's access to notebooks shared with it by a host. The host decides every call from the
 * caller's authenticated endpoint and its grant for that document; this side holds no grant and
 * never shows content the host did not return for the current call. A transport failure is
 * unavailable, not denied and not empty.
 */
class RemoteNotebookAccess(rpc: AdminRpcInvoker) {
    private val host = NotebookRpc(rpc)

    /** Every load is a fresh `notebook.note.open`, so remounting a note rechecks the grant. */
    val backend: NotebookNoteBackend = RemoteNoteBackend(host)

    /** The host checks the read grant inside each resolve; [NotebookReferenceResolver] asks twice. */
    val resolver = NotebookReferenceResolver(
        authorize = { id ->
            when (resolve(id)) {
                is NotebookReferenceLoad.Found, NotebookReferenceLoad.Missing -> NotebookReferenceAccess.ALLOWED
                NotebookReferenceLoad.Denied -> NotebookReferenceAccess.DENIED
                NotebookReferenceLoad.Unavailable -> NotebookReferenceAccess.UNAVAILABLE
            }
        },
        readAuthorized = ::resolve,
    )

    /** Opens a shared note only when the host grants edit access to it now. */
    val opener = DocumentWorkspaceOpener { id ->
        when (backend.load(id)) {
            is NotebookNoteLoad.Found -> DocumentWorkspaceOpen.Opened(
                DocumentWorkspaceSessions(NotebookNoteSession(id, backend), NotebookReferenceSession(id, resolver)),
            )
            NotebookNoteLoad.Denied -> DocumentWorkspaceOpen.Denied
            NotebookNoteLoad.NotFound, is NotebookNoteLoad.Unavailable -> DocumentWorkspaceOpen.Unavailable
        }
    }

    private suspend fun resolve(id: NotebookDocumentId): NotebookReferenceLoad =
        host.call(NotebookAccessWire.RESOLVE, NotebookAccessWire.request(id), { NotebookReferenceLoad.Unavailable }) {
            NotebookAccessWire.readReference(it)
        }
}

private class RemoteNoteBackend(private val host: NotebookRpc) : NotebookNoteBackend {
    override suspend fun load(id: NotebookDocumentId): NotebookNoteLoad =
        host.call(NotebookAccessWire.OPEN, NotebookAccessWire.request(id), { NotebookNoteLoad.Unavailable(it) }) {
            NotebookAccessWire.readLoad(id, it)
        }

    override suspend fun save(id: NotebookDocumentId, expected: String, replacement: String): NotebookNoteSave {
        val body = NotebookAccessWire.request(id) {
            put("expected", expected)
            put("replacement", replacement)
        }
        return host.call(NotebookAccessWire.SAVE, body, { NotebookNoteSave.Unavailable(it) }) {
            NotebookAccessWire.readSave(id, it)
        }
    }

    override suspend fun flush(applied: NotebookNoteSnapshot): NotebookNoteSave {
        val body = NotebookAccessWire.request(applied.id) { put("snapshot", NotebookAccessWire.snapshot(applied)) }
        return host.call(NotebookAccessWire.FLUSH, body, { NotebookNoteSave.Unavailable(it) }) {
            NotebookAccessWire.readSave(applied.id, it)
        }
    }
}

/** One `notebook.*` call; a failed call becomes [onFailure]'s result and cancellation propagates. */
private class NotebookRpc(private val rpc: AdminRpcInvoker) {
    suspend fun <T> call(method: String, body: JsonObject, onFailure: (String) -> T, read: (JsonElement?) -> T): T =
        try {
            read(rpc.invoke(method, body))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expectedFailure: Exception) {
            onFailure(expectedFailure.message ?: "The host could not be reached")
        }
}
