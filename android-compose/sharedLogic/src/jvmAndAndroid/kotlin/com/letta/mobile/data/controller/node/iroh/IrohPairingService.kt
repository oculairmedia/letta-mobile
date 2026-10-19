package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One-time pairing invitations that bind a persistent NodeId
 * (scoped and persisted).
 *
 * The server mints a high-entropy single-use invite (nonce + expiry) that also
 * carries the grant the redeemer receives: a capability set (default
 * [IrohPeerCapabilities.CHAT_ROLE], never admin) and an optional agent scope.
 * The connecting peer proves possession of its Iroh private key implicitly via
 * the QUIC handshake, then presents the invite secret in its auth frame
 * (`invite:<secret>`). Redemption is single-winner: the invite is consumed
 * atomically from the [PairingInviteStore] before the NodeId binding is
 * persisted, so a concurrent second redemption — or any replay — fails.
 * Only a hash of each secret is stored; secrets never appear in telemetry or
 * errors.
 */
class IrohPairingService(
    private val store: PairedPeerStore,
    private val invites: PairingInviteStore = InMemoryPairingInviteStore(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom(),
) {
    /** What the inviter grants the device that redeems the invite. */
    data class InviteRequest(
        val suggestedName: String,
        val ttlMs: Long = DEFAULT_TTL_MS,
        val capabilities: Set<String> = IrohPeerCapabilities.CHAT_ROLE,
        val agentIds: Set<String>? = null,
    )

    data class PairingInvite(
        val secret: String,
        val suggestedName: String,
        val expiresAtMs: Long,
        val id: InviteId = PendingPairingInvite.idFor(secret),
        val capabilities: Set<String> = IrohPeerCapabilities.CHAT_ROLE,
        val agentIds: Set<String>? = null,
    ) {
        /** Deep-link form for QR encoding; contains the secret — never log it. */
        fun deepLink(): String = "letta-pair://invite/$secret"

        override fun toString(): String = "PairingInvite(id=$id, <redacted>, expiresAtMs=$expiresAtMs)"
    }

    sealed interface RedeemResult {
        data class Paired(val peer: PairedPeer) : RedeemResult

        /**
         * The NodeId is already paired. The invite is NOT consumed (it stays
         * usable by the device it was meant for) and the caller authenticates
         * the existing binding instead.
         */
        data class AlreadyPaired(val peer: PairedPeer) : RedeemResult

        /** Fixed reason label: invalid | expired. Never the secret. */
        data class Rejected(val reason: String) : RedeemResult
    }

    private val revocationListeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** Backward-compatible mint with the default (chat) grant. */
    fun createInvite(suggestedName: String, ttlMs: Long = DEFAULT_TTL_MS): PairingInvite =
        createInvite(InviteRequest(suggestedName = suggestedName, ttlMs = ttlMs))

    fun createInvite(request: InviteRequest): PairingInvite {
        val unknown = request.capabilities - IrohPeerCapabilities.ALL
        require(unknown.isEmpty()) { "unknown capabilities: ${unknown.sorted().joinToString(",")}" }
        require(request.capabilities.isNotEmpty()) { "an invite must grant at least one capability" }
        require(request.agentIds == null || request.agentIds.isNotEmpty()) {
            "agent_ids must name at least one agent (omit it for every agent)"
        }
        val secret = ByteArray(SECRET_BYTES).also(random::nextBytes)
            .joinToString("") { "%02x".format(it) }
        val createdAt = nowMs()
        val invite = PendingPairingInvite(
            id = PendingPairingInvite.idFor(secret),
            secretHash = PendingPairingInvite.secretHash(secret),
            suggestedName = request.suggestedName,
            createdAtMs = createdAt,
            expiresAtMs = createdAt + effectiveTtl(request.ttlMs),
            capabilities = request.capabilities,
            agentIds = request.agentIds,
        )
        invites.add(invite)
        Telemetry.event(
            "IrohPairing", "invite.created",
            "inviteId" to invite.id.value,
            "expiresAtMs" to invite.expiresAtMs,
            "capabilities" to invite.capabilities.sorted().joinToString(","),
            "agentScoped" to (invite.agentIds != null),
        )
        return PairingInvite(
            secret = secret,
            suggestedName = invite.suggestedName,
            expiresAtMs = invite.expiresAtMs,
            id = invite.id,
            capabilities = invite.capabilities,
            agentIds = invite.agentIds,
        )
    }

    fun isPaired(nodeId: String): Boolean = store.isPaired(nodeId)

    fun peer(nodeId: String): PairedPeer? = store.get(nodeId)

    fun redeem(secret: String, nodeId: String, name: String? = null): RedeemResult {
        store.get(nodeId)?.let { existing ->
            Telemetry.event("IrohPairing", "redeem.already_paired", "nodeId" to nodeId)
            return RedeemResult.AlreadyPaired(existing)
        }
        // Consume atomically: exactly one caller wins a given invite.
        val invite = invites.consume(PendingPairingInvite.secretHash(secret))
        if (invite == null) {
            Telemetry.event("IrohPairing", "redeem.rejected", "reason" to "invalid_or_replayed")
            return RedeemResult.Rejected("invalid")
        }
        if (nowMs() >= invite.expiresAtMs) {
            Telemetry.event("IrohPairing", "redeem.rejected", "reason" to "expired", "inviteId" to invite.id.value)
            return RedeemResult.Rejected("expired")
        }
        val peer = PairedPeer(
            nodeId = nodeId,
            name = name?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_NAME_CHARS) ?: invite.suggestedName,
            pairedAtMs = nowMs(),
            capabilities = invite.capabilities,
            agentIds = invite.agentIds,
            lastSeenAtMs = nowMs(),
        )
        store.save(peer)
        Telemetry.event(
            "IrohPairing", "peer.paired",
            "nodeId" to nodeId,
            "name" to peer.name,
            "inviteId" to invite.id.value,
            "capabilities" to peer.capabilities.sorted().joinToString(","),
        )
        return RedeemResult.Paired(peer)
    }

    fun listPeers(): List<PairedPeer> = store.list()

    /** Unredeemed, unexpired invites — non-secret metadata only. */
    fun listInvites(): List<PendingPairingInvite> {
        invites.pruneExpired(nowMs())
        return invites.list()
    }

    /** Revoke an unredeemed invite by its non-secret id (protocol §11). */
    fun revokeInvite(id: InviteId): Boolean {
        val removed = invites.remove(id)
        if (removed) Telemetry.event("IrohPairing", "invite.revoked", "inviteId" to id.value)
        return removed
    }

    /**
     * Record a successful authentication for the inventory's "last used"
     * column. Throttled so a reconnect storm does not rewrite the store.
     */
    fun touch(nodeId: String) {
        val existing = store.get(nodeId) ?: return
        val now = nowMs()
        val last = existing.lastSeenAtMs
        if (last != null && now - last < LAST_SEEN_WRITE_INTERVAL_MS) return
        store.save(existing.copy(lastSeenAtMs = now))
    }

    /**
     * Rename an already-paired peer ( inventory UX). Returns the updated
     * record, or null when the NodeId is not paired. The NodeId binding and
     * capabilities are untouched.
     */
    fun rename(nodeId: String, name: String): PairedPeer? {
        val existing = store.get(nodeId) ?: return null
        val updated = existing.copy(name = name)
        store.save(updated)
        Telemetry.event("IrohPairing", "peer.renamed", "nodeId" to nodeId, "name" to name)
        return updated
    }

    /**
     * Replace an already-paired peer's capability grants. Returns the
     * updated record, or null when the NodeId is not paired. Capability names
     * are validated by the caller (handler) against [IrohPeerCapabilities.ALL];
     * this preserves the NodeId binding and paired-at time. Takes effect on the
     * peer's live sessions immediately (grants are re-read per request).
     */
    fun setCapabilities(nodeId: String, capabilities: Set<String>): PairedPeer? {
        val existing = store.get(nodeId) ?: return null
        val updated = existing.copy(capabilities = capabilities)
        store.save(updated)
        Telemetry.event("IrohPairing", "peer.capabilities_set", "nodeId" to nodeId)
        return updated
    }

    /** Replace a paired peer's agent scope (null = every agent). */
    fun setAgentScope(nodeId: String, agentIds: Set<String>?): PairedPeer? {
        val existing = store.get(nodeId) ?: return null
        val updated = existing.copy(agentIds = agentIds)
        store.save(updated)
        Telemetry.event("IrohPairing", "peer.agents_set", "nodeId" to nodeId, "agentScoped" to (agentIds != null))
        return updated
    }

    /**
     * Remove the binding and notify listeners so live sessions of that NodeId
     * are closed — revocation must not wait for the peer to redial.
     */
    fun revoke(nodeId: String): Boolean {
        val removed = store.remove(nodeId)
        if (removed) {
            Telemetry.event("IrohPairing", "peer.revoked", "nodeId" to nodeId)
            revocationListeners.forEach { listener -> runCatching { listener(nodeId) } }
        }
        return removed
    }

    /** Called with the NodeId after every successful [revoke]. */
    fun addRevocationListener(listener: (String) -> Unit) {
        revocationListeners += listener
    }

    /** Prune expired invites so abandoned secrets do not accumulate. */
    fun pruneExpired() {
        invites.pruneExpired(nowMs())
    }

    companion object {
        /** Protocol default (docs/reference/qr-pairing-protocol.md §6.2): five minutes. */
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L

        /** Ceiling on a requested lifetime; an invite is a short-lived enrollment secret. */
        const val MAX_TTL_MS = 60 * 60 * 1000L
        const val SECRET_BYTES = 32
        private const val MAX_NAME_CHARS = 64
        private const val LAST_SEEN_WRITE_INTERVAL_MS = 60_000L

        /** Auth-frame prefix marking an invite redemption instead of a bearer token. */
        const val INVITE_TOKEN_PREFIX = "invite:"

        /** Non-positive requests get the default; longer requests are capped, never extended. */
        fun effectiveTtl(requestedMs: Long): Long =
            if (requestedMs <= 0) DEFAULT_TTL_MS else requestedMs.coerceAtMost(MAX_TTL_MS)
    }
}
