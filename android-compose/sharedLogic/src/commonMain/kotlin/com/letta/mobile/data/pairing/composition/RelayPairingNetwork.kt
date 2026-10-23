package com.letta.mobile.data.pairing.composition

import com.letta.mobile.data.pairing.PairedHost
import com.letta.mobile.data.pairing.PairingRedeemer
import com.letta.mobile.data.pairing.RedeemOutcome
import com.letta.mobile.data.pairing.ScannedInvite
import com.letta.mobile.data.transport.iroh.InviteAuthRequest
import com.letta.mobile.data.transport.iroh.InviteAuthenticator
import com.letta.mobile.data.transport.iroh.presentInvite

/**
 * Pairing for a device that reaches hosts only through the Iroh relay (the browser, which has no
 * direct UDP). Invites are presented through [authenticator], the same network seam the JVM and
 * Android [PairingNetwork] injects; [nodeIds] derives a NodeId from a key on this device's Iroh
 * binding.
 */
class RelayPairingNetwork(
    private val authenticator: InviteAuthenticator,
    private val nodeIds: (secretKey: ByteArray) -> String,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : PairingNetwork {
    override fun redeemer(identityKey: suspend () -> ByteArray): PairingRedeemer =
        RelayPairingRedeemer(identityKey, authenticator, timeoutMs)

    override fun nodeIdOf(secretKey: ByteArray): String = nodeIds(secretKey)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}

/**
 * Presents an invite by the envelope's NodeId alone: the link's host:port hint is never dialled,
 * so a tampered hint cannot redirect the dial, and QUIC proves the host holds the envelope's key.
 * The device dials with its PERSISTENT identity ([identityKey]), the one its chat session
 * reconnects with, so the paired host is saved as `iroh://<nodeId>`.
 */
internal class RelayPairingRedeemer(
    private val identityKey: suspend () -> ByteArray,
    private val authenticator: InviteAuthenticator,
    private val timeoutMs: Long,
) : PairingRedeemer {
    override suspend fun redeem(invite: ScannedInvite, deviceName: String): RedeemOutcome {
        val request = InviteAuthRequest(invite.nodeId, invite.inviteToken, deviceName)
        return presentInvite(invite, PairedHost(invite.nodeId, invite.nodeId), timeoutMs) {
            authenticator.authenticate(identityKey(), request)
        }
    }
}
