package com.letta.mobile.data.secrets.client

import com.letta.mobile.data.secrets.request.SecretReceiptStatus
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.request.SecretRequestReceipt
import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.data.secrets.request.SecretRequestState
import com.letta.mobile.data.secrets.request.SecretRequestTestData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory [SecretRequestClient] for tests. It plays the host: it lists [records], answers a
 * submission with [answer], and remembers what reached it. [received] keeps the bytes each
 * submission carried (decoded), standing in for the vault: it is the one place a test value may be.
 */
internal class FakeSecretRequestClient(
    vararg initial: SecretRequestRecord,
) : SecretRequestClient {
    /** What the host holds, pending or not. */
    val records = MutableStateFlow(initial.toList())

    /** How the host answers each submission. */
    var answer: suspend (SecretRequestId) -> SecretClientOutcome = { _ -> SecretClientOutcome.Settled(receipt(SecretReceiptStatus.SAVED)) }

    /** The values that reached the host, decoded: the stand-in vault. */
    val received = mutableListOf<String>()

    /** Every [SecretInput] handed to [submit], to check it was wiped afterwards. */
    val inputs = mutableListOf<SecretInput>()

    /** The requests declined. */
    val declined = mutableListOf<SecretRequestId>()

    override fun pending(scope: SecretRequestScope): Flow<List<SecretRequestRecord>> = records.map { all ->
        all.filter { it.status.state == SecretRequestState.PENDING && scope.covers(it) }
    }

    override suspend fun submit(requestId: SecretRequestId, value: SecretInput): SecretClientOutcome {
        inputs += value
        received += value.revealSecretBytes { it.decodeToString() }
        return answer(requestId)
    }

    override suspend fun decline(requestId: SecretRequestId): SecretClientOutcome {
        declined += requestId
        return SecretClientOutcome.Settled(receipt(SecretReceiptStatus.CANCELLED))
    }

    private fun SecretRequestScope.covers(record: SecretRequestRecord): Boolean = when (this) {
        SecretRequestScope.Everyone -> true
        is SecretRequestScope.Agent -> record.details.requestingAgent == agent
    }

    companion object {
        /** A receipt for the test target with [status]. */
        fun receipt(status: SecretReceiptStatus): SecretRequestReceipt =
            SecretRequestReceipt(status, SecretRequestTestData.TARGET.item, SecretRequestTestData.TARGET.field)
    }
}
