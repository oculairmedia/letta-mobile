package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookPeerGrant
import com.letta.mobile.data.canvas.authority.FileNotebookGrantStore
import com.letta.mobile.data.canvas.authority.NotebookGrantStore
import com.letta.mobile.data.canvas.authority.mayEditNotebookContent
import com.letta.mobile.data.canvas.mayReadNotebook
import com.letta.mobile.data.canvas.mount.CanvasMountPrincipal
import com.letta.mobile.data.model.CanvasId
import java.nio.file.Path

/**
 * Host-owned canvas grants, keyed by the verified principal and the semantic [CanvasId] (owner decision O2).
 *
 * Notebook grants are keyed by the exact notebook document id, and compaction moves a notebook to a new id
 * without carrying its grants (the old id is retired; nothing provisions the successor), so a canvas resolved to
 * its current notebook generation would lose every grant at the next compaction. These grants name the canvas
 * itself and therefore survive generation changes.
 *
 * Storage reuses the strict, locked [FileNotebookGrantStore] format on its OWN file: each entry's `documentId`
 * holds the canvas id. That file must never be the notebook grants file. `mayRead` lets the principal learn that a
 * record holds the canvas; `mayRead` together with `mayEdit` also lets it store the canvas in a record.
 */
class CanvasMountGrants internal constructor(private val store: NotebookGrantStore) {
    /** Reads grants from the dedicated canvas grants file [grantsFile]; provisioning is a host administrator task. */
    constructor(grantsFile: Path) : this(FileNotebookGrantStore(grantsFile))

    /** The principal's grant for [canvas]; storage failures throw and the caller denies. */
    internal suspend fun of(principal: CanvasMountPrincipal, canvas: CanvasId): CanvasMountGrant =
        store.withGrant(principal.endpoint, NotebookDocumentId(canvas.value)) { CanvasMountGrant.of(it) }
}

/** What one canvas grant allows. */
internal enum class CanvasMountGrant {
    /** No grant, or one that allows nothing. */
    NONE,

    /** The principal may learn that a record holds the canvas. */
    SEE,

    /** The principal may also store the canvas in a record. */
    MOUNT,
    ;

    val maySee: Boolean get() = this != NONE

    val mayMount: Boolean get() = this == MOUNT

    companion object {
        fun of(grant: NotebookPeerGrant?): CanvasMountGrant = when {
            mayEditNotebookContent(grant) -> MOUNT
            mayReadNotebook(grant) -> SEE
            else -> NONE
        }
    }
}
