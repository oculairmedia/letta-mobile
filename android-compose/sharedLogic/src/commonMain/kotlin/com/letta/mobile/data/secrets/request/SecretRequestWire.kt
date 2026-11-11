package com.letta.mobile.data.secrets.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The method names of the secret request contract (`docs/reference/contracts/secret-request-v1.md`). */
object SecretRequestMethods {
    /** Agent: create a request. */
    const val REQUEST: String = "secret.request"

    /** Agent: the receipt of one of its own requests, absent while it is open. */
    const val STATUS: String = "secret.request.status"

    /** Human: every request the host holds. */
    const val LIST: String = "secret.request.list"

    /** Human: one request. */
    const val GET: String = "secret.request.get"

    /** Human: answer a request with a value; the only call that carries one. */
    const val SUBMIT: String = "secret.request.submit"

    /** Human: decline a request. */
    const val CANCEL: String = "secret.request.cancel"
}

/** Why a call was refused. Stable wire codes; none carries request text or a value. */
@Serializable
enum class SecretServiceError {
    /** The agent's request breaks the grammar or limits; the refusal names the problem. */
    @SerialName("invalid_request")
    INVALID_REQUEST,

    /** The caller is not the human principal (not a paired device with `admin.full`, or an agent origin). */
    @SerialName("forbidden")
    FORBIDDEN,

    /**
     * The request cannot be answered: unknown, pruned, expired, already answered or being written.
     * All of these answer the same code, and a refused submission writes nothing.
     */
    @SerialName("not_pending")
    NOT_PENDING,

    /** The submitted value is empty or larger than [SecretRequestLimits.MAX_VALUE_BYTES]; the request stays pending. */
    @SerialName("invalid_value")
    INVALID_VALUE,

    /** No request with that id belongs to the asking caller (or none exists at all; the two are not told apart). */
    @SerialName("unknown_request")
    UNKNOWN_REQUEST,

    /** The journal cannot be read or written; nothing was written to the keystore by this call. */
    @SerialName("journal_unavailable")
    JOURNAL_UNAVAILABLE,
}

/** The body of a refused call: the code and, for [SecretServiceError.INVALID_REQUEST], the problem. */
@Serializable
data class SecretRequestRefusal(
    /** Why the call was refused. */
    val error: SecretServiceError,
    /** What is wrong with the agent's request, for [SecretServiceError.INVALID_REQUEST] only. */
    val problem: SecretRequestProblem? = null,
)

/** The params of the calls that name one request (status, get, cancel). */
@Serializable
data class SecretRequestRef(
    /** The request. */
    val requestId: SecretRequestId,
)

/** The list a human's client receives: every request the journal still holds, newest first. */
@Serializable
data class SecretRequestList(
    /** The requests. */
    val requests: List<SecretRequestRecord>,
)

/** The result of status, submit and cancel: the agent's receipt, absent while the request is open. */
@Serializable
data class SecretRequestReceiptResult(
    /** The receipt, or null (omitted on the wire) while the request is still open. */
    val receipt: SecretRequestReceipt? = null,
)
