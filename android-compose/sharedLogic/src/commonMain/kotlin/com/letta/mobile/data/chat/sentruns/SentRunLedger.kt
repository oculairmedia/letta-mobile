package com.letta.mobile.data.chat.sentruns

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The published [SentMessageRuns] of one chat send pipeline. Safe to call from any thread: every
 * change is one atomic update of the published value.
 *
 * Bounded: past [MAX_SENT_MESSAGES] entries the oldest entries whose run has ended are dropped. An
 * entry whose run is still in flight is never dropped, so a published run stays published until
 * it ends.
 */
internal class SentRunLedger {
    private val runs = MutableStateFlow(SentMessageRuns())

    /** The published runs. */
    val state: StateFlow<SentMessageRuns> = runs.asStateFlow()

    /** [id] started the run known by [ids]; adds them to its entry. */
    fun started(id: SentMessageId, ids: Set<SentRunId>) {
        if (ids.isEmpty()) return
        runs.update { it.withStarted(id, ids) }
    }

    /** The run [id] started has ended, as a failure when [failed]. */
    fun ended(id: SentMessageId, failed: Boolean) {
        runs.update { it.withEnded(id, failed) }
    }
}

/** Past this many entries, ended ones are dropped oldest first. */
internal const val MAX_SENT_MESSAGES = 16

/**
 * Adds [ids] to [id]'s entry. A new entry, or one whose run is still in flight, makes them active;
 * an id added to an entry whose run already ended stays inactive.
 */
internal fun SentMessageRuns.withStarted(id: SentMessageId, ids: Set<SentRunId>): SentMessageRuns {
    val known = started[id]
    val stillRunning = known == null || known.any { it in active }
    val next = copy(
        started = started + (id to known.orEmpty() + ids),
        active = if (stillRunning) active + ids else active,
    )
    return next.bounded()
}

/** [id]'s run is no longer in flight; its ids are failed too when [failed]. */
internal fun SentMessageRuns.withEnded(id: SentMessageId, failed: Boolean): SentMessageRuns {
    val ids = started[id] ?: return this
    return copy(active = active - ids, failed = if (failed) this.failed + ids else this.failed)
}

private fun SentMessageRuns.bounded(): SentMessageRuns {
    var current = this
    while (current.started.size > MAX_SENT_MESSAGES) {
        val oldestEnded = current.oldestEndedEntry() ?: break
        current = current.without(oldestEnded)
    }
    return current
}

private fun SentMessageRuns.oldestEndedEntry(): SentMessageId? =
    started.entries.firstOrNull { entry -> entry.value.none { it in active } }?.key

private fun SentMessageRuns.without(id: SentMessageId): SentMessageRuns {
    val ids = started[id].orEmpty()
    return copy(started = started - id, failed = failed - ids)
}
