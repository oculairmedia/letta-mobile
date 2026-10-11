package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Authentication + authorization state of ONE Iroh App Server connection
 * (letta-mobile-dpv7m.1), separated from the QUIC plumbing in
 * [IrohNodeConnection] so every fail-closed decision is unit-testable.
 *
 * Decision order for an auth frame:
 *  1. `invite:<secret>` — one-time redemption (rate limited with bearer guesses);
 *     a NodeId that is already paired authenticates its binding instead and
 *     leaves the invite for the device it was meant for.
 *  2. A paired NodeId authenticates by transport identity alone (the QUIC
 *     handshake proved the key).
 *  3. Otherwise the configured bootstrap allowance: bearer token, static
 *     allowlist, or explicit anonymous test mode. With none configured
 *     ([IrohAuthPolicy.PairedPeersOnly]) the peer is denied.
 *
 * Grants: a paired session gets ONLY its stored record's capabilities and
 * agent scope, re-read on every check so re-scoping applies live; if the
 * record disappears (revoked) the session is denied and torn down — it is
 * never widened to the bootstrap admin role. Bootstrap sessions keep the
 * legacy admin role (they exist only when the operator configured them).
 */
internal class PeerSessionAuth(
    private val policy: IrohAuthPolicy,
    private val verifier: IrohBearerAuthVerifier,
    private val pairing: IrohPairingService?,
    private val remoteId: String,
    /** Invoked once when a paired session loses its record (close the connection). */
    private val onRevoked: () -> Unit = {},
) {
    sealed interface Decision {
        data class Accepted(val method: String, val newlyPaired: Boolean = false, val alreadyPaired: Boolean = false) : Decision

        /** [outcome] is [IrohBearerAuthVerifier.Outcome.Denied] or [IrohBearerAuthVerifier.Outcome.RateLimited]. */
        data class Rejected(val outcome: IrohBearerAuthVerifier.Outcome) : Decision
    }

    private val initiallyPaired = pairing?.isPaired(remoteId) == true
    private val pairedSession = AtomicBoolean(initiallyPaired)
    private val revoked = AtomicBoolean(false)

    /**
     * Shared with the admin_rpc stream server. Pre-set only when the policy
     * vouches for THIS NodeId (anonymous test mode, static allowlist) or the
     * NodeId is paired — never merely because no token is configured.
     */
    val authenticated = AtomicBoolean(policy.preAuthenticates(remoteId) || initiallyPaired)

    fun authenticate(token: String?, peerName: String?): Decision {
        if (pairing != null && token != null && token.startsWith(IrohPairingService.INVITE_TOKEN_PREFIX)) {
            return redeem(pairing, token.removePrefix(IrohPairingService.INVITE_TOKEN_PREFIX), peerName)
        }
        if (pairing?.isPaired(remoteId) == true) return acceptPaired(alreadyPaired = false)
        return when (val outcome = verifier.verify(remoteId, token)) {
            IrohBearerAuthVerifier.Outcome.Authenticated -> {
                pairedSession.set(false)
                accept(Decision.Accepted(method = "bootstrap"))
            }
            else -> reject(outcome)
        }
    }

    private fun redeem(pairing: IrohPairingService, secret: String, peerName: String?): Decision {
        // Invite guessing shares the NodeId's failure budget with bearer guessing.
        if (verifier.isRateLimited(remoteId)) return reject(IrohBearerAuthVerifier.Outcome.RateLimited)
        return when (val result = pairing.redeem(secret, remoteId, peerName)) {
            is IrohPairingService.RedeemResult.Paired -> {
                pairedSession.set(true)
                accept(Decision.Accepted(method = "invite_redeemed", newlyPaired = true))
            }
            is IrohPairingService.RedeemResult.AlreadyPaired -> acceptPaired(alreadyPaired = true)
            is IrohPairingService.RedeemResult.Rejected -> reject(verifier.deny(remoteId, "invite_${result.reason}"))
        }
    }

    private fun acceptPaired(alreadyPaired: Boolean): Decision {
        pairedSession.set(true)
        runCatching { pairing?.touch(remoteId) }
        return accept(Decision.Accepted(method = "paired_node_id", alreadyPaired = alreadyPaired))
    }

    private fun accept(decision: Decision.Accepted): Decision {
        revoked.set(false)
        authenticated.set(true)
        return decision
    }

    private fun reject(outcome: IrohBearerAuthVerifier.Outcome): Decision {
        authenticated.set(false)
        return Decision.Rejected(outcome)
    }

    /** The peer's current grants; empty when unauthenticated or revoked. */
    fun effectiveCapabilities(): Set<String> {
        if (!authenticated.get()) return emptySet()
        val record = pairing?.peer(remoteId)
        return when {
            record != null -> record.capabilities
            pairedSession.get() -> {
                revoke()
                emptySet()
            }
            else -> setOf(IrohPeerCapabilities.ADMIN_FULL)
        }
    }

    /** Agents this session may address; null = every agent (bootstrap sessions, unscoped peers). */
    fun agentScope(): Set<String>? =
        if (pairedSession.get()) pairing?.peer(remoteId)?.agentIds else null

    /** Deauthenticate after the pairing was revoked; [onRevoked] runs once. */
    fun revoke() {
        authenticated.set(false)
        if (revoked.compareAndSet(false, true)) {
            Telemetry.event("IrohNode", "auth.revoked", "remoteEndpointId" to remoteId)
            runCatching { onRevoked() }
        }
    }
}
