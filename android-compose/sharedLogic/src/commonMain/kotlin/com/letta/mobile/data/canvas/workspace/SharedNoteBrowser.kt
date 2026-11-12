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
 * @property listing The latest answer; null before the first answer arrives.
 * @property loading A listing request is in flight.
 * @property opening The note being opened, while the opener decides.
 * @property workspace The workspace holding the open note, or null while the list shows.
 * @property openProblem Why the latest open failed, shown beside the list.
 */
data class SharedNoteBrowserState(
    val listing: SharedNoteListing? = null,
    val loading: Boolean = false,
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

    /** The listing and the open note. */
    val state: StateFlow<SharedNoteBrowserState> = mutableState.asStateFlow()

    /**
     * Asks the host again; a failure keeps no stale list, it says the host is unavailable. Refreshes
     * may overlap (the mount, Refresh, Try again, leaving a note): the latest one asked wins, so an
     * older answer arriving last never replaces a newer one, and [SharedNoteBrowserState.loading]
     * stays true until every refresh has its answer or was cancelled.
     */
    suspend fun refresh() {
        val ticket = ++asked
        waiting++
        mutableState.update { it.copy(loading = true) }
        var answer: SharedNoteListing? = null
        try {
            answer = ask()
        } finally {
            waiting--
            val fresh = answer?.takeIf { ticket > shown }
            if (fresh != null) shown = ticket
            mutableState.update { it.copy(listing = fresh ?: it.listing, loading = waiting > 0) }
        }
    }

    private suspend fun ask(): SharedNoteListing = try {
        notebooks.directory.list()
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

/** The host refused the open note's current grant (revoked while it was open). */
fun DocumentWorkspaceController.withdrawn(): Boolean = state.value.current?.note?.state?.value?.denied == true
