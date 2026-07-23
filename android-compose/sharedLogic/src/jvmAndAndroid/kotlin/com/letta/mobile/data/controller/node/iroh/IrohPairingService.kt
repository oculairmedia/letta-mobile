package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One-time pairing invitations that bind a persistent NodeId
 * (letta-mobile-d6e8g.5).
 *
 * The server mints a high-entropy single-use invite (nonce + expiry). The
 * connecting peer proves possession of its Iroh private key implicitly via the
 * QUIC handshake, then presents the invite secret in its auth frame
 * (`invite:<secret>`). Redemption is single-winner: the invite is consumed
 * atomically before the NodeId binding is persisted, so a concurrent second
 * redemption — or any replay — fails. Invite secrets never appear in
 * telemetry or errors.
 */
class IrohPairingService(
    private val store: PairedPeerStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom(),
) {
    data class PairingInvite(
        val secret: String,
        val suggestedName: String,
        val expiresAtMs: Long,
    ) {
        /** Deep-link form for QR encoding; contains the secret — never log it. */
        fun deepLink(): String = "letta-pair://invite/$secret"

        override fun toString(): String = "PairingInvite(<redacted>, expiresAtMs=$expiresAtMs)"
    }

    sealed interface RedeemResult {
        data class Paired(val peer: PairedPeer) : RedeemResult

        /** Fixed reason label: invalid | expired | already_redeemed. Never the secret. */
        data class Rejected(val reason: String) : RedeemResult
    }

    private data class PendingInvite(val suggestedName: String, val expiresAtMs: Long)

    private val pending = mutableMapOf<String, PendingInvite>()
    private val lock = Any()

    fun createInvite(suggestedName: String, ttlMs: Long = DEFAULT_TTL_MS): PairingInvite {
        val secret = ByteArray(SECRET_BYTES).also(random::nextBytes)
            .joinToString("") { "%02x".format(it) }
        val expiresAt = nowMs() + ttlMs
        synchronized(lock) {
            pending[secret] = PendingInvite(suggestedName, expiresAt)
        }
        Telemetry.event("IrohPairing", "invite.created", "expiresAtMs" to expiresAt)
        return PairingInvite(secret = secret, suggestedName = suggestedName, expiresAtMs = expiresAt)
    }

    fun isPaired(nodeId: String): Boolean = store.isPaired(nodeId)

    fun redeem(secret: String, nodeId: String, name: String? = null): RedeemResult {
        // Consume atomically: exactly one caller wins a given invite.
        val invite = synchronized(lock) {
            val match = pending.keys.firstOrNull { constantTimeEquals(it, secret) }
            match?.let { pending.remove(it) }
        }
        if (invite == null) {
            Telemetry.event("IrohPairing", "redeem.rejected", "reason" to "invalid_or_replayed")
            return RedeemResult.Rejected("invalid")
        }
        if (nowMs() > invite.expiresAtMs) {
            Telemetry.event("IrohPairing", "redeem.rejected", "reason" to "expired")
            return RedeemResult.Rejected("expired")
        }
        val peer = PairedPeer(
            nodeId = nodeId,
            name = name?.takeIf { it.isNotBlank() } ?: invite.suggestedName,
            pairedAtMs = nowMs(),
        )
        store.save(peer)
        Telemetry.event("IrohPairing", "peer.paired", "nodeId" to nodeId, "name" to peer.name)
        return RedeemResult.Paired(peer)
    }

    fun listPeers(): List<PairedPeer> = store.list()

    fun revoke(nodeId: String): Boolean {
        val removed = store.remove(nodeId)
        if (removed) Telemetry.event("IrohPairing", "peer.revoked", "nodeId" to nodeId)
        return removed
    }

    /** Prune expired invites so abandoned secrets do not accumulate. */
    fun pruneExpired() {
        val cutoff = nowMs()
        synchronized(lock) {
            pending.entries.removeIf { it.value.expiresAtMs < cutoff }
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.encodeToByteArray(), b.encodeToByteArray())

    companion object {
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L
        const val SECRET_BYTES = 32

        /** Auth-frame prefix marking an invite redemption instead of a bearer token. */
        const val INVITE_TOKEN_PREFIX = "invite:"
    }
}
