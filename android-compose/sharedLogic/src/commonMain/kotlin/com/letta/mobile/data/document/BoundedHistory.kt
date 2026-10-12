package com.letta.mobile.data.document

/**
 * A document's live history: the ops applied since [base], weighed against a [HistoryBudget].
 * Not thread-safe; the owning [SharedDocument] holds it under its lock.
 */
class BoundedHistory<Op : DocumentOp, Snapshot>(
    base: Snapshot,
    private val budget: HistoryBudget,
    private val sizeOf: (Op) -> Long,
) {
    /** The snapshot the live history starts from: the open state, or the head of the last archive. */
    var base: Snapshot = base
        private set

    private val held = mutableListOf<Op>()
    private var bytes = 0L

    /** The highest pressure already reported, so each level is reported once. */
    private var reported = HistoryPressure.WITHIN

    val ops: List<Op> get() = held.toList()

    val pressure: HistoryPressure get() = budget.assess(bytes, held.size)

    fun record(ops: List<Op>) {
        held += ops
        bytes += ops.sumOf { sizeOf(it) }
    }

    /** Whether [level] is newly reached; WITHIN resets, so a later climb is reported again. */
    fun escalate(level: HistoryPressure): Boolean {
        val newlyReached = level > reported
        reported = level
        return newlyReached
    }

    /** The stretch from [base] to [head], for the archive. */
    fun stretchTo(head: Snapshot, nowEpochMs: Long): ArchivedHistory<Op, Snapshot> =
        ArchivedHistory(base, held.toList(), head, nowEpochMs)

    /** After an archive: the live history starts again from [head]. */
    fun continueFrom(head: Snapshot) {
        base = head
        held.clear()
        bytes = 0L
        reported = HistoryPressure.WITHIN
    }

    fun describe(): String = "${held.size} ops / $bytes bytes"
}
