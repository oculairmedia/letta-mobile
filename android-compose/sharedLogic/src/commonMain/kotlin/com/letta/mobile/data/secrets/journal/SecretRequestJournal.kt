package com.letta.mobile.data.secrets.journal

import com.letta.mobile.data.secrets.request.SecretRequestLimits
import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.data.secrets.request.SecretRequestState
import com.letta.mobile.data.secrets.request.SecretRequestStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the host keeps secret requests so single use and expiry survive a restart. A journal
 * stores [SecretRequestRecord]s only: ids, targets, the agent's purpose, states and timestamps,
 * never a secret value.
 *
 * Rules every implementation keeps:
 *
 * - [update] is atomic and durable: when it returns, the new records survive a restart; when it
 *   throws, the stored records are unchanged.
 * - Fail closed: a journal it cannot read, parse or trust throws [SecretRequestJournalException];
 *   it never answers an empty list in place of records it lost.
 * - It refuses an update that would break [SecretRequestJournalRules].
 */
interface SecretRequestJournal {
    /** Every stored record, oldest first. */
    suspend fun read(): List<SecretRequestRecord>

    /**
     * Replaces the stored records with [change] applied to them, durably, and returns the new
     * records. [change] runs under the journal's lock and must not suspend or call back into it.
     */
    suspend fun update(change: (List<SecretRequestRecord>) -> List<SecretRequestRecord>): List<SecretRequestRecord>
}

/** Why a journal refused to read or write. */
enum class SecretJournalProblem(
    /** A fixed, non-secret sentence for operators. */
    val message: String,
) {
    /** The stored journal cannot be parsed or breaks the record rules. */
    CORRUPT("The secret request journal is corrupt"),

    /** The journal would exceed its record or byte bound. */
    FULL("The secret request journal is full"),

    /** An earlier write did not complete cleanly; an operator must reconcile it. */
    NEEDS_RECONCILIATION("The secret request journal needs reconciliation"),

    /** The journal could not be written. */
    WRITE_FAILED("The secret request journal could not be written"),
}

/**
 * A journal failure. Its message is the fixed text of [problem], so no path, record or value can
 * enter it; the cause, when present, is for local debugging only and is never logged by callers.
 */
class SecretRequestJournalException(
    /** What went wrong. */
    val problem: SecretJournalProblem,
    cause: Throwable? = null,
) : IllegalStateException(problem.message, cause)

/** The rules every stored record list keeps, shared by every journal implementation. */
object SecretRequestJournalRules {
    /** Why [records] may not be stored, or null when they may. */
    fun problem(records: List<SecretRequestRecord>): SecretJournalProblem? = when {
        records.size > SecretRequestLimits.MAX_JOURNAL_RECORDS -> SecretJournalProblem.FULL
        records.distinctBy { it.requestId }.size != records.size -> SecretJournalProblem.CORRUPT
        !records.all(::isWellFormed) -> SecretJournalProblem.CORRUPT
        else -> null
    }

    /** Throws [SecretRequestJournalException] when [records] break the rules. */
    fun require(records: List<SecretRequestRecord>) {
        problem(records)?.let { throw SecretRequestJournalException(it) }
    }

    private fun isWellFormed(record: SecretRequestRecord): Boolean {
        val target = record.details.target
        return listOf(
            record.requestId.isWellFormed,
            target.keystore.isWellFormed,
            target.item.isWellFormed,
            target.field.isWellFormed,
            consistentStatus(record.status),
        ).all { it }
    }

    private fun consistentStatus(status: SecretRequestStatus): Boolean {
        val failed = status.state == SecretRequestState.FAILED
        return failed == (status.reason != null) && status.state.isTerminal == (status.settledAtMs != null)
    }
}

/** A journal held in memory: for tests and hosts without durable storage. It does not survive a restart. */
class InMemorySecretRequestJournal(
    initial: List<SecretRequestRecord> = emptyList(),
) : SecretRequestJournal {
    private val mutex = Mutex()
    private var records: List<SecretRequestRecord> = initial.also(SecretRequestJournalRules::require)

    /** The stored records. */
    override suspend fun read(): List<SecretRequestRecord> = mutex.withLock { records }

    /** Applies [change] and keeps the result when it follows [SecretRequestJournalRules]. */
    override suspend fun update(
        change: (List<SecretRequestRecord>) -> List<SecretRequestRecord>,
    ): List<SecretRequestRecord> = mutex.withLock {
        change(records).also(SecretRequestJournalRules::require).also { records = it }
    }
}
