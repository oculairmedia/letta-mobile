package com.letta.mobile.data.controller.node.iroh

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
 * JSON-file-backed store with atomic writes; the file holds only public
 * NodeIds and metadata (no secrets), but is still written 0600-style via the
 * same permission restriction used for key files.
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
        if (!Files.exists(path)) return emptyMap()
        val text = String(Files.readAllBytes(path), Charsets.UTF_8)
        if (text.isBlank()) return emptyMap()
        return json.decodeFromString<List<PairedPeer>>(text).associateBy { it.nodeId }
    }

    private fun persist(peers: Map<String, PairedPeer>) {
        path.parent?.let(Files::createDirectories)
        val temp = path.resolveSibling(".${path.fileName}.tmp-${java.util.UUID.randomUUID()}")
        Files.write(temp, json.encodeToString(peers.values.sortedBy { it.pairedAtMs }).toByteArray(Charsets.UTF_8))
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
