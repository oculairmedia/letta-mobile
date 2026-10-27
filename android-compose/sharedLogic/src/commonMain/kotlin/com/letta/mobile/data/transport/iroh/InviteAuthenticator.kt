package com.letta.mobile.data.transport.iroh

import com.letta.mobile.util.attr
import com.letta.mobile.data.pairing.PairedHost
import com.letta.mobile.data.pairing.PairingError
import com.letta.mobile.data.pairing.RedeemOutcome
import com.letta.mobile.data.pairing.ScannedInvite
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** One invite presentation: where to dial and what to say (the token is secret). */
class InviteAuthRequest(val dialAddress: String, val inviteToken: String, val peerName: String) {
    override fun toString(): String = "InviteAuthRequest(dial=$dialAddress, <redacted>)"
}

/** Network seam: dial with [secretKey] and send the auth frame; returns the host's answer. */
fun interface InviteAuthenticator {
    suspend fun authenticate(secretKey: ByteArray, request: InviteAuthRequest): AppServerInboundFrame.AuthResponse
}

/**
 * What presenting an invite means on every transport: one bounded exchange, [exchange] (loading the
 * identity and dialling included), within [timeoutMs]. Failing to reach the host is
 * [PairingError.HOST_UNREACHABLE]; the host's answer maps through [inviteOutcomeOf] for [host].
 */
internal suspend fun presentInvite(
    invite: ScannedInvite,
    host: PairedHost,
    timeoutMs: Long,
    exchange: suspend () -> AppServerInboundFrame.AuthResponse,
): RedeemOutcome {
    val response = runCatching { withTimeout(timeoutMs) { exchange() } }.getOrElse { failure ->
        when (failure) {
            is TimeoutCancellationException -> Telemetry.event(PAIRING_TAG, "redeem.timeout", "host" attr invite.hostFingerprint)
            is CancellationException, !is Exception -> throw failure
            else -> Telemetry.event(
                PAIRING_TAG,
                "redeem.unreachable",
                "host" attr invite.hostFingerprint,
                "class" attr failure::class.simpleName,
            )
        }
        return RedeemOutcome.Failed(PairingError.HOST_UNREACHABLE)
    }
    return inviteOutcomeOf(response, host)
}

/** Map the host's fixed reason labels (never secret-derived) to user-facing errors. */
internal fun inviteOutcomeOf(response: AppServerInboundFrame.AuthResponse, host: PairedHost): RedeemOutcome {
    if (response.success) return RedeemOutcome.Paired(host, alreadyPaired = response.alreadyPaired == true)
    val error = when (response.error) {
        "invite_expired" -> PairingError.EXPIRED
        "invite_invalid" -> PairingError.INVITE_INVALID
        "rate_limited" -> PairingError.RATE_LIMITED
        else -> PairingError.DENIED
    }
    Telemetry.event(PAIRING_TAG, "redeem.rejected", "reason" attr error.name)
    return RedeemOutcome.Failed(error)
}

private const val PAIRING_TAG = "IrohPairing"
