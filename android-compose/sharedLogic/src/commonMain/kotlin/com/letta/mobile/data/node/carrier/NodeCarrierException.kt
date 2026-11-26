package com.letta.mobile.data.node.carrier

/** A carrier or session operation that could not complete; [failure] says why in a form callers can branch on. */
class NodeCarrierException(val failure: NodeCarrierFailure) : Exception(failure.toString())

/** Why a carrier or session operation failed. Callers map an unknown case to a generic error, never a crash. */
sealed interface NodeCarrierFailure {
    /** The node could not be reached, or the address is not one this carrier dials. [detail] is diagnostic text only. */
    data class Unreachable(val detail: String) : NodeCarrierFailure

    /** The link ended (or had ended) before the operation completed. */
    data class Closed(val closure: NodeLinkClosure) : NodeCarrierFailure

    /** The node answered the session's `auth` frame with `success: false`; [reason] is the node's fixed reason label. */
    data class AuthRejected(val reason: String) : NodeCarrierFailure

    /** The node answered an `admin_rpc` the session needed a result from with `success: false`. */
    data class Refused(val method: String, val error: String) : NodeCarrierFailure
}
