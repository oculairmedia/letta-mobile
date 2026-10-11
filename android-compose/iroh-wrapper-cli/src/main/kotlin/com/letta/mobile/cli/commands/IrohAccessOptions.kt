package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.letta.mobile.data.controller.node.iroh.FileIrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.FixedIrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStores
import com.letta.mobile.data.controller.node.iroh.FilePairedPeerStore
import com.letta.mobile.data.controller.node.iroh.FilePairingInviteStore
import com.letta.mobile.data.controller.node.iroh.HmacPairQrSigner
import com.letta.mobile.data.controller.node.iroh.IrohAuthPolicy
import com.letta.mobile.data.controller.node.iroh.IrohAuthPolicyResolution
import com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.PairingQrConfig
import java.nio.file.Path

/**
 * Who may use this host's Iroh endpoint (d6e8g.2 / d6e8g.5 / letta-mobile-dpv7m.1):
 * the bootstrap allowance (bearer token, static allowlist, test-only anonymous
 * mode) and pairing (paired-peer store, shared pending-invite store, scannable
 * link). Grouped out of [AppServerServeIrohCommand] so access control is one
 * cohesive unit.
 *
 * Fail-closed: no token, no allowlist, no pairing store and no explicit
 * anonymous opt-in refuses to start. A pairing store on its own is a secure,
 * pairing-only configuration.
 */
class IrohAccessOptions : OptionGroup(name = "Iroh access control (d6e8g / dpv7m)") {
    private val authToken by option(
        "--auth-token",
        envvar = "LETTA_IROH_AUTH_TOKEN",
        help = "Optional BOOTSTRAP bearer token: a peer presenting it gets the full admin role. " +
            "With --pairing-store-file it is not required (paired NodeIds and one-time invites " +
            "authenticate); omit it to run fail-closed pairing-only.",
    )

    private val allowedPeerIds by option(
        "--allowed-peer-ids",
        envvar = "LETTA_IROH_ALLOWED_PEER_IDS",
        help = "Optional comma-separated allowlist of remote EndpointIds (64 hex chars).",
    ).default("")

    private val pairingStoreFile by option(
        "--pairing-store-file",
        envvar = "LETTA_IROH_PAIRING_STORE",
        help = "Path to the paired-peer JSON store (d6e8g.5). When set, paired NodeIds " +
            "authenticate without a token and one-time invites can be minted via " +
            "pair.invite.create or the 'pair' command; redeem with an 'invite:<secret>' auth " +
            "token. On its own this is a secure (pairing-only) configuration.",
    )

    private val pairingInviteStoreFile by option(
        "--pairing-invite-store-file",
        envvar = "LETTA_IROH_PAIRING_INVITE_STORE",
        help = "Pending-invite store shared with the 'pair' command (hashed secrets only). " +
            "Default: <pairing-store>.invites.json next to --pairing-store-file.",
    )

    private val publicDialAddress by option(
        "--public-dial-address",
        envvar = "LETTA_IROH_PUBLIC_DIAL",
        help = "Dial address embedded in scannable pairing links (an Iroh ticket or " +
            "<node-id>@host:port reachable from phones). Default: this endpoint's current ticket.",
    )

    private val allowInsecureAnonymousIroh by option(
        "--allow-insecure-anonymous-iroh",
        help = "TEST/DEV ONLY: run the Iroh endpoint with NO authentication. Every peer that " +
            "can dial the ticket gets full runtime and admin access. Prohibited for release " +
            "or long-running service use; a warning is printed on every start.",
    ).flag(default = false)

    fun resolvePolicy(): IrohAuthPolicyResolution = IrohAuthPolicy.resolve(
        authToken = authToken,
        allowedPeerIds = allowedPeerIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        allowInsecureAnonymous = allowInsecureAnonymousIroh,
        pairingEnabled = !pairingStoreFile.isNullOrBlank(),
    )

    /** The pairing service over the shared on-disk stores, or null when pairing is off. */
    fun pairingService(log: (String) -> Unit): IrohPairingService? {
        val peers = pairingStoreFile?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: return null
        val invites = PairingStorePaths.inviteStore(peers, pairingInviteStoreFile)
        log("[iroh-app-server] Pairing enabled (store: $peers, invites: $invites)")
        return IrohPairingService(FilePairedPeerStore(peers), FilePairingInviteStore(invites))
    }

    /**
     * QR invites name this endpoint's REAL NodeId and are HMAC-signed with its
     * identity key; the scannable link carries --public-dial-address or the
     * live ticket.
     */
    fun pairingQrConfig(endpoint: IrohNodeEndpoint, secretKeyPath: String?): PairingQrConfig? {
        if (pairingStoreFile.isNullOrBlank()) return null
        val keyStore = secretKeyPath?.let(::FileIrohSecretKeyStore)
            ?: FixedIrohSecretKeyStore(IrohSecretKeyStores.generateKeyBytes())
        return PairingQrConfig(HmacPairQrSigner(keyStore), endpoint.nodeIdHex()) {
            publicDialAddress?.takeIf { it.isNotBlank() } ?: runCatching { endpoint.ticketString() }.getOrNull()
        }
    }
}
