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
import com.letta.mobile.data.canvas.workspace.SharedNotesCursor
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
    val resolver = NotebookReferenceResolver(authorize = ::referenceAccess, readAuthorized = ::referenceLoad)

    /** A new edit decision is made for every requested open. */
    val opener = DocumentWorkspaceOpener(::open)

    private suspend fun referenceAccess(id: NotebookDocumentId): NotebookReferenceAccess {
        return recover(NotebookReferenceAccess.UNAVAILABLE) {
            grants.withGrant(principal, id) { grant ->
                if (mayResolveNotebookReference(grant)) NotebookReferenceAccess.ALLOWED else NotebookReferenceAccess.DENIED
            }
        }
    }

    private suspend fun referenceLoad(id: NotebookDocumentId): NotebookReferenceLoad {
        return recover(NotebookReferenceLoad.Unavailable) {
            grants.withGrant(principal, id) { grant ->
                if (!mayResolveNotebookReference(grant)) NotebookReferenceLoad.Denied
                else notebooks.read(notebookStorageId(id))?.let(NotebookReferenceLoad::Found) ?: NotebookReferenceLoad.Missing
            }
        }
    }

    private suspend fun open(id: NotebookDocumentId): DocumentWorkspaceOpen {
        return recover(DocumentWorkspaceOpen.Unavailable) {
            grants.withGrant(principal, id) { grant ->
                if (!mayEditNotebookContent(grant)) DocumentWorkspaceOpen.Denied
                else DocumentWorkspaceOpen.Opened(
                    DocumentWorkspaceSessions(NotebookNoteSession(id, backend), NotebookReferenceSession(id, resolver)),
                )
            }
        }
    }

    /**
     * One page of the notes this principal may open now, starting after [from] (null for the first
     * page). Each grant is rechecked under its own transaction for read and edit, and a granted note
     * missing from the store is left out, as is a grant whose id is longer than a cursor can name
     * ([SharedNotesCursors.MAX_ID_BYTES]; the host's own note ids are far shorter). A page holds up
     * to [SharedNotesWire.MAX_LISTED] notes that pass that check and rechecks at most [SharedNotesWire.MAX_CHECKED] grants; one that stopped
     * before the principal's last grant carries [SharedNoteListing.Listed.next], naming the last grant
     * it checked, so following cursors reaches every grant however many cannot open the editor.
     *
     * Consistency: every page is a fresh decision over a fresh, id-ordered read of the principal's
     * grants, and starts strictly after the cursor's id, so pages never repeat a note and a walk
     * always ends. Nothing is held between pages: a note shared or unshared during a walk may be
     * missed (it sorts before the cursor) or appear on a later page, but a page never lists a note
     * its own check did not just allow. A cursor that is malformed, too long or issued to another
     * principal is answered unavailable with [SharedNotesWire.INVALID_CURSOR] before any recheck.
     */
    suspend fun sharedNotes(from: SharedNotesCursor? = null): SharedNoteListing {
        return recover(SharedNoteListing.Unavailable("Notebook authority unavailable")) { listFrom(from) }
    }

    private suspend fun listFrom(from: SharedNotesCursor?): SharedNoteListing {
        if (from == null) return listAfter(null)
        val after = SharedNotesCursors.read(principal, from)
        if (after == null) return SharedNoteListing.Unavailable(SharedNotesWire.INVALID_CURSOR)
        return listAfter(after)
    }

    private suspend fun listAfter(after: NotebookDocumentId?): SharedNoteListing {
        val candidates = candidatesAfter(after)
        val notes = mutableListOf<SharedNote>()
        var checked = 0
        for (id in candidates.take(SharedNotesWire.MAX_CHECKED)) {
            if (notes.size == SharedNotesWire.MAX_LISTED) break
            checked++
            openable(id)?.let(notes::add)
        }
        return SharedNoteListing.Listed(notes, nextCursor(candidates, checked))
    }

    /** The principal's grants after [after], in id order (sorted here too, so a store's own order never moves a cursor). */
    private suspend fun candidatesAfter(after: NotebookDocumentId?): List<NotebookDocumentId> {
        val ordered = grants.documentsOf(principal).filter(SharedNotesCursors::positionable).sortedBy { it.value }
        if (after == null) return ordered
        return ordered.filter { it.value > after.value }
    }

    /** Where the next page starts: after the last grant checked, unless every candidate was checked. */
    private fun nextCursor(candidates: List<NotebookDocumentId>, checked: Int): SharedNotesCursor? {
        if (checked == 0) return null
        if (checked == candidates.size) return null
        return SharedNotesCursors.issue(principal, candidates[checked - 1])
    }

    private suspend fun openable(id: NotebookDocumentId): SharedNote? {
        return grants.withGrant(principal, id) { grant ->
            if (!mayEditNotebookContent(grant)) null else notebooks.read(notebookStorageId(id))?.let { SharedNote(id, it.title) }
        }
    }
}

private class AuthorizedBackend(
    private val delegate: NotebookNoteBackend,
    private val grants: NotebookGrantStore,
    private val principal: String,
) : NotebookNoteBackend {
    override suspend fun load(id: NotebookDocumentId): NotebookNoteLoad {
        return recover(NotebookNoteLoad.Unavailable("Notebook authority unavailable")) {
            grants.withGrant(principal, id) { grant ->
                if (mayEditNotebookContent(grant)) delegate.load(id) else NotebookNoteLoad.Denied
            }
        }
    }

    override suspend fun save(id: NotebookDocumentId, expected: String, replacement: String): NotebookNoteSave =
        edit(id) { delegate.save(id, expected, replacement) }

    override suspend fun flush(applied: NotebookNoteSnapshot): NotebookNoteSave =
        edit(applied.id) { delegate.flush(applied) }

    private suspend fun edit(id: NotebookDocumentId, action: suspend () -> NotebookNoteSave): NotebookNoteSave {
        return recover(NotebookNoteSave.Unavailable("Notebook authority unavailable")) {
            grants.withGrant(principal, id) { grant -> if (mayEditNotebookContent(grant)) action() else NotebookNoteSave.Denied }
        }
    }
}

private suspend fun <T> recover(failure: T, action: suspend () -> T): T {
    return try {
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
}

