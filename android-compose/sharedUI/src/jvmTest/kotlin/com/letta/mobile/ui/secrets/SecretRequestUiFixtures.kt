package com.letta.mobile.ui.secrets

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.secrets.client.SecretClientOutcome
import com.letta.mobile.data.secrets.client.SecretInput
import com.letta.mobile.data.secrets.client.SecretRequestClient
import com.letta.mobile.data.secrets.client.SecretRequestScope
import com.letta.mobile.data.secrets.request.AuthoredPurpose
import com.letta.mobile.data.secrets.request.SecretKeystoreName
import com.letta.mobile.data.secrets.request.SecretPurpose
import com.letta.mobile.data.secrets.request.SecretReceiptStatus
import com.letta.mobile.data.secrets.request.SecretRequestDetails
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.request.SecretRequestLimits
import com.letta.mobile.data.secrets.request.SecretRequestReceipt
import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.data.secrets.request.SecretRequestState
import com.letta.mobile.data.secrets.request.SecretRequestStatus
import com.letta.mobile.data.secrets.request.SecretRequestTarget
import com.letta.mobile.data.secrets.request.SecretRequestTiming
import com.letta.mobile.data.secrets.request.TextAuthor
import com.letta.mobile.data.secrets.request.VaultFieldName
import com.letta.mobile.data.secrets.request.VaultItemName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Instant

/** Test data for the secret request views. Every value is an obvious test name, never a secret. */
internal object SecretRequestUiFixtures {
    const val SENTINEL = "SENTINEL-ui-9d2e71"
    const val AGENT_NAME = "Ada"
    val AGENT = AgentId("agent-ada")
    val NOW: Instant = Instant.fromEpochMilliseconds(1_800_000_000_000)
    val TARGET = SecretRequestTarget(SecretKeystoreName("test.vault"), VaultItemName("shop/gelato"), VaultFieldName("api_key"))
    const val PURPOSE = "Sync the print catalogue"

    fun id(number: Int): SecretRequestId = SecretRequestId(SecretRequestId.PREFIX + number.toString().padStart(43, 'A'))

    fun record(number: Int = 1, expiresAtMs: Long = NOW.toEpochMilliseconds() + SecretRequestLimits.DEFAULT_TTL_MS): SecretRequestRecord =
        SecretRequestRecord(
            requestId = id(number),
            details = SecretRequestDetails(AGENT, TARGET, AuthoredPurpose(SecretPurpose(PURPOSE), TextAuthor.AGENT)),
            timing = SecretRequestTiming(NOW.toEpochMilliseconds(), expiresAtMs),
            status = SecretRequestStatus(SecretRequestState.PENDING),
        )

    fun receipt(status: SecretReceiptStatus): SecretRequestReceipt = SecretRequestReceipt(status, TARGET.item, TARGET.field)

    /** A clock fixed at [NOW]. */
    val clock: Clock = object : Clock {
        override fun now(): Instant = NOW
    }
}

/**
 * A host stand-in for the views: it lists [records] that are pending, answers with [answer], and
 * keeps what reached it. [received] is the stand-in vault, the one place a test value may be.
 */
internal class UiFakeSecretRequestClient(vararg initial: SecretRequestRecord) : SecretRequestClient {
    val records = MutableStateFlow(initial.toList())
    var answer: suspend (SecretRequestId) -> SecretClientOutcome = {
        SecretClientOutcome.Settled(SecretRequestUiFixtures.receipt(SecretReceiptStatus.SAVED))
    }
    val received: MutableList<String> = CopyOnWriteArrayList()
    val inputs: MutableList<SecretInput> = CopyOnWriteArrayList()
    val declined: MutableList<SecretRequestId> = CopyOnWriteArrayList()

    override fun pending(scope: SecretRequestScope): Flow<List<SecretRequestRecord>> =
        records.map { all -> all.filter { it.status.state == SecretRequestState.PENDING } }

    override suspend fun submit(requestId: SecretRequestId, value: SecretInput): SecretClientOutcome {
        inputs += value
        received += value.revealSecretBytes { it.decodeToString() }
        return answer(requestId)
    }

    override suspend fun decline(requestId: SecretRequestId): SecretClientOutcome {
        declined += requestId
        return SecretClientOutcome.Settled(SecretRequestUiFixtures.receipt(SecretReceiptStatus.CANCELLED))
    }

    /** The host settles [id]: it is no longer pending. */
    fun settle(id: SecretRequestId) {
        records.value = records.value.map { record ->
            if (record.requestId == id) record.copy(status = SecretRequestStatus(SecretRequestState.SAVED)) else record
        }
    }
}
