package com.letta.mobile.data.transport.iroh

/**
 * Shared admin_rpc error vocabulary so clients and probes can detect
 * capability-unaware / unregistered methods without matching ad-hoc strings.
 */
object AdminRpcErrors {
    const val UNKNOWN_METHOD_PREFIX = "Unknown method"

    fun unknownMethod(method: String): String = "$UNKNOWN_METHOD_PREFIX: $method"

    fun isUnknownMethod(error: String?): Boolean =
        error?.contains(UNKNOWN_METHOD_PREFIX, ignoreCase = true) == true
}
