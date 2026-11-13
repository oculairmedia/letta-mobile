package com.letta.mobile.data.canvas.workspace

import com.letta.mobile.data.canvas.NotebookDocumentId
import kotlin.jvm.JvmInline

/**
 * One note a host shares with this device, as the host listed it.
 * @property id The exact notebook identity; opening it goes through [SharedNotebooks.opener].
 * @property title The note's title when it was listed; a listing grants nothing.
 */
data class SharedNote(val id: NotebookDocumentId, val title: String)

/**
 * Where a host's listing stopped, as that host wrote it. It is opaque here: only the host that
 * issued it reads it, and it is handed back unchanged to ask for the next page.
 * @property value The host's own encoding; never parsed or built on this side.
 */
@JvmInline
value class SharedNotesCursor(val value: String)

/** What a host answered when asked which notes it shares with this device. */
sealed interface SharedNoteListing {
    /**
     * One page of the notes this device may open now; empty when the host shares none.
     * @property notes The listed notes, in the host's order.
     * @property next Where to ask for the next page, when the host stopped before its last grant for
     *   this device (the page is full, or it reached its per-request bound); null when nothing is left.
     */
    data class Listed(val notes: List<SharedNote>, val next: SharedNotesCursor? = null) : SharedNoteListing

    /** The host refused this device (it is not authenticated or may not ask). */
    data object Denied : SharedNoteListing

    /**
     * The host could not be reached or did not answer usefully; never read as "none shared".
     * @property reason A short diagnostic, for logs rather than the person.
     */
    data class Unavailable(val reason: String) : SharedNoteListing {
        companion object {
            /** The reason a host gives for a cursor it cannot read; the caller starts again from the first page. */
            const val INVALID_CURSOR = "The listing position is not valid; list again from the start"
        }
    }
}

/** Asks the host which notes it shares with this device. Every call is a fresh question. */
fun interface SharedNoteDirectory {
    /**
     * The host's current answer for one page; transport failures are [SharedNoteListing.Unavailable].
     * @param from Null for the first page, else the [SharedNoteListing.Listed.next] of the page before.
     */
    suspend fun list(from: SharedNotesCursor?): SharedNoteListing
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
