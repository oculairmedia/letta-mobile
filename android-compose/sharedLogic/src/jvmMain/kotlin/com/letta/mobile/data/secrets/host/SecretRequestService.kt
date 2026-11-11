package com.letta.mobile.data.secrets.host

import com.letta.mobile.data.secrets.request.AgentSecretRequest
import com.letta.mobile.data.secrets.request.AuthoredPurpose
import com.letta.mobile.data.secrets.request.RequestingAgent
import com.letta.mobile.data.secrets.request.SecretKeystoreName
import com.letta.mobile.data.secrets.request.SecretRequestCreated
import com.letta.mobile.data.secrets.request.SecretRequestDetails
import com.letta.mobile.data.secrets.request.SecretRequestEvent
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.journal.SecretRequestJournal
import com.letta.mobile.data.secrets.journal.SecretRequestJournalException
import com.letta.mobile.data.secrets.request.SecretRequestList
import com.letta.mobile.data.secrets.request.SecretRequestPrincipal
import com.letta.mobile.data.secrets.request.SecretRequestProblem
import com.letta.mobile.data.secrets.request.SecretRequestReceipt
import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.data.secrets.request.SecretRequestState
import com.letta.mobile.data.secrets.request.SecretRequestStatus
import com.letta.mobile.data.secrets.request.SecretRequestTarget
import com.letta.mobile.data.secrets.request.SecretRequestTiming
import com.letta.mobile.data.secrets.request.SecretRequestValidation
import com.letta.mobile.data.secrets.request.SecretServiceError
import com.letta.mobile.data.secrets.request.TextAuthor
import com.letta.mobile.plugin.keystore.KeystoreProvider
import com.letta.mobile.plugin.keystore.SecretValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The host side of the secure secret-input flow. An agent creates a request naming an item and
 * field; the human answers it from a dedicated secure surface; the host writes the value to the
 * keystore and the agent learns only a receipt (status, item, field, fixed failure reason).
 *
 * Guarantees, each covered by tests:
 *
 * - **Single use.** A request is claimed in the journal (pending to submitting) before anything is
 *   written to the keystore, under the service lock, so concurrent or replayed submissions write at
 *   most once; every later submission answers [SecretServiceError.NOT_PENDING] and writes nothing.
 *   The claim and the outcome are durable, so a replay after a restart is refused too.
 * - **Expiry.** A pending request expires at its fixed expiry time; every call expires due requests
 *   first, and [expire] lets the host do it on a schedule.
 * - **Human only.** [submit], [cancel], [list] and [get] need a [SecretRequestPrincipal] whose
 *   [SecretRequestPrincipal.mayAnswer] is true; agent origins are refused.
 * - **Fixed target.** The keystore, item and field are fixed when the request is created; nothing
 *   at submission can change them.
 * - **No value in a sink.** The value travels only inside [SecretValue], is wiped when [submit]
 *   returns on every path, and never reaches the journal, telemetry, a receipt or an error.
 * - **Nothing partial.** See [SecretVaultWriter]: create-then-verify, undo on mismatch, no retry.
 *
 * One host process owns a journal: a submitting record this instance is not writing is treated as
 * left behind by a stopped host and failed as interrupted.
 */
class SecretRequestService(
    journal: SecretRequestJournal,
    provider: KeystoreProvider,
    private val settings: SecretRequestSettings = SecretRequestSettings(),
) {
    private val ledger = SecretRequestLedger(journal, settings.clock)
    private val writer = SecretVaultWriter(provider)
    private val keystore = SecretKeystoreName(provider.describe().id.value)
    private val writeMutex = Mutex()

    /**
     * Creates a pending request for [agent] (the verified requesting agent) from its [request],
     * after validation. The target is fixed here.
     */
    suspend fun request(agent: RequestingAgent, request: AgentSecretRequest): SecretServiceResult<SecretRequestCreated> {
        val validation = SecretRequestValidation.validate(request, settings.policy.itemScope)
        if (validation is SecretRequestValidation.Invalid) {
            SecretRequestTelemetry.requestRefused(validation.problem)
            return SecretServiceResult.Refused(SecretServiceError.INVALID_REQUEST, validation.problem)
        }
        val record = newRecord(agent, (validation as SecretRequestValidation.Valid).request)
        return journaled {
            when (ledger.add(record, settings.policy.maxPendingPerAgent)) {
                AddOutcome.ADDED -> created(record)
                AddOutcome.TOO_MANY_PENDING -> tooManyPending()
                AddOutcome.DUPLICATE_ID -> SecretServiceResult.Refused(SecretServiceError.JOURNAL_UNAVAILABLE)
            }
        }
    }

    /**
     * Writes [value] to request [requestId]'s fixed target on behalf of [principal] and answers the
     * agent's receipt. The service takes ownership of [value] and wipes it before returning, on
     * every path. An empty or oversized value is refused and the request stays pending; any other
     * refusal writes nothing. A keystore failure settles the request as failed.
     */
    suspend fun submit(
        principal: SecretRequestPrincipal,
        requestId: SecretRequestId,
        value: SecretValue,
    ): SecretServiceResult<SecretRequestReceipt> = try {
        when {
            !principal.mayAnswer -> refuseAnswer(requestId, SecretServiceError.FORBIDDEN)
            value.valueProblem != null -> refuseAnswer(requestId, SecretServiceError.INVALID_VALUE)
            else -> journaled { claimAndWrite(requestId, value) }
        }
    } finally {
        value.wipe()
    }

    /** Declines request [requestId] on behalf of [principal]; answers the agent's receipt. */
    suspend fun cancel(principal: SecretRequestPrincipal, requestId: SecretRequestId): SecretServiceResult<SecretRequestReceipt> {
        if (!principal.mayAnswer) return refuseAnswer(requestId, SecretServiceError.FORBIDDEN)
        return journaled { settle(ledger.transition(requestId, SecretRequestEvent.Cancel), requestId) }
    }

    /** Expires every pending request due at [now]; answers how many expired. */
    suspend fun expire(now: Instant = settings.clock.now()): SecretServiceResult<Int> =
        journaled { SecretServiceResult.Ok(ledger.expire(now)) }

    /** Every request the journal holds, newest first, for [principal]'s client. */
    suspend fun list(principal: SecretRequestPrincipal): SecretServiceResult<SecretRequestList> {
        if (!principal.mayAnswer) return SecretServiceResult.Refused(SecretServiceError.FORBIDDEN)
        return journaled { SecretServiceResult.Ok(SecretRequestList(ledger.current().sortedByDescending { it.timing.createdAtMs })) }
    }

    /** Request [requestId] for [principal]'s client; an unknown id answers [SecretServiceError.UNKNOWN_REQUEST]. */
    suspend fun get(principal: SecretRequestPrincipal, requestId: SecretRequestId): SecretServiceResult<SecretRequestRecord> {
        if (!principal.mayAnswer) return SecretServiceResult.Refused(SecretServiceError.FORBIDDEN)
        return journaled { found(ledger.current().firstOrNull { it.requestId == requestId }) }
    }

    /**
     * The receipt of [agent]'s own request [requestId], or an `Ok(null)` while it is still open.
     * Another agent's request and an unknown id both answer [SecretServiceError.UNKNOWN_REQUEST].
     */
    suspend fun receipt(agent: RequestingAgent, requestId: SecretRequestId): SecretServiceResult<SecretRequestReceipt?> =
        journaled {
            ownRecord(ledger.current(), agent, requestId)
                ?.let { SecretServiceResult.Ok(it.receipt()) }
                ?: SecretServiceResult.Refused(SecretServiceError.UNKNOWN_REQUEST)
        }

    /**
     * Suspends until [agent]'s own request [requestId] reaches a terminal state and answers its
     * receipt. While waiting it expires the request itself once its expiry passes. Another agent's
     * request and an unknown id answer [SecretServiceError.UNKNOWN_REQUEST] at once.
     */
    suspend fun awaitReceipt(agent: RequestingAgent, requestId: SecretRequestId): SecretServiceResult<SecretRequestReceipt> {
        while (true) {
            when (val lookup = receipt(agent, requestId)) {
                is SecretServiceResult.Refused -> return lookup
                is SecretServiceResult.Ok -> lookup.value?.let { return SecretServiceResult.Ok(it) }
            }
            waitForChange(requestId)
        }
    }

    private suspend fun waitForChange(requestId: SecretRequestId) {
        val record = ledger.records.value.firstOrNull { it.requestId == requestId } ?: return
        val remaining = record.timing.expiresAtMs - settings.clock.now().toEpochMilliseconds()
        withTimeoutOrNull(remaining.coerceAtLeast(MIN_WAIT_MS).milliseconds) {
            ledger.records.first { records -> records.none { it.requestId == requestId && !it.status.state.isTerminal } }
        }
    }

    private suspend fun claimAndWrite(requestId: SecretRequestId, value: SecretValue): SecretServiceResult<SecretRequestReceipt> {
        val claim = ledger.transition(requestId, SecretRequestEvent.Claim)
        val claimed = (claim as? LedgerOutcome.Applied)?.record ?: return refuseAnswer(requestId, SecretServiceError.NOT_PENDING)
        return try {
            val outcome = writeMutex.withLock { writer.write(claimed.details.target, value) }
            settle(ledger.transition(requestId, outcome), requestId)
        } finally {
            ledger.release(requestId)
        }
    }

    private fun settle(outcome: LedgerOutcome, requestId: SecretRequestId): SecretServiceResult<SecretRequestReceipt> {
        val receipt = (outcome as? LedgerOutcome.Applied)?.record?.receipt()
            ?: return refuseAnswer(requestId, SecretServiceError.NOT_PENDING)
        SecretRequestTelemetry.settled(requestId, receipt)
        return SecretServiceResult.Ok(receipt)
    }

    private fun newRecord(agent: RequestingAgent, request: AgentSecretRequest): SecretRequestRecord {
        val now = settings.clock.now().toEpochMilliseconds()
        val target = SecretRequestTarget(keystore, request.vaultItemName, request.fieldName)
        return SecretRequestRecord(
            requestId = newRequestId(settings.random),
            details = SecretRequestDetails(agent.agentId, target, AuthoredPurpose(request.purpose, TextAuthor.AGENT)),
            timing = SecretRequestTiming(now, now + settings.policy.ttl.inWholeMilliseconds),
            status = SecretRequestStatus(SecretRequestState.PENDING),
        )
    }

    private suspend fun <T> journaled(block: suspend () -> SecretServiceResult<T>): SecretServiceResult<T> = try {
        block()
    } catch (expectedJournalFailure: SecretRequestJournalException) {
        SecretRequestTelemetry.journalUnavailable(expectedJournalFailure.problem)
        SecretServiceResult.Refused(SecretServiceError.JOURNAL_UNAVAILABLE)
    }

    private companion object {
        const val MIN_WAIT_MS = 1_000L
    }
}

private fun newRequestId(random: SecretRandomSource): SecretRequestId {
    val bytes = random.nextBytes(SecretRequestId.RANDOM_BYTES)
    check(bytes.size == SecretRequestId.RANDOM_BYTES) { "The random source returned the wrong number of bytes" }
    return SecretRequestId(SecretRequestId.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
}

private fun created(record: SecretRequestRecord): SecretServiceResult<SecretRequestCreated> {
    SecretRequestTelemetry.created(record.requestId)
    return SecretServiceResult.Ok(SecretRequestCreated(record.requestId, record.status.state, record.timing.expiresAtMs))
}

private fun tooManyPending(): SecretServiceResult<SecretRequestCreated> {
    val problem = SecretRequestProblem.TOO_MANY_PENDING
    SecretRequestTelemetry.requestRefused(problem)
    return SecretServiceResult.Refused(SecretServiceError.INVALID_REQUEST, problem)
}

private fun found(record: SecretRequestRecord?): SecretServiceResult<SecretRequestRecord> =
    record?.let { SecretServiceResult.Ok(it) } ?: SecretServiceResult.Refused(SecretServiceError.UNKNOWN_REQUEST)

private fun ownRecord(records: List<SecretRequestRecord>, agent: RequestingAgent, requestId: SecretRequestId): SecretRequestRecord? =
    records.firstOrNull { it.requestId == requestId }?.takeIf { it.details.requestingAgent == agent.agentId }

private fun refuseAnswer(requestId: SecretRequestId, error: SecretServiceError): SecretServiceResult<Nothing> {
    SecretRequestTelemetry.answerRefused(requestId, error)
    return SecretServiceResult.Refused(error)
}
