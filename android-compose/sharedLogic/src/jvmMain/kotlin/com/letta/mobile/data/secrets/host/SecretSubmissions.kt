package com.letta.mobile.data.secrets.host

import com.letta.mobile.data.secrets.request.SecretFailureReason
import com.letta.mobile.data.secrets.request.SecretRequestEvent
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.request.SecretRequestReceipt
import com.letta.mobile.data.secrets.request.SecretRequestState
import com.letta.mobile.data.secrets.request.SecretServiceError
import com.letta.mobile.plugin.keystore.SecretValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * The submission half of [SecretRequestService]: one write at a time, each bounded in time.
 *
 * - A submission for a request that is not pending is refused before anything else.
 * - Writes are serialized by one lock. A submission that cannot get it within
 *   [SecretRequestSettings.writeLockWait] is refused `busy` before its value is decoded or its
 *   request claimed, so one slow keystore can never pile up decoded values or claimed requests.
 * - A write that runs over [SecretRequestSettings.keystoreDeadline] settles its request as failed
 *   (`vault_unavailable`), wipes the value and releases the lock.
 *
 * Journal failures propagate to the service, which maps them.
 */
internal class SecretSubmissions(
    private val ledger: SecretRequestLedger,
    private val writer: SecretVaultWriter,
    private val settings: SecretRequestSettings,
) {
    private val writeMutex = Mutex()

    /** Writes the value [decode] makes to request [requestId]; see [SecretRequestService.submit]. */
    suspend fun submit(requestId: SecretRequestId, decode: () -> SecretValue?): SecretServiceResult<SecretRequestReceipt> =
        if (isPending(requestId)) withWriteLock(requestId) { decodeAndWrite(requestId, decode) } else notPending(requestId)

    private suspend fun isPending(requestId: SecretRequestId): Boolean =
        ledger.current().any { it.requestId == requestId && it.status.state == SecretRequestState.PENDING }

    /** Runs [write] holding the write lock, or refuses [SecretServiceError.BUSY] when the lock is not free in time. */
    private suspend fun withWriteLock(
        requestId: SecretRequestId,
        write: suspend () -> SecretServiceResult<SecretRequestReceipt>,
    ): SecretServiceResult<SecretRequestReceipt> {
        bounded(settings.writeLockWait) { writeMutex.lock() } ?: return refuseAnswer(requestId, SecretServiceError.BUSY)
        return try {
            write()
        } finally {
            writeMutex.unlock()
        }
    }

    private suspend fun decodeAndWrite(requestId: SecretRequestId, decode: () -> SecretValue?): SecretServiceResult<SecretRequestReceipt> {
        val value = decode() ?: return refuseAnswer(requestId, SecretServiceError.INVALID_VALUE)
        return try {
            if (value.valueProblem != null) refuseAnswer(requestId, SecretServiceError.INVALID_VALUE) else claimAndWrite(requestId, value)
        } finally {
            value.wipe()
        }
    }

    private suspend fun claimAndWrite(requestId: SecretRequestId, value: SecretValue): SecretServiceResult<SecretRequestReceipt> {
        val claim = ledger.transition(requestId, SecretRequestEvent.Claim)
        val claimed = (claim as? LedgerOutcome.Applied)?.record ?: return notPending(requestId)
        return try {
            val outcome = bounded(settings.keystoreDeadline) { writer.write(claimed.details, value) } ?: overran(requestId)
            settle(ledger.transition(requestId, outcome), requestId)
        } finally {
            ledger.release(requestId)
        }
    }
}

/** [block]'s result, or null when it runs over [limit]; [Duration.INFINITE] never runs over. */
internal suspend fun <T : Any> bounded(limit: Duration, block: suspend () -> T): T? =
    if (limit.isInfinite()) block() else withTimeoutOrNull(limit) { block() }

/** The receipt of a request [outcome] settled, or `not_pending` when it could not be applied. */
internal fun settle(outcome: LedgerOutcome, requestId: SecretRequestId): SecretServiceResult<SecretRequestReceipt> {
    val receipt = (outcome as? LedgerOutcome.Applied)?.record?.receipt() ?: return notPending(requestId)
    SecretRequestTelemetry.settled(requestId, receipt)
    return SecretServiceResult.Ok(receipt)
}

/** A refusal of an answer to [requestId] with [error], reported by its fixed code. */
internal fun refuseAnswer(requestId: SecretRequestId, error: SecretServiceError): SecretServiceResult<Nothing> {
    SecretRequestTelemetry.answerRefused(requestId, error)
    return SecretServiceResult.Refused(error)
}

private fun notPending(requestId: SecretRequestId): SecretServiceResult<Nothing> = refuseAnswer(requestId, SecretServiceError.NOT_PENDING)

/** The settling event of a write that ran over the keystore deadline. */
private fun overran(requestId: SecretRequestId): SecretRequestEvent {
    SecretRequestTelemetry.keystoreOverran(requestId)
    return SecretRequestEvent.Fail(SecretFailureReason.VAULT_UNAVAILABLE)
}
