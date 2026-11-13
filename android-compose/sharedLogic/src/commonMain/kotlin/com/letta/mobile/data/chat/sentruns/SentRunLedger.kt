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
 * it ends. The last [MAX_SETTLED_RUNS] settled run ids are remembered with how they ended, so an
 * echo that arrives only after its turn settled can still be paired, as an ended run ([lateEcho]).
 */
internal class SentRunLedger {
    private val runs = MutableStateFlow(SentMessageRuns())
    private val settledRuns = MutableStateFlow<Map<SentRunId, Boolean>>(emptyMap())
    private val sentIds = MutableStateFlow<List<SentMessageId>>(emptyList())

    /** The published runs. */
    val state: StateFlow<SentMessageRuns> = runs.asStateFlow()

    /** [id] started the run known by [ids]; adds them to its entry. */
    fun started(id: SentMessageId, ids: Set<SentRunId>) {
        if (ids.isEmpty()) return
        runs.update { it.withStarted(id, ids) }
    }

    /** This pipeline accepted a message under [id]: a row the server stamps with it as its turn is that message's run. */
    fun sent(id: SentMessageId) {
        sentIds.update { current -> (current - id + id).takeLast(MAX_SENT_MESSAGES) }
    }

    /**
     * A live row of turn [turn] carried [run]. The host stamps every row of a turn with the client
     * message id of the input that started it, so when [turn] is a message this pipeline sent,
     * [run] is (an id of) that message's run. A row of any other turn is not ours and changes nothing.
     */
    fun row(turn: String?, run: String?) {
        val id = turn?.takeIf { it.isNotBlank() }?.let(::SentMessageId) ?: return
        val runId = run?.takeIf { it.isNotBlank() }?.let(::SentRunId) ?: return
        if (id in sentIds.value) started(id, setOf(runId))
    }

    /**
     * The turn that ran [previous] now runs [next] (a durable id promoting a provisional one, in the
     * same turn): every sent message whose run is [previous] gains [next].
     */
    fun promoted(previous: String?, next: String?) {
        val from = previous?.takeIf { it.isNotBlank() }?.let(::SentRunId) ?: return
        val to = next?.takeIf { it.isNotBlank() && it != previous }?.let(::SentRunId) ?: return
        runs.value.started.filterValues { from in it }.keys.forEach { started(it, setOf(to)) }
    }

    /** The run [id] started has ended, as a failure when [failed]. */
    fun ended(id: SentMessageId, failed: Boolean) {
        runs.update { it.withEnded(id, failed) }
    }

    /**
     * A turn known by [ids] settled, as a failure when [failed], whoever's run it was. A sent
     * message whose run is one of [ids] has ended with it.
     */
    fun settled(ids: Set<SentRunId>, failed: Boolean) {
        if (ids.isEmpty()) return
        settledRuns.update { current -> (current - ids + ids.associateWith { failed }).newest(MAX_SETTLED_RUNS) }
        runs.update { it.withSettled(ids, failed) }
    }

    /**
     * The echo of the send [id] named [run] after that send's turn settled: when [run] is a settled
     * run, publishes it as the run [id] started, already ended (failed when it failed). An unknown
     * run is not published.
     */
    fun lateEcho(id: SentMessageId, run: SentRunId) {
        val failed = settledRuns.value[run] ?: return
        runs.update { it.withSettledRun(id, run, failed) }
    }
}

/** How many settled run ids are remembered for a late echo. */
internal const val MAX_SETTLED_RUNS = 16

private fun <K, V> Map<K, V>.newest(limit: Int): Map<K, V> =
    if (size <= limit) this else entries.drop(size - limit).associate { it.key to it.value }

/** [id] started [run], which already ended (as a failure when [failed]); it never becomes active. */
internal fun SentMessageRuns.withSettledRun(id: SentMessageId, run: SentRunId, failed: Boolean): SentMessageRuns {
    val next = copy(
        started = started + (id to started[id].orEmpty() + run),
        failed = if (failed) this.failed + run else this.failed,
    )
    return next.bounded()
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

/** Every entry whose run is one of the settled [ids] has ended, failed too when [failed]. */
internal fun SentMessageRuns.withSettled(ids: Set<SentRunId>, failed: Boolean): SentMessageRuns =
    started.entries.filter { (_, runIds) -> runIds.any { it in ids } }
        .fold(this) { current, entry -> current.withEnded(entry.key, failed) }

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
