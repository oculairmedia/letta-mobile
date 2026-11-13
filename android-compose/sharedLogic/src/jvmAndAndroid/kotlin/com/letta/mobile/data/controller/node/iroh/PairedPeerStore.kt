package com.letta.mobile.data.controller.node.iroh

import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persistent registry of paired peer NodeIds.
 *
 * A paired peer authenticates by its Iroh NodeId alone — the QUIC handshake
 * already proves possession of the private key — so no reusable bootstrap
 * token crosses the wire after enrollment.
 */
@Serializable
data class PairedPeer(
    val nodeId: String,
    val name: String,
    val pairedAtMs: Long,
    /**
     * Explicit capability grants. Records without the field keep the frozen
     * pre-mount desktop role ([IrohPeerCapabilities.LEGACY_DESKTOP_ROLE]) they
     * were paired under, never a later, wider desktop role; admin.full is never
     * implicit and must be granted deliberately.
     */
    val capabilities: Set<String> = IrohPeerCapabilities.LEGACY_DESKTOP_ROLE,
    /**
     * Agents this peer may address. Null = every agent
     * on the host (records paired before agent scoping keep that meaning); a
     * set confines runtime frames and admin_rpc to those agent ids.
     */
    val agentIds: Set<String>? = null,
    /** Last successful authentication, for the paired-device inventory. */
    val lastSeenAtMs: Long? = null,
)

interface PairedPeerStore {
    fun isPaired(nodeId: String): Boolean

    fun get(nodeId: String): PairedPeer?

    fun list(): List<PairedPeer>

    fun save(peer: PairedPeer)

    fun remove(nodeId: String): Boolean

    /**
     * Applies [change] to every stored peer in ONE read-modify-write, so a concurrent pair or revoke is never lost
     * or undone; writes only when some record changed and answers how many did.
     */
    fun updateEach(change: (PairedPeer) -> PairedPeer): Int
}

class InMemoryPairedPeerStore : PairedPeerStore {
    private val peers = mutableMapOf<String, PairedPeer>()

    @Synchronized override fun isPaired(nodeId: String): Boolean = nodeId in peers

    @Synchronized override fun get(nodeId: String): PairedPeer? = peers[nodeId]

    @Synchronized override fun list(): List<PairedPeer> = peers.values.sortedBy { it.pairedAtMs }

    @Synchronized override fun save(peer: PairedPeer) {
        peers[peer.nodeId] = peer
    }

    @Synchronized override fun remove(nodeId: String): Boolean = peers.remove(nodeId) != null

    @Synchronized override fun updateEach(change: (PairedPeer) -> PairedPeer): Int {
        val changed = changedPeers(peers.values, change)
        changed.forEach { peers[it.nodeId] = it }
        return changed.size
    }
}

/** The peers [change] alters; a change may never re-key a record (its node id is its identity). */
private fun changedPeers(peers: Collection<PairedPeer>, change: (PairedPeer) -> PairedPeer): List<PairedPeer> =
    peers.mapNotNull { peer ->
        val updated = change(peer)
        require(updated.nodeId == peer.nodeId) { "a paired peer update may not change its node id" }
        updated.takeIf { it != peer }
    }

/**
 * JSON-file-backed store with atomic writes ([AtomicJsonFile]); the file holds
 * only public NodeIds and metadata (no secrets).
 *
 * The file is shared by separate processes — the serving wrapper (redeem,
 * last-seen touch, revoke) and the host `pair` CLI — and by independent
 * instances inside one process, so every read-modify-write runs under
 * [AtomicJsonFile.withProcessLock] (an OS file lock on a `.lock` sibling plus
 * a per-path JVM monitor). Without it a concurrent pair and revoke could drop
 * the new peer or resurrect the revoked one. Reads
 * stay lock-free: the atomic rename means they always see a whole snapshot.
 */
class FilePairedPeerStore(private val path: Path) : PairedPeerStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override fun isPaired(nodeId: String): Boolean = load().containsKey(nodeId)

    override fun get(nodeId: String): PairedPeer? = load()[nodeId]

    override fun list(): List<PairedPeer> = load().values.sortedBy { it.pairedAtMs }

    override fun save(peer: PairedPeer) {
        AtomicJsonFile.withProcessLock(path) { persist(load() + (peer.nodeId to peer)) }
    }

    override fun remove(nodeId: String): Boolean = AtomicJsonFile.withProcessLock(path) {
        val current = load()
        val present = nodeId in current
        if (present) persist(current - nodeId)
        present
    }

    override fun updateEach(change: (PairedPeer) -> PairedPeer): Int = AtomicJsonFile.withProcessLock(path) {
        val current = load()
        val changed = changedPeers(current.values, change)
        if (changed.isNotEmpty()) persist(current + changed.associateBy { it.nodeId })
        changed.size
    }

    private fun load(): Map<String, PairedPeer> {
        val text = AtomicJsonFile.readOrNull(path) ?: return emptyMap()
        return json.decodeFromString<List<PairedPeer>>(text).associateBy { it.nodeId }
    }

    private fun persist(peers: Map<String, PairedPeer>) {
        AtomicJsonFile.write(path, json.encodeToString(peers.values.sortedBy { it.pairedAtMs }))
    }
}
