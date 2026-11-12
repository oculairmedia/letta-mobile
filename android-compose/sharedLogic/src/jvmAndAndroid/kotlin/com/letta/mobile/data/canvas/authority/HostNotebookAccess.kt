package com.letta.mobile.data.canvas.authority

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.canvas.notes.LocalNotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookNoteLoad
import com.letta.mobile.data.canvas.notes.NotebookNoteSave
import com.letta.mobile.data.canvas.notes.NotebookNoteSession
import com.letta.mobile.data.canvas.notes.NotebookNoteSnapshot
import com.letta.mobile.data.canvas.notes.notebookStorageId
import com.letta.mobile.data.canvas.references.NotebookReferenceAccess
import com.letta.mobile.data.canvas.references.NotebookReferenceLoad
import com.letta.mobile.data.canvas.references.NotebookReferenceResolver
import com.letta.mobile.data.canvas.references.NotebookReferenceSession
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceOpen
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceOpener
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceSessions
import com.letta.mobile.data.canvas.workspace.SharedNote
import com.letta.mobile.data.canvas.workspace.SharedNoteListing
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import kotlinx.coroutines.CancellationException

/**
 * Host-side access bound to a principal obtained from authenticated transport state. Never construct
 * it with an agent ID or a value from a request. The caller owns the notebook and grant stores.
 */
class HostNotebookAccess(
    private val notebooks: NotebookLocalStore,
    private val grants: NotebookGrantStore,
    private val principal: String,
) {
    init { notebooks.requireGrantIsolation() }

    /** Editor operations recheck edit authority under the grant transaction. */
    val backend: NotebookNoteBackend = AuthorizedBackend(LocalNotebookNoteBackend(notebooks), grants, principal)

    /** Rechecks both reference capabilities before consulting the repository. */
    val resolver = NotebookReferenceResolver(
        authorize = { id ->
            recover(NotebookReferenceAccess.UNAVAILABLE) {
                grants.withGrant(principal, id) { grant ->
                    if (mayResolveNotebookReference(grant)) NotebookReferenceAccess.ALLOWED else NotebookReferenceAccess.DENIED
                }
            }
        },
        readAuthorized = { id ->
            recover(NotebookReferenceLoad.Unavailable) {
                grants.withGrant(principal, id) { grant ->
                    if (!mayResolveNotebookReference(grant)) NotebookReferenceLoad.Denied
                    else notebooks.read(notebookStorageId(id))?.let(NotebookReferenceLoad::Found) ?: NotebookReferenceLoad.Missing
                }
            }
        },
    )

    /** A new edit decision is made for every requested open. */
    val opener = DocumentWorkspaceOpener { id ->
        recover(DocumentWorkspaceOpen.Unavailable) {
            grants.withGrant(principal, id) { grant ->
                if (!mayEditNotebookContent(grant)) DocumentWorkspaceOpen.Denied
                else DocumentWorkspaceOpen.Opened(
                    DocumentWorkspaceSessions(NotebookNoteSession(id, backend), NotebookReferenceSession(id, resolver)),
                )
            }
        }
    }

    /**
     * The notes this principal may open now: each listed grant is rechecked under its own
     * transaction for read and edit, and a granted note missing from the store is left out.
     */
    suspend fun sharedNotes(): SharedNoteListing = recover(SharedNoteListing.Unavailable("Notebook authority unavailable")) {
        val notes = grants.documentsOf(principal).take(SharedNotesWire.MAX_LISTED).mapNotNull { id ->
            grants.withGrant(principal, id) { grant ->
                if (!mayEditNotebookContent(grant)) null else notebooks.read(notebookStorageId(id))?.let { SharedNote(id, it.title) }
            }
        }
        SharedNoteListing.Listed(notes)
    }
}

private class AuthorizedBackend(
    private val delegate: NotebookNoteBackend,
    private val grants: NotebookGrantStore,
    private val principal: String,
) : NotebookNoteBackend {
    override suspend fun load(id: NotebookDocumentId): NotebookNoteLoad =
        recover(NotebookNoteLoad.Unavailable("Notebook authority unavailable")) {
            grants.withGrant(principal, id) { grant ->
                if (mayEditNotebookContent(grant)) delegate.load(id) else NotebookNoteLoad.Denied
            }
        }

    override suspend fun save(id: NotebookDocumentId, expected: String, replacement: String): NotebookNoteSave =
        edit(id) { delegate.save(id, expected, replacement) }

    override suspend fun flush(applied: NotebookNoteSnapshot): NotebookNoteSave =
        edit(applied.id) { delegate.flush(applied) }

    private suspend fun edit(id: NotebookDocumentId, action: suspend () -> NotebookNoteSave): NotebookNoteSave =
        recover(NotebookNoteSave.Unavailable("Notebook authority unavailable")) {
            grants.withGrant(principal, id) { grant -> if (mayEditNotebookContent(grant)) action() else NotebookNoteSave.Denied }
        }
}

private suspend fun <T> recover(failure: T, action: suspend () -> T): T = try {
    action()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (expectedFailure: Exception) {
    Telemetry.event(
        "NotebookAuthority", "operation.unavailable", "reason" attr (expectedFailure.message ?: "unknown"),
        level = Telemetry.Level.WARN,
    )
    failure
}

