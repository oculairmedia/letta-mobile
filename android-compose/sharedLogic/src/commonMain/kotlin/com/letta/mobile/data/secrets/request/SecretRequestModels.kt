package com.letta.mobile.data.secrets.request

import com.letta.mobile.data.model.AgentId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Why the agent wants the secret, in its own words. Agent-authored plain text: a client shows it
 * labelled as written by the agent, never renders markup or links in it, and never treats it as
 * host or system text. See [SecretRequestValidation] for what is accepted.
 */
@Serializable
@JvmInline
value class SecretPurpose(
    /** The purpose text. */
    val value: String,
) {
    /** The purpose text itself. */
    override fun toString(): String = value
}

/**
 * What an agent asks for (`secret.request`): which item and field should receive a secret and why.
 * It carries no value; the agent never sees one.
 */
@Serializable
data class AgentSecretRequest(
    /** Agent-authored plain text explaining the request. */
    val purpose: SecretPurpose,
    /** The item that will receive the secret. */
    val vaultItemName: VaultItemName,
    /** The field of that item that will receive the secret. */
    val fieldName: VaultFieldName,
)

/** Where a request writes: fixed when the request is created and never changed afterwards. */
@Serializable
data class SecretRequestTarget(
    /** The keystore, chosen by the host. */
    val keystore: SecretKeystoreName,
    /** The item, as the agent named it. */
    val item: VaultItemName,
    /** The field, as the agent named it. */
    val field: VaultFieldName,
)

/** Who wrote a piece of text a client shows. */
@Serializable
enum class TextAuthor {
    /** The requesting agent wrote it; a client labels it "Agent-authored". */
    @SerialName("agent")
    AGENT,
}

/** The purpose as a client receives it: the text and who wrote it. */
@Serializable
data class AuthoredPurpose(
    /** The agent's text. */
    val text: SecretPurpose,
    /** Always [TextAuthor.AGENT] in this contract; always encoded, so a client never has to assume it. */
    val author: TextAuthor,
)

/** The lifecycle state of a request. Terminal states never change again. */
@Serializable
enum class SecretRequestState {
    /** Waiting for the human; may be submitted, cancelled or expire. */
    @SerialName("pending")
    PENDING,

    /** A submission is being written; it ends saved or failed. Never shown to the agent. */
    @SerialName("submitting")
    SUBMITTING,

    /** The secret was written and verified. */
    @SerialName("saved")
    SAVED,

    /** The human declined. */
    @SerialName("cancelled")
    CANCELLED,

    /** Nobody answered before the request expired. */
    @SerialName("expired")
    EXPIRED,

    /** The write failed; nothing was left stored. */
    @SerialName("failed")
    FAILED,
    ;

    /** Whether the state is final. */
    val isTerminal: Boolean get() = this != PENDING && this != SUBMITTING
}

/** The status an agent's receipt reports: only terminal states. */
@Serializable
enum class SecretReceiptStatus {
    /** The secret was written and verified. */
    @SerialName("saved")
    SAVED,

    /** The human declined. */
    @SerialName("cancelled")
    CANCELLED,

    /** Nobody answered in time. */
    @SerialName("expired")
    EXPIRED,

    /** The write failed; see the receipt's reason. */
    @SerialName("failed")
    FAILED,
}

/**
 * Why a request failed: a fixed, non-secret code. Provider diagnostics, paths and request text
 * never appear in one.
 */
@Serializable
enum class SecretFailureReason {
    /** The keystore is locked. */
    @SerialName("vault_locked")
    VAULT_LOCKED,

    /** The keystore is unreachable or unhealthy. */
    @SerialName("vault_unavailable")
    VAULT_UNAVAILABLE,

    /** The keystore refused the operation. */
    @SerialName("vault_denied")
    VAULT_DENIED,

    /** The keystore cannot read, write, list or delete as this flow needs. */
    @SerialName("vault_unsupported")
    VAULT_UNSUPPORTED,

    /** The keystore failed unexpectedly. */
    @SerialName("vault_error")
    VAULT_ERROR,

    /** The target cannot be written without risking a partial store (see the contract). */
    @SerialName("target_not_writable")
    TARGET_NOT_WRITABLE,

    /** The keystore refused the value. */
    @SerialName("value_rejected")
    VALUE_REJECTED,

    /** The read-back did not match; the write was undone. */
    @SerialName("verify_failed")
    VERIFY_FAILED,

    /** The read-back did not match and undoing the write failed too. */
    @SerialName("rollback_failed")
    ROLLBACK_FAILED,

    /** The host stopped while the write was in progress. */
    @SerialName("interrupted")
    INTERRUPTED,

    /**
     * The slot held no value when the request was made but holds one now, so writing would replace
     * a value the person was never told about; nothing was written.
     */
    @SerialName("target_changed")
    TARGET_CHANGED,
}

/** What the agent learns about its request once it ends: never a value or a value's length. */
@Serializable
data class SecretRequestReceipt(
    /** The outcome. */
    val status: SecretReceiptStatus,
    /** The item the request targeted. */
    val itemId: VaultItemName,
    /** The field the request targeted. */
    val fieldName: VaultFieldName,
    /** Why it failed; present only when [status] is [SecretReceiptStatus.FAILED]. */
    val reason: SecretFailureReason? = null,
)

/** What the agent gets back when its request is accepted. */
@Serializable
data class SecretRequestCreated(
    /** The new request's id; the agent uses it to wait for the receipt. */
    val requestId: SecretRequestId,
    /** Always [SecretRequestState.PENDING]. */
    val status: SecretRequestState,
    /** When the request expires, in epoch milliseconds. */
    val expiresAtMs: Long,
)

/** Who asked, for what and where to: the fixed part of a request. */
@Serializable
data class SecretRequestDetails(
    /** The agent that made the request. */
    val requestingAgent: AgentId,
    /** Where the secret will be written. */
    val target: SecretRequestTarget,
    /** The agent's purpose text. */
    val purpose: AuthoredPurpose,
    /**
     * Whether the target slot already held a value when the host created the request, as the host
     * itself found out (never as the agent claimed). A client shows it as "this replaces an
     * existing value". Only such a request may replace a value; a slot that appears after the
     * request was made is never replaced ([SecretFailureReason.TARGET_CHANGED]). Never sent to the
     * agent. Omitted on the wire when false.
     */
    val replacesExisting: Boolean = false,
)

/** When a request was made and when it expires, in epoch milliseconds. */
@Serializable
data class SecretRequestTiming(
    /** When the host created the request. */
    val createdAtMs: Long,
    /** When a still-pending request expires. */
    val expiresAtMs: Long,
)

/** Where a request is in its lifecycle. */
@Serializable
data class SecretRequestStatus(
    /** The current state. */
    val state: SecretRequestState,
    /** Why it failed; present only for [SecretRequestState.FAILED]. */
    val reason: SecretFailureReason? = null,
    /** When it reached a terminal state, in epoch milliseconds. */
    val settledAtMs: Long? = null,
)

/**
 * One request as the journal stores it and the human's client sees it. It holds ids, the target,
 * the agent's purpose, timestamps and the state: never a secret value.
 */
@Serializable
data class SecretRequestRecord(
    /** The request id. */
    val requestId: SecretRequestId,
    /** Who asked, for what and where to. */
    val details: SecretRequestDetails,
    /** When it was made and when it expires. */
    val timing: SecretRequestTiming,
    /** Its lifecycle state. */
    val status: SecretRequestStatus,
) {
    /** The agent's receipt, or null while the request is not terminal. */
    fun receipt(): SecretRequestReceipt? {
        val receiptStatus = when (status.state) {
            SecretRequestState.SAVED -> SecretReceiptStatus.SAVED
            SecretRequestState.CANCELLED -> SecretReceiptStatus.CANCELLED
            SecretRequestState.EXPIRED -> SecretReceiptStatus.EXPIRED
            SecretRequestState.FAILED -> SecretReceiptStatus.FAILED
            SecretRequestState.PENDING, SecretRequestState.SUBMITTING -> return null
        }
        return SecretRequestReceipt(receiptStatus, details.target.item, details.target.field, status.reason)
    }
}
