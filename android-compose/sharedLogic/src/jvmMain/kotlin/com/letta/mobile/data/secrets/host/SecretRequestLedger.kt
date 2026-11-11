package com.letta.mobile.data.secrets.host

import com.letta.mobile.data.secrets.request.SecretRequestEvent
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.journal.SecretRequestJournal
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
internal enum class AddOutcome { ADDED, TOO_MANY_PENDING, DUPLICATE_ID }

/**
 * The service's single writer to the journal. Every operation runs under one mutex, re-reads the
 * journal and first applies housekeeping: a submitting request this process is not writing is
 * failed as interrupted (it was left by a stopped host, or by a write whose outcome could not be
 * journaled), due pending requests expire and old settled ones are pruned. Changes are journaled
 * before they are published to [records] or returned.
 */
internal class SecretRequestLedger(
    private val journal: SecretRequestJournal,
    private val clock: Clock,
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

    /** Adds [record] unless its agent already has [maxPending] pending requests. */
    suspend fun add(record: SecretRequestRecord, maxPending: Int): AddOutcome = mutex.withLock {
        var outcome = AddOutcome.ADDED
        val next = journal.update { stored ->
            val prepared = housekeep(stored, clock.now())
            outcome = admission(prepared, record, maxPending)
            if (outcome == AddOutcome.ADDED) prepared + record else prepared
        }
        published.value = next
        outcome
    }

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
        return SecretRequestLifecycle.prune(SecretRequestLifecycle.expireDue(orphaned, now), now)
    }

    private fun isOrphaned(record: SecretRequestRecord): Boolean =
        record.status.state == SecretRequestState.SUBMITTING && record.requestId !in inFlight

    private fun appliedOrSame(record: SecretRequestRecord, event: SecretRequestEvent, now: Instant): SecretRequestRecord =
        (SecretRequestLifecycle.apply(record, event, now) as? SecretRequestTransition.Applied)?.record ?: record

    private fun admission(records: List<SecretRequestRecord>, record: SecretRequestRecord, maxPending: Int): AddOutcome {
        val agent = record.details.requestingAgent
        val pending = records.count { it.details.requestingAgent == agent && it.status.state == SecretRequestState.PENDING }
        return when {
            records.any { it.requestId == record.requestId } -> AddOutcome.DUPLICATE_ID
            pending >= maxPending -> AddOutcome.TOO_MANY_PENDING
            else -> AddOutcome.ADDED
        }
    }

    private fun outcomeOf(transition: SecretRequestTransition): LedgerOutcome = when (transition) {
        is SecretRequestTransition.Applied -> LedgerOutcome.Applied(transition.record)
        is SecretRequestTransition.Refused -> LedgerOutcome.Refused(transition.refusal)
    }
}
