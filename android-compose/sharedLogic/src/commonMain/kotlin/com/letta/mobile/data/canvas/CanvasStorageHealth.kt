package com.letta.mobile.data.canvas

import kotlinx.coroutines.flow.StateFlow

/**
 * Something went wrong storing a board, kept until the app restarts so the person sees it. A
 * storage failure must neither kill the process nor pass silently: the store logs it at ERROR and
 * publishes it here, and the board shows it.
 */
data class CanvasStorageFault(
    val kind: Kind,
    /** The notebook document's stable key, when the failure names one. */
    val documentId: String?,
    /** The board it holds, when known. */
    val canvasId: CanvasId?,
    /** The document's size on disk when the fault was seen, when known. */
    val documentBytes: Long?,
    val message: String,
    val atEpochMs: Long,
) {
    enum class Kind {
        /** A save did not reach disk: changes since [atEpochMs] are only in memory. */
        SAVE_FAILED,

        /** The document could not be read from disk, or its history could not be archived. */
        LOAD_FAILED,

        /** The document could not be opened and was set aside; its files are kept. */
        QUARANTINED,

        /** The document's history passed the warning share of its size budget. */
        NEAR_BUDGET,

        /**
         * The document's history was archived, and the board continues from its current state in
         * a new document; the old one is retired.
         */
        COMPACTED,

        /**
         * The document's history passed its size budget: it is moved to a new document the next
         * time the store opens, or it could not be, and stays as it is.
         */
        OVER_BUDGET,

        /**
         * Writes are refused: the store hit an error it cannot vouch for having recovered from
         * (out of memory on a repository thread), or the document's board layout is newer than
         * this build understands. Changes made since [atEpochMs] are not saved.
         */
        READ_ONLY,
    }

    /** Faults the person must act on or be told about; budget notes are only logged. */
    val isError: Boolean get() = when (kind) {
        Kind.SAVE_FAILED, Kind.LOAD_FAILED, Kind.QUARANTINED, Kind.OVER_BUDGET, Kind.READ_ONLY -> true
        Kind.NEAR_BUDGET, Kind.COMPACTED -> false
    }
}

/** A canvas store that can report storage faults. Android and desktop render [faults]. */
interface CanvasStorageHealth {
    val storageFaults: StateFlow<List<CanvasStorageFault>>
}

/** The faults that concern [canvasId]: its own, and ones no board could be named for. */
fun List<CanvasStorageFault>.affecting(canvasId: CanvasId): List<CanvasStorageFault> =
    filter { it.isError && (it.canvasId == canvasId || it.canvasId == null) }
