package com.letta.mobile.data.secrets.request

import com.letta.mobile.data.model.AgentId
import kotlin.time.Instant

/** Shared builders for secret request tests. Every value here is an obvious test name, never a secret. */
internal object SecretRequestTestData {
    val NOW: Instant = Instant.fromEpochMilliseconds(1_800_000_000_000)
    val AGENT: AgentId = AgentId("agent-requester")
    val OTHER_AGENT: AgentId = AgentId("agent-bystander")
    val TARGET = SecretRequestTarget(SecretKeystoreName("test.vault"), VaultItemName("team/openai"), VaultFieldName("api_key"))

    val PURPOSE = AuthoredPurpose(SecretPurpose("Embeddings for the notes index"), TextAuthor.AGENT)

    fun id(number: Int): SecretRequestId = SecretRequestId(SecretRequestId.PREFIX + number.toString().padStart(43, 'A'))

    fun request(purpose: String = "Embeddings for the notes index"): AgentSecretRequest =
        AgentSecretRequest(SecretPurpose(purpose), TARGET.item, TARGET.field)

    fun record(id: SecretRequestId = id(1), status: SecretRequestStatus = pending()): SecretRequestRecord =
        SecretRequestRecord(
            requestId = id,
            details = SecretRequestDetails(AGENT, TARGET, PURPOSE),
            timing = SecretRequestTiming(NOW.toEpochMilliseconds(), NOW.toEpochMilliseconds() + SecretRequestLimits.DEFAULT_TTL_MS),
            status = status,
        )

    fun pending(): SecretRequestStatus = SecretRequestStatus(SecretRequestState.PENDING)

    fun status(state: SecretRequestState): SecretRequestStatus = when (state) {
        SecretRequestState.PENDING, SecretRequestState.SUBMITTING -> SecretRequestStatus(state)
        SecretRequestState.FAILED -> SecretRequestStatus(state, SecretFailureReason.VAULT_ERROR, NOW.toEpochMilliseconds())
        else -> SecretRequestStatus(state, null, NOW.toEpochMilliseconds())
    }
}
