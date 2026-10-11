package com.letta.mobile.data.controller.node.iroh

import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persistent registry of paired peer NodeIds (letta-mobile-d6e8g.5).
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
     * Explicit capability grants (d6e8g.6). Records without the field keep the
     * least-privilege desktop role they were paired under; admin.full is never
     * implicit and must be granted deliberately.
     */
    val capabilities: Set<String> = IrohPeerCapabilities.DEFAULT_DESKTOP_ROLE,
    /**
     * Agents this peer may address (letta-mobile-dpv7m.1). Null = every agent
     * on the host (records paired before agent scoping keep that meaning); a
     * set confines runtime frames and admin_rpc to those agent ids.
     */
    val agentIds: Set<String>? = null,
    /** Last successful authentication, for the paired-device inventory (d6e8g.7). */
    val lastSeenAtMs: Long? = null,
)

interface PairedPeerStore {
    fun isPaired(nodeId: String): Boolean

    fun get(nodeId: String): PairedPeer?

    fun list(): List<PairedPeer>

    fun save(peer: PairedPeer)

    fun remove(nodeId: String): Boolean
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
}

/**
 * JSON-file-backed store with atomic writes ([AtomicJsonFile]); the file holds
 * only public NodeIds and metadata (no secrets).
 */
class FilePairedPeerStore(private val path: Path) : PairedPeerStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Synchronized
    override fun isPaired(nodeId: String): Boolean = load().containsKey(nodeId)

    @Synchronized
    override fun get(nodeId: String): PairedPeer? = load()[nodeId]

    @Synchronized
    override fun list(): List<PairedPeer> = load().values.sortedBy { it.pairedAtMs }

    @Synchronized
    override fun save(peer: PairedPeer) {
        persist(load() + (peer.nodeId to peer))
    }

    @Synchronized
    override fun remove(nodeId: String): Boolean {
        val current = load()
        if (nodeId !in current) return false
        persist(current - nodeId)
        return true
    }

    private fun load(): Map<String, PairedPeer> {
        val text = AtomicJsonFile.readOrNull(path) ?: return emptyMap()
        return json.decodeFromString<List<PairedPeer>>(text).associateBy { it.nodeId }
    }

    private fun persist(peers: Map<String, PairedPeer>) {
        AtomicJsonFile.write(path, json.encodeToString(peers.values.sortedBy { it.pairedAtMs }))
    }
}
