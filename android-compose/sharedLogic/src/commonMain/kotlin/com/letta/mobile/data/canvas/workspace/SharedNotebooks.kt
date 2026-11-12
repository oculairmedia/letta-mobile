package com.letta.mobile.data.canvas.workspace

import com.letta.mobile.data.canvas.NotebookDocumentId

/**
 * One note a host shares with this device, as the host listed it.
 * @property id The exact notebook identity; opening it goes through [SharedNotebooks.opener].
 * @property title The note's title when it was listed; a listing grants nothing.
 */
data class SharedNote(val id: NotebookDocumentId, val title: String)

/** What a host answered when asked which notes it shares with this device. */
sealed interface SharedNoteListing {
    /**
     * The notes this device may open now; empty when the host shares none.
     * @property notes The listed notes, in the host's order.
     * @property truncated The host stopped before checking every grant it holds for this device
     *   (the listing is full, or it reached its per-request bound), so it may share more than listed.
     */
    data class Listed(val notes: List<SharedNote>, val truncated: Boolean = false) : SharedNoteListing

    /** The host refused this device (it is not authenticated or may not ask). */
    data object Denied : SharedNoteListing

    /**
     * The host could not be reached or did not answer usefully; never read as "none shared".
     * @property reason A short diagnostic, for logs rather than the person.
     */
    data class Unavailable(val reason: String) : SharedNoteListing
}

/** Asks the host which notes it shares with this device. Every call is a fresh question. */
fun interface SharedNoteDirectory {
    /** The host's current answer; transport failures are [SharedNoteListing.Unavailable]. */
    suspend fun list(): SharedNoteListing
}

/**
 * Notes a host shares with this device: which ones ([directory]) and the trusted opener that
 * rechecks the grant for every open ([opener]). The shell's document view is bound to this seam
 * and never to a transport.
 */
interface SharedNotebooks {
    /** Which notes are shared now. */
    val directory: SharedNoteDirectory

    /** Opens one shared note into a [DocumentWorkspaceController], rechecking the grant. */
    val opener: DocumentWorkspaceOpener
}
