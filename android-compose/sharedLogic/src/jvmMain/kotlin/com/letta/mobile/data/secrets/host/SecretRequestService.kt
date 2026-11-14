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
 *   at submission can change them. The item must lie in the policy's [SecretItemScope] for the
 *   requesting agent (per agent unless the host owner chose a shared root).
 * - **No silent replace.** An existing value is replaced only when the request was created while
 *   the slot already held one ([SecretRequestDetails.replacesExisting], decided by the host); a
 *   slot that appears later fails the submission with `target_changed` and nothing is written.
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
    private val ledger = SecretRequestLedger(journal, settings.clock, settings.policy)
    private val writer = SecretVaultWriter(provider)
    private val keystore = SecretKeystoreName(provider.describe().id.value)
    private val submissions = SecretSubmissions(ledger, writer, settings)

    /**
     * Creates a pending request for [agent] (the verified requesting agent) from its [request],
     * after validation against the policy's item scope for that agent. The target is fixed here,
     * and so is whether it replaces an existing value: the host reads the slot now
     * ([SecretRequestDetails.replacesExisting]); the agent's answer is the same either way.
     */
    suspend fun request(agent: RequestingAgent, request: AgentSecretRequest): SecretServiceResult<SecretRequestCreated> {
        val scope = settings.policy.itemScope.rootFor(agent) ?: COVERS_NOTHING
        val validation = SecretRequestValidation.validate(request, scope)
        if (validation is SecretRequestValidation.Invalid) {
            SecretRequestTelemetry.requestRefused(validation.problem)
            return SecretServiceResult.Refused(SecretServiceError.INVALID_REQUEST, validation.problem)
        }
        val valid = (validation as SecretRequestValidation.Valid).request
        val replacesExisting = bounded(settings.keystoreDeadline) { writer.holdsValue(targetOf(valid)) } ?: false
        val record = newRecord(agent, valid, replacesExisting)
        return journaled {
            when (ledger.add(record)) {
                AddOutcome.ADDED -> created(record)
                AddOutcome.TOO_MANY_PENDING -> refusedRequest(SecretRequestProblem.TOO_MANY_PENDING)
                AddOutcome.TOO_MANY_REQUESTS -> refusedRequest(SecretRequestProblem.TOO_MANY_REQUESTS)
                AddOutcome.DUPLICATE_ID -> SecretServiceResult.Refused(SecretServiceError.JOURNAL_UNAVAILABLE)
            }
        }
    }

    /**
     * Writes [value] to request [requestId]'s fixed target on behalf of [principal] and answers the
     * agent's receipt. The service takes ownership of [value] and wipes it before returning, on
     * every path. See the other [submit] for the order of checks.
     */
    suspend fun submit(
        principal: SecretRequestPrincipal,
        requestId: SecretRequestId,
        value: SecretValue,
    ): SecretServiceResult<SecretRequestReceipt> = try {
        submit(principal, requestId) { value }
    } finally {
        value.wipe()
    }

    /**
     * Writes the value [decode] makes to request [requestId]'s fixed target on behalf of
     * [principal] and answers the agent's receipt. In order: the principal must answer; the request
     * must be pending; this submission must get the write lock within
     * [SecretRequestSettings.writeLockWait] or it is refused [SecretServiceError.BUSY]; only then is
     * [decode] called (no value, or an empty or oversized one, is [SecretServiceError.INVALID_VALUE]),
     * the request claimed and the value written within [SecretRequestSettings.keystoreDeadline].
     * Every refusal leaves the request as it was and writes nothing; a keystore failure or an
     * overrun settles it as failed. The decoded value is wiped before this returns, on every path.
     */
    suspend fun submit(
        principal: SecretRequestPrincipal,
        requestId: SecretRequestId,
        decode: () -> SecretValue?,
    ): SecretServiceResult<SecretRequestReceipt> {
        if (!principal.mayAnswer) return refuseAnswer(requestId, SecretServiceError.FORBIDDEN)
        return journaled {
            submissions.submit(requestId, decode)
        }
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

    private fun targetOf(request: AgentSecretRequest): SecretRequestTarget =
        SecretRequestTarget(keystore, request.vaultItemName, request.fieldName)

    private fun newRecord(agent: RequestingAgent, request: AgentSecretRequest, replacesExisting: Boolean): SecretRequestRecord {
        val now = settings.clock.now().toEpochMilliseconds()
        val purpose = AuthoredPurpose(request.purpose, TextAuthor.AGENT)
        return SecretRequestRecord(
            requestId = newRequestId(settings.random),
            details = SecretRequestDetails(agent.agentId, targetOf(request), purpose, replacesExisting),
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

        /** A scope root no well-formed item equals or lies under: for an agent the policy gives no root. */
        val COVERS_NOTHING = com.letta.mobile.data.secrets.request.VaultItemName("")
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

private fun refusedRequest(problem: SecretRequestProblem): SecretServiceResult<SecretRequestCreated> {
    SecretRequestTelemetry.requestRefused(problem)
    return SecretServiceResult.Refused(SecretServiceError.INVALID_REQUEST, problem)
}

private fun found(record: SecretRequestRecord?): SecretServiceResult<SecretRequestRecord> =
    record?.let { SecretServiceResult.Ok(it) } ?: SecretServiceResult.Refused(SecretServiceError.UNKNOWN_REQUEST)

private fun ownRecord(records: List<SecretRequestRecord>, agent: RequestingAgent, requestId: SecretRequestId): SecretRequestRecord? =
    records.firstOrNull { it.requestId == requestId }?.takeIf { it.details.requestingAgent == agent.agentId }
