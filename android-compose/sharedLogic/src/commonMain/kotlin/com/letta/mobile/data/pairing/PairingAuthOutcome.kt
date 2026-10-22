package com.letta.mobile.data.pairing

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * The host's answer to an invite, as a [RedeemOutcome]: one mapping every [PairingRedeemer] shares
 * (native over the Iroh FFI, the browser over its wasm binding), so a reason label and a dead host
 * mean the same thing on every platform.
 */
object PairingAuthOutcome {
    /**
     * Presents [invite] through [authenticate] (one dial and auth exchange) and reads the answer for
     * [host]. A dial that fails or does not answer within [timeoutMs] is an unreachable host;
     * cancellation of the caller is passed on.
     */
    suspend fun present(
        invite: ScannedInvite,
        host: PairedHost,
        timeoutMs: Long,
        authenticate: suspend () -> AppServerInboundFrame.AuthResponse,
    ): RedeemOutcome {
        val response = runCatching { withTimeout(timeoutMs) { authenticate() } }.getOrElse { failure ->
            when (failure) {
                is TimeoutCancellationException -> Telemetry.event("IrohPairing", "redeem.timeout", "host" to invite.hostFingerprint)
                is CancellationException, is Error -> throw failure
                else -> Telemetry.event(
                    "IrohPairing",
                    "redeem.unreachable",
                    "host" to invite.hostFingerprint,
                    "class" to failure::class.simpleName,
                )
            }
            return RedeemOutcome.Failed(PairingError.HOST_UNREACHABLE)
        }
        return of(response, host)
    }

    /** Map the host's fixed reason labels (never secret-derived) to user-facing errors. */
    fun of(response: AppServerInboundFrame.AuthResponse, host: PairedHost): RedeemOutcome {
        if (response.success) return RedeemOutcome.Paired(host, alreadyPaired = response.alreadyPaired == true)
        val error = when (response.error) {
            "invite_expired" -> PairingError.EXPIRED
            "invite_invalid" -> PairingError.INVITE_INVALID
            "rate_limited" -> PairingError.RATE_LIMITED
            else -> PairingError.DENIED
        }
        Telemetry.event("IrohPairing", "redeem.rejected", "reason" to error.name)
        return RedeemOutcome.Failed(error)
    }
}
