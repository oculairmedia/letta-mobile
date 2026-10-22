package com.letta.mobile.data.pairing

import com.letta.mobile.data.model.IrohServerAddress
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame

/** Network seam for [RelayPairingRedeemer]: dial [address], send [inviteToken] in the auth frame, return the answer. */
fun interface RelayInviteAuthenticator {
    suspend fun authenticate(address: IrohServerAddress, inviteToken: String, peerName: String): AppServerInboundFrame.AuthResponse
}

/**
 * Presents a scanned invite to its host by the envelope's NodeId alone, for a device that can only
 * reach a host through the relay (a browser has no direct UDP). The link's host:port hint is never
 * used, so a tampered hint cannot redirect the dial; QUIC proves the host holds the envelope's key.
 * The device dials with its persistent identity, the one its chat session reconnects with.
 */
class RelayPairingRedeemer(
    private val authenticator: RelayInviteAuthenticator,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : PairingRedeemer {
    override suspend fun redeem(invite: ScannedInvite, deviceName: String): RedeemOutcome {
        val address = runCatching { IrohServerAddress(IrohServerAddress.parse(invite.nodeId).endpointIdHex) }.getOrNull()
            ?: return RedeemOutcome.Failed(PairingError.MALFORMED)
        val host = PairedHost(address.endpointIdHex, address.endpointIdHex)
        return PairingAuthOutcome.present(invite, host, timeoutMs) {
            authenticator.authenticate(address, invite.inviteToken, deviceName)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
