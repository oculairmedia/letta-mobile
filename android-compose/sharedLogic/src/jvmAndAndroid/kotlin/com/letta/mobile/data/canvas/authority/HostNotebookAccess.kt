package com.letta.mobile.data.canvas.authority

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.canvas.NotebookPeerGrant
import com.letta.mobile.data.canvas.notes.LocalNotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookNoteLoad
import com.letta.mobile.data.canvas.notes.NotebookNoteSave
import com.letta.mobile.data.canvas.notes.NotebookNoteSession
import com.letta.mobile.data.canvas.notes.NotebookNoteSnapshot
import com.letta.mobile.data.canvas.notes.notebookStorageId
import com.letta.mobile.data.canvas.notes.notebookStorageIdOrNull
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
import java.util.concurrent.ExecutionException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import org.automerge.repo.DocumentId

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
     * page). Grants are rechecked for read and edit in id order, [SHARED_NOTES_CHECKS_PER_TRANSACTION]
     * at a time under one grant transaction ([NotebookGrantStore.withGrants]), and each note's title is
     * read inside the transaction that allowed it, by a title-only read that never loads the rest of the
     * note and never resolves a retired id to a successor. A granted note that is missing or retired is
     * left out, as is a grant whose id cannot name a local notebook, or is longer than a cursor can name
     * ([SharedNotesCursors.MAX_ID_BYTES]; the host's own note ids are far shorter). A note whose title
     * read fails or times out is left out too and logged with its id and the error class only, so one
     * unreadable note never stops the walk; a grant store that cannot be read still answers unavailable.
     *
     * A page holds up to [SharedNotesWire.MAX_LISTED] notes whose entries together stay within
     * [SharedNotesWire.MAX_PAGE_BYTES], rechecks at most [SharedNotesWire.MAX_CHECKED] grants, and stops
     * taking new notes once [SHARED_NOTES_PAGE_SECONDS] have passed. One that stopped before the
     * principal's last grant carries [SharedNoteListing.Listed.next], naming the last grant it checked
     * (a note that did not fit is not counted as checked, so the next page starts with it), so following
     * cursors reaches every grant however many cannot open the editor.
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
        val page = SharedNotesPage()
        for (chunk in candidates.take(SharedNotesWire.MAX_CHECKED).chunked(SHARED_NOTES_CHECKS_PER_TRANSACTION)) {
            if (page.full()) break
            grants.withGrants(principal, chunk) { held -> checkChunk(chunk, held, page) }
        }
        return SharedNoteListing.Listed(page.notes, nextCursor(candidates, page.checked))
    }

    /**
     * Checks [chunk] in order against the grants read for it in this transaction, until the page is full.
     * A note that passes but no longer fits stops the page uncounted, so the next page rechecks it first.
     */
    private fun checkChunk(chunk: List<NotebookDocumentId>, held: Map<NotebookDocumentId, NotebookPeerGrant>, page: SharedNotesPage) {
        for (id in chunk) {
            if (page.full()) return
            val note = openable(id, held[id])
            if (note != null && !page.add(note)) return
            page.checked++
        }
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

    /**
     * The note as listed, when [grant] (read in the current transaction) lets this principal open it. A
     * grant whose id cannot name a local notebook opens nothing and is left out, still counted as
     * checked so the cursor moves past it, as is a note whose title cannot be read.
     */
    private fun openable(id: NotebookDocumentId, grant: NotebookPeerGrant?): SharedNote? {
        if (!mayEditNotebookContent(grant)) return null
        val storage = notebookStorageIdOrNull(id) ?: return null
        return titleOrSkip(id, storage)?.let { SharedNote(id, SharedNotesWire.boundedTitle(it)) }
    }

    /** The note's title; null when it is missing or retired, or (logged, without the title) when the read fails. */
    private fun titleOrSkip(id: NotebookDocumentId, storage: DocumentId): String? {
        // Any failure of this one read (timeout, failed load, malformed title) skips the note; cancellation still propagates.
        return runCatching { notebooks.titleOf(storage) }.getOrElse { unreadable ->
            if (unreadable is CancellationException || unreadable !is Exception) throw unreadable
            reportUnreadable(id, unreadable)
            null
        }
    }
}

/** Logs a note left out of a listing: its id and the error class only, never its title or the principal. */
private fun reportUnreadable(id: NotebookDocumentId, failure: Exception) {
    val cause = (failure as? ExecutionException)?.cause ?: failure
    Telemetry.event(
        "NotebookAuthority", "shared_list.note_unreadable",
        "documentId" attr id.value, "error" attr (cause::class.simpleName ?: "unknown"),
        level = Telemetry.Level.WARN,
    )
}

/**
 * How many grants one listing transaction rechecks. A page runs at most MAX_CHECKED / this
 * transactions after its listing read, and the grant lock is held for one store read plus at most
 * this many title-only reads, so provisioning and revocation wait for one chunk, never for a whole
 * page. The grant file itself is parsed once and reused while its bytes are unchanged (see
 * [FileNotebookGrantStore]).
 */
internal const val SHARED_NOTES_CHECKS_PER_TRANSACTION = 50

/**
 * After this long a page takes no further notes (once it has checked at least one grant) and hands back
 * a cursor, so a run of slow notes bounds how long one request holds the grant lock.
 */
internal const val SHARED_NOTES_PAGE_SECONDS = 5L

/**
 * One page being built: the notes that passed their recheck and fit, how many grants were checked, and
 * whether the page stopped because the next note would pass the byte budget or its time ran out.
 */
private class SharedNotesPage {
    val notes = mutableListOf<SharedNote>()
    var checked = 0
    private var bytes = 0
    private var overBudget = false
    private val deadline = TimeSource.Monotonic.markNow() + SHARED_NOTES_PAGE_SECONDS.seconds

    fun full(): Boolean {
        if (notes.size == SharedNotesWire.MAX_LISTED || overBudget) return true
        return checked > 0 && deadline.hasPassedNow()
    }

    /** Adds [note] when its entry fits the byte budget (the first always does); false, and the page is full, when not. */
    fun add(note: SharedNote): Boolean {
        val size = SharedNotesWire.entryBytes(note)
        if (notes.isNotEmpty() && bytes + size > SharedNotesWire.MAX_PAGE_BYTES) {
            overBudget = true
            return false
        }
        notes += note
        bytes += size
        return true
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

