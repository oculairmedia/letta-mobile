package com.letta.mobile.ui.shell.workspace

import com.letta.mobile.data.canvas.workspace.SharedNote

/** Test tags of the shell's shared-notes view. */
object ShellDocumentTags {
    /** The view, listing or showing an open note. */
    const val MODULE = "shell-document-module"

    /** The list of shared notes. */
    const val LIST = "shell-document-list"

    /** The host shares no notes with this device. */
    const val EMPTY = "shell-document-empty"

    /** The first listing has not arrived yet. */
    const val LOADING = "shell-document-loading"

    /** The host could not be reached. */
    const val UNAVAILABLE = "shell-document-unavailable"

    /** The host refuses this device. */
    const val DENIED = "shell-document-denied"

    /** The latest open failed. */
    const val OPEN_PROBLEM = "shell-document-open-problem"

    /** The open note's grant was withdrawn by the host. */
    const val WITHDRAWN = "shell-document-withdrawn"

    /** Leaves the open note for the list. */
    const val BACK = "shell-document-back"

    /** Hides the view. */
    const val CLOSE = "shell-document-close"

    /** The row of one shared note. */
    fun row(note: SharedNote): String = "shell-document-row-${note.id.value}"
}
