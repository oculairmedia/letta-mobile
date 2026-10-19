package com.letta.mobile.data.controller.node.iroh

import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Non-secret handle of a pending invite (safe to list, log, and revoke by). */
@Serializable
@JvmInline
value class InviteId(val value: String) {
    override fun toString(): String = value
}

/** Domain-separated SHA-256 of an invite secret; the only form a secret is stored in. */
@Serializable
@JvmInline
value class InviteSecretHash(val value: String) {
    override fun toString(): String = "InviteSecretHash(<redacted>)"
}

/**
 * An unredeemed pairing invitation. Holds only the
 * SHA-256 of the secret, never the secret itself, so the store file (and any
 * backup of it) cannot be replayed into a pairing.
 *
 * [id] is a non-secret handle (a prefix of a second, domain-separated hash)
 * for listing and revoking an invite before it is redeemed (protocol §6.3/§11).
 */
@Serializable
data class PendingPairingInvite(
    val id: InviteId,
    val secretHash: InviteSecretHash,
    val suggestedName: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val capabilities: Set<String>,
    val agentIds: Set<String>? = null,
) {
    override fun toString(): String =
        "PendingPairingInvite(id=$id, name=$suggestedName, expiresAtMs=$expiresAtMs)"

    companion object {
        fun secretHash(secret: String): InviteSecretHash = InviteSecretHash(sha256Hex("letta-invite-secret:$secret"))

        fun idFor(secret: String): InviteId = InviteId(sha256Hex("letta-invite-id:$secret").take(ID_HEX_CHARS))

        private const val ID_HEX_CHARS = 16

        private fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Pending-invite persistence. [consume] is the single-winner redemption
 * primitive: it removes and returns the invite matching [secretHash] (compared
 * in constant time) atomically, so a replay or a concurrent second scanner
 * finds nothing.
 */
interface PairingInviteStore {
    fun add(invite: PendingPairingInvite)

    fun consume(secretHash: InviteSecretHash): PendingPairingInvite?

    fun list(): List<PendingPairingInvite>

    fun remove(id: InviteId): Boolean

    /** Drop invites that expired before [cutoffMs]; housekeeping, never a redemption path. */
    fun pruneExpired(cutoffMs: Long)
}

class InMemoryPairingInviteStore : PairingInviteStore {
    private val invites = mutableListOf<PendingPairingInvite>()

    @Synchronized override fun add(invite: PendingPairingInvite) {
        invites += invite
    }

    @Synchronized override fun consume(secretHash: InviteSecretHash): PendingPairingInvite? =
        invites.firstMatching(secretHash)?.also { invites.remove(it) }

    @Synchronized override fun list(): List<PendingPairingInvite> = invites.sortedBy { it.createdAtMs }

    @Synchronized override fun remove(id: InviteId): Boolean = invites.removeAll { it.id == id }

    @Synchronized override fun pruneExpired(cutoffMs: Long) {
        invites.removeAll { it.expiresAtMs < cutoffMs }
    }
}

/**
 * File-backed store shared by the serving wrapper and the host `pair` CLI
 * (separate processes): every read-modify-write runs under an OS file lock,
 * so an invite the CLI mints is visible to — and consumed exactly once by —
 * the server's redemption path.
 */
class FilePairingInviteStore(private val path: Path) : PairingInviteStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Synchronized override fun add(invite: PendingPairingInvite) = mutate { it + invite }

    @Synchronized override fun consume(secretHash: InviteSecretHash): PendingPairingInvite? {
        var consumed: PendingPairingInvite? = null
        mutate { current ->
            consumed = current.firstMatching(secretHash)
            consumed?.let { current - it } ?: current
        }
        return consumed
    }

    @Synchronized override fun list(): List<PendingPairingInvite> =
        AtomicJsonFile.withProcessLock(path) { load() }.sortedBy { it.createdAtMs }

    @Synchronized override fun remove(id: InviteId): Boolean {
        var removed = false
        mutate { current ->
            val kept = current.filterNot { it.id == id }
            removed = kept.size != current.size
            kept
        }
        return removed
    }

    @Synchronized override fun pruneExpired(cutoffMs: Long) = mutate { current ->
        current.filterNot { it.expiresAtMs < cutoffMs }
    }

    private fun mutate(change: (List<PendingPairingInvite>) -> List<PendingPairingInvite>) {
        AtomicJsonFile.withProcessLock(path) {
            val current = load()
            val next = change(current)
            if (next != current) AtomicJsonFile.write(path, json.encodeToString(next))
        }
    }

    private fun load(): List<PendingPairingInvite> {
        val text = AtomicJsonFile.readOrNull(path) ?: return emptyList()
        return json.decodeFromString<List<PendingPairingInvite>>(text)
    }

    companion object {
        /** Default location next to the paired-peer store: `<store>.invites.json`. */
        fun besidePeerStore(peerStore: Path): Path =
            peerStore.resolveSibling("${peerStore.fileName.toString().removeSuffix(".json")}.invites.json")
    }
}

private fun List<PendingPairingInvite>.firstMatching(secretHash: InviteSecretHash): PendingPairingInvite? {
    val wanted = secretHash.value.encodeToByteArray()
    // Scan every entry (no early exit) so timing does not reveal the position.
    var match: PendingPairingInvite? = null
    for (invite in this) {
        if (MessageDigest.isEqual(invite.secretHash.value.encodeToByteArray(), wanted) && match == null) match = invite
    }
    return match
}
