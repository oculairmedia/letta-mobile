package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.canvas.NotebookHistoryBudget
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.controller.node.iroh.IrohNodeProtocolHandler
import computer.iroh.Connection
import computer.iroh.Endpoint
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An explicitly provisioned notebook allowlist; absent configuration never enables sync. */
object NotebookPeerProvisioning {
    @Serializable
    private data class Peers(val peerIds: List<String>)

    fun read(file: Path): Set<String>? {
        if (!Files.exists(file, NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Not a regular notebook peer file: $file" }
        val ids = Json.decodeFromString<Peers>(Files.readString(file, UTF_8)).peerIds
        require(ids.isNotEmpty() && ids.size == ids.toSet().size && ids.all { it.matches(Regex("[0-9a-f]{64}")) }) {
            "Notebook peers must be distinct lowercase Iroh endpoint IDs"
        }
        return ids.toSet()
    }
}

/** Sync handler for a store owned by the caller, using the host endpoint for identity and dialing. */
class NotebookEndpointSession private constructor(
    private val store: NotebookLocalStore,
    endpoint: Endpoint,
    peers: Set<String>,
    scope: CoroutineScope,
    private val ownsStore: Boolean,
) : IrohNodeProtocolHandler, AutoCloseable {
    constructor(store: NotebookLocalStore, endpoint: Endpoint, peers: Set<String>, scope: CoroutineScope) :
        this(store, endpoint, peers, scope, false)

    /** Compatibility constructor for sessions that own their own store and projection poller. */
    constructor(directory: Path, endpoint: Endpoint, peers: Set<String>, scope: CoroutineScope) :
        this(
            // A synced store: an over-budget history is reported, not restarted under peers.
            NotebookLocalStore(directory, IrohDiagnostics.endpointIdHex(endpoint.addr().id()), NotebookHistoryBudget(compactOversized = false)),
            endpoint, peers, scope, true,
        )

    private val protocol = AutomergeIrohRepoProtocol(store.repo, peers, scope, endpoint::connect)
    init {
        if (ownsStore) store.startPolling(1_000)
    }
    override val alpn: ByteArray get() = protocol.alpn
    override fun authorize(remoteEndpointId: String): Boolean = protocol.authorize(remoteEndpointId)
    override suspend fun accept(connection: Connection, remoteEndpointId: String) = protocol.accept(connection, remoteEndpointId)
    fun documents(): NotebookLocalStore = store

    override fun close() {
        protocol.close()
        if (ownsStore) store.close()
    }
}
