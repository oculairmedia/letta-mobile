package com.letta.mobile.data.controller

import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope

/**
 * letta-mobile-qygvv.5: one approval decision to send as an `approval_response` input, answering
 * the pending `control_request` [approvalRequestId] on [runtime]. [source] tags telemetry.
 *
 * letta-mobile-qygvv.13: [answersStreamDelta] is true when the decision answers an
 * `approval_request_message` stream delta (informational) rather than a real
 * `control_request`; the server's "no longer pending" reply to such a decision means
 * the server already resolved the approval itself, not that the decision was refused.
 * [toolName] is telemetry only.
 */
data class ApprovalSubmission(
    val runtime: AppServerRuntimeScope,
    val approvalRequestId: String,
    val decision: AppServerApprovalResponseDecision,
    val source: String = "submit",
    val answersStreamDelta: Boolean = false,
    val toolName: String? = null,
)

/**
 * letta-mobile-qygvv.5: what the App Server said about one `approval_response` input.
 *
 * Approval responses carry a `request_id` and wait for `input_accepted`, so a
 * decision that missed its gate ("Approval request is no longer pending") is
 * reported to the caller instead of vanishing while the turn stays parked.
 */
sealed interface ApprovalSubmitResult {
    /** The server applied the decision (`accepted=true`, started or queued). */
    data object Accepted : ApprovalSubmitResult

    /** `accepted=false`: the server refused the decision; [error] is its text. */
    data class Rejected(val error: String) : ApprovalSubmitResult

    /**
     * No ack to act on: the client cannot correlate `input_accepted`, the ack timed
     * out, or the connection dropped first. The decision stays cached so a server
     * replay of the same request is answered again.
     */
    data class Unacknowledged(val reason: String) : ApprovalSubmitResult
}

/** Thrown by callers whose API has no result type when the server rejected the decision. */
class ApprovalRejectedException(val error: String) : IllegalStateException("Approval was rejected: $error")
