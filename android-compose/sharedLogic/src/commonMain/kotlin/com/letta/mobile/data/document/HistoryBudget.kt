package com.letta.mobile.data.document

import kotlinx.coroutines.flow.StateFlow

/**
 * How much op history one shared document may hold before it is archived and continued from its
 * current snapshot (the canvas-history policy: keep history, bound it, never silently discard).
 *
 * Either bound trips it. The canvas's notebook repository bounds bytes only
 * ([com.letta.mobile.data.canvas.NotebookHistoryBudget] wraps one of these); a [SharedDocument]
 * bounds both.
 */
data class HistoryBudget(
    val maxOps: Int = Int.MAX_VALUE,
    val maxBytes: Long = Long.MAX_VALUE,
    val warnFraction: Double = DEFAULT_WARN_FRACTION,
) {
    init {
        require(maxOps > 0 && maxBytes > 0 && warnFraction > 0.0 && warnFraction <= 1.0)
    }

    val warnBytes: Long get() = (maxBytes * warnFraction).toLong()

    val warnOps: Int get() = (maxOps * warnFraction).toInt()

    /** Where a history of [bytes] and [ops] stands against this budget. */
    fun assess(bytes: Long, ops: Int = 0): HistoryPressure = when {
        bytes > maxBytes || ops > maxOps -> HistoryPressure.OVER
        bytes >= warnBytes || ops >= warnOps -> HistoryPressure.NEAR
        else -> HistoryPressure.WITHIN
    }

    companion object {
        const val DEFAULT_WARN_FRACTION: Double = 0.75
    }
}

/** Where a document's history stands against its [HistoryBudget]; ordered. */
enum class HistoryPressure { WITHIN, NEAR, OVER }

/**
 * A storage problem a person must see or be told about. Storage failures are never fatal and never
 * silent: the store logs them through telemetry and publishes them as a fault state.
 */
interface StorageFault {
    /** Faults the person must act on; budget notes and completed archives are only logged. */
    val isError: Boolean
    val message: String
    val atEpochMs: Long
}

/** A store that reports its [StorageFault]s; the surfaces showing its documents render them. */
interface DocumentStorageHealth<F : StorageFault> {
    val storageFaults: StateFlow<List<F>>
}

/** A [SharedDocument]'s own storage faults. */
data class SharedDocumentFault(
    val kind: Kind,
    /** The document's id as text. */
    val documentKey: String,
    override val message: String,
    override val atEpochMs: Long,
) : StorageFault {
    enum class Kind {
        /** A snapshot or op did not reach storage: changes since [atEpochMs] are only in memory. */
        SAVE_FAILED,

        /** The stored snapshot or log could not be read; the document runs from what it holds. */
        LOAD_FAILED,

        /** The history passed the warning share of its budget. */
        NEAR_BUDGET,

        /** The history was archived, and the document continues from its current snapshot. */
        COMPACTED,

        /** The history passed its budget and could not be archived: all of it is kept, unbounded. */
        OVER_BUDGET,
    }

    override val isError: Boolean get() = when (kind) {
        Kind.SAVE_FAILED, Kind.LOAD_FAILED, Kind.OVER_BUDGET -> true
        Kind.NEAR_BUDGET, Kind.COMPACTED -> false
    }
}
