package com.letta.mobile.data.canvas.workspace

import com.letta.mobile.data.canvas.NotebookDocumentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the shell's document view shows: the host's latest listing and, once one is open, the
 * workspace holding that note.
 * @property listing The latest answer, with every page loaded since the refresh that started it
 *   appended in order; null before the first answer arrives.
 * @property loading A refresh (a first-page request) is in flight.
 * @property loadingMore The next page is being asked for.
 * @property moreFailed The latest request for the next page was unavailable; what is shown stays and it may be asked again.
 * @property opening The note being opened, while the opener decides.
 * @property workspace The workspace holding the open note, or null while the list shows.
 * @property openProblem Why the latest open failed, shown beside the list.
 */
data class SharedNoteBrowserState(
    val listing: SharedNoteListing? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
    val opening: NotebookDocumentId? = null,
    val workspace: DocumentWorkspaceController? = null,
    val openProblem: DocumentWorkspaceProblem? = null,
)

/**
 * The shared notes a host offers this device, and the one open in a [DocumentWorkspaceController].
 * Opening goes through [SharedNotebooks.opener], so the host rechecks the grant every time; the
 * listing itself grants nothing. Call on one owner dispatcher; the caller owns the coroutines.
 */
class SharedNoteBrowser(private val notebooks: SharedNotebooks) {
    private val mutableState = MutableStateFlow(SharedNoteBrowserState())

    // Owner-dispatcher state ordering overlapping refreshes: the number of the latest one asked,
    // of the latest one whose answer is shown, and how many are still waiting for the host.
    private var asked = 0L
    private var shown = 0L
    private var waiting = 0

    // The page-request generation: a refresh that publishes its listing advances it, so a page asked
    // for an older listing neither appends nor clears the flag of a page asked for the newer one.
    private var pageGeneration = 0L

    /** The listing and the open note. */
    val state: StateFlow<SharedNoteBrowserState> = mutableState.asStateFlow()

    /**
     * Asks the host again from the first page; a failure keeps no stale list, it says the host is
     * unavailable. Refreshes may overlap (the mount, Refresh, Try again, leaving a note): the latest
     * one asked wins, so an older answer arriving last never replaces a newer one, and
     * [SharedNoteBrowserState.loading] stays true until every refresh has its answer or was cancelled.
     */
    suspend fun refresh() {
        val ticket = ++asked
        waiting++
        mutableState.update { it.copy(loading = true) }
        var answer: SharedNoteListing? = null
        try {
            answer = ask(null)
        } finally {
            waiting--
            val fresh = answer?.takeIf { ticket > shown }
            if (fresh != null) markPublished(ticket)
            mutableState.update { showRefresh(it, fresh) }
        }
    }

    private fun markPublished(ticket: Long) {
        shown = ticket
        pageGeneration++
    }

    /** A published refresh starts the listing again, so no page is being asked for it yet. */
    private fun showRefresh(current: SharedNoteBrowserState, fresh: SharedNoteListing?): SharedNoteBrowserState {
        if (fresh == null) return current.copy(loading = waiting > 0)
        return current.copy(listing = fresh, loading = waiting > 0, loadingMore = false, moreFailed = false)
    }

    /**
     * Asks for the page after the one shown and appends it; true when it was appended. Nothing is
     * asked (false) while a page is already being asked for or when the host said nothing is left.
     * The page extends the listing shown when it was asked: once a refresh is asked or answered
     * meanwhile, its answer is dropped, since the refresh starts again from the first page; once a
     * refresh has published its listing, this request no longer touches the state at all, so a page
     * asked for the new listing is not held back by it. A failed page keeps what is shown and sets
     * [SharedNoteBrowserState.moreFailed], so the person can ask again; a cursor the host can no longer
     * read lists again from the first page instead. A refused page replaces the listing with
     * [SharedNoteListing.Denied]: the earlier notes are not shown once the host has refused this device.
     */
    suspend fun loadMore(): Boolean {
        val from = nextPageOf(state.value) ?: return false
        val base = asked to shown
        val generation = pageGeneration
        mutableState.update { it.copy(loadingMore = true, moreFailed = false) }
        var page: SharedNoteListing? = null
        try {
            page = ask(from)
        } finally {
            page = page?.takeIf { asked to shown == base }
            val answer = page
            if (generation == pageGeneration) mutableState.update { showPage(it, answer) }
        }
        if (page == CURSOR_REFUSED) refresh()
        return page is SharedNoteListing.Listed
    }

    /** Where the next page starts, or null when nothing is left or a page is already being asked for. */
    private fun nextPageOf(current: SharedNoteBrowserState): SharedNotesCursor? {
        if (current.loadingMore) return null
        return (current.listing as? SharedNoteListing.Listed)?.next
    }

    /**
     * A refused page is the host's current answer for this device, so the notes listed before it are
     * no longer shown. An unavailable page keeps them and may be asked again, except a cursor the host
     * cannot read: asking with it again can never succeed, so [loadMore] lists again from the first page.
     */
    private fun showPage(current: SharedNoteBrowserState, page: SharedNoteListing?): SharedNoteBrowserState {
        return when (page) {
            null -> current.copy(loadingMore = false)
            SharedNoteListing.Denied -> current.copy(listing = SharedNoteListing.Denied, loadingMore = false)
            is SharedNoteListing.Unavailable -> current.copy(loadingMore = false, moreFailed = page != CURSOR_REFUSED)
            is SharedNoteListing.Listed -> current.copy(listing = appended(current.listing, page), loadingMore = false)
        }
    }

    private fun appended(shown: SharedNoteListing?, page: SharedNoteListing.Listed): SharedNoteListing.Listed {
        val notes = ((shown as? SharedNoteListing.Listed)?.notes.orEmpty() + page.notes).distinctBy { it.id }
        return SharedNoteListing.Listed(notes, page.next)
    }

    private suspend fun ask(from: SharedNotesCursor?): SharedNoteListing = try {
        notebooks.directory.list(from)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expectedFailure: Exception) {
        SharedNoteListing.Unavailable(expectedFailure.message ?: "The host could not be reached")
    }

    /**
     * Opens [id] in a fresh workspace. A refused or unreachable open leaves the list showing with
     * [SharedNoteBrowserState.openProblem] set; nothing is mounted.
     */
    suspend fun open(id: NotebookDocumentId): Boolean {
        if (state.value.opening != null) return false
        val controller = DocumentWorkspaceController(notebooks.opener)
        mutableState.update { it.copy(opening = id, openProblem = null) }
        val opened = try {
            controller.open(id)
        } finally {
            mutableState.update { it.copy(opening = null) }
        }
        val problem = if (opened) null else controller.state.value.problem ?: DocumentWorkspaceProblem.UNAVAILABLE
        mutableState.update { it.copy(workspace = controller.takeIf { opened }, openProblem = problem) }
        return opened
    }

    /**
     * Leaves the open note through the workspace's durability barrier and lists again. A note whose
     * grant the host withdrew is left even when its draft cannot be saved: nothing could save it.
     */
    suspend fun closeNote(): Boolean {
        val controller = state.value.workspace ?: return true
        if (!controller.close() && !controller.withdrawn()) return false
        controller.state.value.current?.references?.cancel()
        mutableState.update { it.copy(workspace = null) }
        refresh()
        return true
    }

    /** The workspace's own Close emptied it: show the list again. */
    suspend fun workspaceEmptied() {
        val controller = state.value.workspace ?: return
        if (controller.state.value.current != null) return
        mutableState.update { it.copy(workspace = null) }
        refresh()
    }
}

private val CURSOR_REFUSED = SharedNoteListing.Unavailable(SharedNoteListing.Unavailable.INVALID_CURSOR)

/** The host refused the open note's current grant (revoked while it was open). */
fun DocumentWorkspaceController.withdrawn(): Boolean = state.value.current?.note?.state?.value?.denied == true
