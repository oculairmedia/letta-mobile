package com.letta.mobile.ui.pairing.composition

import com.letta.mobile.data.pairing.composition.PairingComposition
import com.letta.mobile.data.pairing.composition.PairingPorts
import com.letta.mobile.ui.pairing.AgentChoice
import com.letta.mobile.ui.pairing.InstallAndPairBindings

/**
 * What the install-and-pair card needs from the platform's shell: the
 * roster, where a deep-linked pairing link waits, navigation to chat and the share action.
 * The pairing logic itself comes from [PairingPorts].
 */
interface PairingShellPorts {
    /** The agents an invite may be limited to. */
    val agents: List<AgentChoice>

    /** A deep-linked pairing link waiting to be redeemed; null when there is none. */
    val pendingLink: String?

    /** Clears [pendingLink] once the card has taken it. */
    fun consumePendingLink()

    fun openChat()

    /** Hands a pairing link to the platform's share target (share sheet, clipboard). */
    fun shareLink(link: String)
}

/** The install-and-pair card's bindings, built the same way on every client from its ports. */
fun installAndPairBindings(ports: PairingPorts, shell: PairingShellPorts): InstallAndPairBindings {
    val pairing = PairingComposition(ports)
    return InstallAndPairBindings(
        joinFlow = pairing::joinFlow,
        qrEncoder = pairing.qrEncoder,
        rpc = pairing.adminRpc,
        agents = shell.agents,
        onOpenChat = shell::openChat,
        nowMs = pairing::nowMs,
        pendingLink = shell.pendingLink,
        onPendingLinkConsumed = shell::consumePendingLink,
        selfNodeId = pairing::selfNodeId,
        onShareLink = shell::shareLink,
    )
}
