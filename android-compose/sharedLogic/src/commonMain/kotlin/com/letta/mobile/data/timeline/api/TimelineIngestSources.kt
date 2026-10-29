package com.letta.mobile.data.timeline.api

/** Provenance values an ingest's `source` may carry that a writer acts on, not just logs. */
object TimelineIngestSources {
    /**
     * A frame of a turn the send coordinator has already finished: its tail arrived behind the
     * terminal. A canonical writer folds it into that turn and never opens a new turn for it.
     */
    const val RETIRED_TURN_TAIL = "coordinator.retiredTurnTail"

    /** The send coordinator's source for a delta, naming a finished turn's tail as such. */
    fun coordinator(retiredTail: Boolean): String = if (retiredTail) RETIRED_TURN_TAIL else "coordinator"
}
