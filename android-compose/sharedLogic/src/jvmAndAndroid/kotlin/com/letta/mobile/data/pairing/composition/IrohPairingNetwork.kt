package com.letta.mobile.data.pairing.composition

import com.letta.mobile.data.controller.node.iroh.IrohNodeIdentity
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore
import com.letta.mobile.data.pairing.PairingRedeemer
import com.letta.mobile.data.transport.iroh.InviteAuthenticator
import com.letta.mobile.data.transport.iroh.IrohPairingRedeemer
import com.letta.mobile.data.transport.iroh.LiveInviteAuthenticator

/**
 * Pairing over a native Iroh endpoint (JVM and Android): invites are
 * presented by [IrohPairingRedeemer] through [authenticator], the network seam.
 */
class IrohPairingNetwork(
    private val authenticator: InviteAuthenticator = LiveInviteAuthenticator(),
) : PairingNetwork {
    override fun redeemer(identityKey: suspend () -> ByteArray): PairingRedeemer =
        IrohPairingRedeemer(IrohSecretKeyStore { identityKey() }, authenticator)

    override fun nodeIdOf(secretKey: ByteArray): String = IrohNodeIdentity.nodeIdHexFromSecretBytes(secretKey)
}
