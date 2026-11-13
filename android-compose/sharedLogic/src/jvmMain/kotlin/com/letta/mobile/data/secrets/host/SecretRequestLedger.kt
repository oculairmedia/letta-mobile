package com.letta.mobile.data.secrets.host

import com.letta.mobile.data.secrets.request.SecretRequestEvent
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.journal.SecretJournalProblem
import com.letta.mobile.data.secrets.journal.SecretRequestJournal
import com.letta.mobile.data.secrets.journal.SecretRequestJournalException
import com.letta.mobile.data.secrets.request.SecretRequestLifecycle
import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.data.secrets.request.SecretRequestState
import com.letta.mobile.data.secrets.request.SecretRequestTransition
import com.letta.mobile.data.secrets.request.TransitionRefusal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

/** The outcome of one event applied through the ledger. */
internal sealed interface LedgerOutcome {
    /** The event was applied and journaled; [record] is the request afterwards. */
    data class Applied(val record: SecretRequestRecord) : LedgerOutcome

    /** The event was refused; nothing changed. */
    data class Refused(val refusal: TransitionRefusal) : LedgerOutcome

    /** No request has that id (never existed, pruned). */
    data object Unknown : LedgerOutcome
}

/** The outcome of adding a new request. */
internal enum class AddOutcome { ADDED, TOO_MANY_PENDING, TOO_MANY_REQUESTS, DUPLICATE_ID }

/**
 * The service's single writer to the journal. Every operation runs under one mutex, re-reads the
 * journal and first applies housekeeping: a submitting request this process is not writing is
 * failed as interrupted (it was left by a stopped host, or by a write whose outcome could not be
 * journaled), due pending requests expire, old settled ones are pruned and each agent keeps at
 * most [SecretRequestPolicy.maxSettledPerAgent] settled ones. Changes are journaled before they are
 * published to [records] or returned. A new request that would not fit makes room first by
 * pruning settled requests (those outside the creation window, then all of them) before the
 * journal refuses it as full.
 */
internal class SecretRequestLedger(
    private val journal: SecretRequestJournal,
    private val clock: Clock,
    private val policy: SecretRequestPolicy,
) {
    private val mutex = Mutex()
    private val inFlight = mutableSetOf<SecretRequestId>()
    private val published = MutableStateFlow<List<SecretRequestRecord>>(emptyList())

    /** The records as last read or written. */
    val records: StateFlow<List<SecretRequestRecord>> = published.asStateFlow()

    /** The current records after housekeeping, journaling the housekeeping when it changed anything. */
    suspend fun current(): List<SecretRequestRecord> = mutex.withLock {
        val now = clock.now()
        val stored = journal.read()
        val next = if (housekeep(stored, now) == stored) stored else journal.update { housekeep(it, now) }
        next.also { published.value = it }
    }

    /** Expires every pending request due at [now]; answers how many expired. */
    suspend fun expire(now: Instant): Int = mutex.withLock {
        var expired = 0
        val next = journal.update { stored ->
            val prepared = housekeep(stored, clock.now())
            SecretRequestLifecycle.expireDue(prepared, now).also { result ->
                expired = result.zip(prepared).count { (after, before) -> after.status != before.status }
            }
        }
        published.value = next
        expired
    }

    /**
     * Adds [record] unless its agent already has too many pending requests or created too many
     * within the creation window. When the journal is full it prunes settled requests to make room
     * (see [Room]) and tries again; only when even that is not enough does the full journal refuse.
     */
    suspend fun add(record: SecretRequestRecord): AddOutcome = mutex.withLock {
        val now = clock.now()
        var outcome = AddOutcome.ADDED
        val next = updateMakingRoom(now) { prepared ->
            outcome = admission(prepared, record)
            if (outcome == AddOutcome.ADDED) prepared + record else prepared
        }
        published.value = next
        outcome
    }

    /** Applies [change] to the housekept records, pruning harder each time the journal is full. */
    private suspend fun updateMakingRoom(
        now: Instant,
        change: (List<SecretRequestRecord>) -> List<SecretRequestRecord>,
    ): List<SecretRequestRecord> {
        for (room in Room.entries) {
            try {
                return journal.update { stored -> change(room.prune(housekeep(stored, now), windowStart(now))) }
            } catch (full: SecretRequestJournalException) {
                if (full.problem != SecretJournalProblem.FULL || room == Room.entries.last()) throw full
                SecretRequestTelemetry.journalPruned(room.name.lowercase())
            }
        }
        error("unreachable: the last room rethrows")
    }

    private fun windowStart(now: Instant): Long = now.toEpochMilliseconds() - policy.creationWindow.inWholeMilliseconds

    /** Applies [event] to request [id] and journals the result. A claim marks the request as being written here. */
    suspend fun transition(id: SecretRequestId, event: SecretRequestEvent): LedgerOutcome = mutex.withLock {
        val now = clock.now()
        var outcome: LedgerOutcome = LedgerOutcome.Unknown
        val next = journal.update { stored ->
            val prepared = housekeep(stored, now)
            val target = prepared.firstOrNull { it.requestId == id }
            outcome = target?.let { outcomeOf(SecretRequestLifecycle.apply(it, event, now)) } ?: LedgerOutcome.Unknown
            val applied = (outcome as? LedgerOutcome.Applied)?.record
            if (applied == null) prepared else prepared.map { if (it.requestId == id) applied else it }
        }
        if (event == SecretRequestEvent.Claim && outcome is LedgerOutcome.Applied) inFlight += id
        published.value = next
        outcome
    }

    /** Forgets that request [id] is being written here; a later housekeeping pass fails it if it is still submitting. */
    suspend fun release(id: SecretRequestId) {
        mutex.withLock { inFlight -= id }
    }

    private fun housekeep(records: List<SecretRequestRecord>, now: Instant): List<SecretRequestRecord> {
        val orphaned = records.map { record ->
            if (isOrphaned(record)) appliedOrSame(record, SecretRequestEvent.Interrupt, now) else record
        }
        return capSettled(SecretRequestLifecycle.prune(SecretRequestLifecycle.expireDue(orphaned, now), now))
    }

    /** [records] with at most [SecretRequestPolicy.maxSettledPerAgent] settled requests per agent, the newest. */
    private fun capSettled(records: List<SecretRequestRecord>): List<SecretRequestRecord> {
        val kept = records.filter { it.status.state.isTerminal }
            .groupBy { it.details.requestingAgent }
            .values
            .flatMap { settled -> settled.sortedByDescending { it.status.settledAtMs }.take(policy.maxSettledPerAgent) }
            .mapTo(mutableSetOf()) { it.requestId }
        return records.filter { !it.status.state.isTerminal || it.requestId in kept }
    }

    private fun isOrphaned(record: SecretRequestRecord): Boolean =
        record.status.state == SecretRequestState.SUBMITTING && record.requestId !in inFlight

    private fun appliedOrSame(record: SecretRequestRecord, event: SecretRequestEvent, now: Instant): SecretRequestRecord =
        (SecretRequestLifecycle.apply(record, event, now) as? SecretRequestTransition.Applied)?.record ?: record

    private fun admission(records: List<SecretRequestRecord>, record: SecretRequestRecord): AddOutcome {
        val own = records.filter { it.details.requestingAgent == record.details.requestingAgent }
        val windowStart = record.timing.createdAtMs - policy.creationWindow.inWholeMilliseconds
        return when {
            records.any { it.requestId == record.requestId } -> AddOutcome.DUPLICATE_ID
            own.count { it.status.state == SecretRequestState.PENDING } >= policy.maxPendingPerAgent -> AddOutcome.TOO_MANY_PENDING
            own.count { it.timing.createdAtMs > windowStart } >= policy.maxCreatedPerWindow -> AddOutcome.TOO_MANY_REQUESTS
            else -> AddOutcome.ADDED
        }
    }

    private fun outcomeOf(transition: SecretRequestTransition): LedgerOutcome = when (transition) {
        is SecretRequestTransition.Applied -> LedgerOutcome.Applied(transition.record)
        is SecretRequestTransition.Refused -> LedgerOutcome.Refused(transition.refusal)
    }
}

/**
 * How hard [SecretRequestLedger.add] prunes to make room for a new request, tried in order: first
 * nothing, then settled requests created before the creation window (so the window's count stays
 * exact), then every settled request. Pending and submitting requests are never pruned.
 */
private enum class Room {
    AS_IS,
    SETTLED_BEFORE_WINDOW,
    ALL_SETTLED,
    ;

    fun prune(records: List<SecretRequestRecord>, windowStartMs: Long): List<SecretRequestRecord> = when (this) {
        AS_IS -> records
        SETTLED_BEFORE_WINDOW -> records.filterNot { it.status.state.isTerminal && it.timing.createdAtMs <= windowStartMs }
        ALL_SETTLED -> records.filterNot { it.status.state.isTerminal }
    }
}
