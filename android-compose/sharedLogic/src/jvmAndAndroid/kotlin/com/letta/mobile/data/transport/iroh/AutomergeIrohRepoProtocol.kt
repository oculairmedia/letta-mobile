package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.controller.node.iroh.IrohNodeProtocolHandler
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.RecvStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.automerge.repo.Dialer
import org.automerge.repo.Repo
import org.automerge.repo.Transport

/** Samod wire protocol on the host endpoint. Both sides must register this ALPN. */
val AUTOMERGE_REPO_ALPN: ByteArray get() = "meridian/automerge-repo/1".encodeToByteArray()

/**
 * Registers with IrohNodeEndpoint(protocolHandlers = listOf(handler)). The caller owns the
 * endpoint and repository; this handler owns only its Samod connections. No App Server session
 * is required. Peer IDs here are authenticated Iroh endpoint IDs, not Samod's self-reported IDs.
 *
 * [connect] must dial through the same host endpoint registered with this handler (for example,
 * IrohNodeEndpoint.connect). The allowlist must be provisioned out-of-band on both devices.
 */
class AutomergeIrohRepoProtocol(
    private val repo: Repo,
    allowedPeerIds: Set<String>,
    private val scope: CoroutineScope,
    private val connect: suspend (EndpointAddr, ByteArray) -> Connection,
) : IrohNodeProtocolHandler, AutoCloseable {
    override val alpn: ByteArray get() = AUTOMERGE_REPO_ALPN
    private val peers = allowedPeerIds.toSet().also { ids ->
        require(ids.all { it.matches(Regex("[0-9a-f]{64}")) }) { "Expected lowercase Iroh endpoint IDs" }
    }
    private val closed = AtomicBoolean(false)
    private val links = ConcurrentHashMap.newKeySet<AutoCloseable>()

    override fun authorize(remoteEndpointId: String): Boolean = !closed.get() && remoteEndpointId in peers

    override suspend fun accept(connection: Connection, remoteEndpointId: String) {
        if (!authorize(remoteEndpointId) || IrohDiagnostics.endpointIdHex(connection.remoteId()) != remoteEndpointId) {
            connection.close(4403, "peer_not_allowed".encodeToByteArray())
            return
        }
        var link: RepoStream? = null
        try {
            val stream = connection.acceptBi()
            if (closed.get()) return
            val opened = RepoStream(connection, stream, scope)
            link = opened
            links.add(opened)
            if (closed.get()) return
            repo.makeAcceptor("iroh://$remoteEndpointId").use { acceptor ->
                acceptor.accept(opened.transport).let { future ->
                    withContext(Dispatchers.IO) { future.get(15, TimeUnit.SECONDS) }
                }
                opened.awaitClosed()
            }
        } finally {
            link?.close()
            connection.close(0, "closed".encodeToByteArray())
        }
    }

    /** Dial an allowlisted peer using the injected host-owned endpoint, not a second endpoint. */
    suspend fun dial(remote: EndpointAddr, remoteEndpointId: String): AutoCloseable {
        require(authorize(remoteEndpointId)) { "Peer not allowed" }
        val connectionFuture = CompletableFuture<Transport>()
        val job = scope.launch {
            runDialedStream(remote, remoteEndpointId, connectionFuture)
        }
        val handle = try {
            repo.dial(object : Dialer {
                override fun getUrl(): String = "iroh://$remoteEndpointId"
                override fun connect(): CompletableFuture<Transport> = connectionFuture
            })
        } catch (error: Throwable) {
            job.cancel()
            throw error
        }
        val owned = AutoCloseable {
            handle.close()
            job.cancel()
        }
        links.add(owned)
        if (closed.get()) owned.close()
        return AutoCloseable { links.remove(owned); owned.close() }
    }

    private suspend fun runDialedStream(
        remote: EndpointAddr,
        remoteEndpointId: String,
        connectionFuture: CompletableFuture<Transport>,
    ) {
        var connection: Connection? = null
        var link: RepoStream? = null
        try {
            connection = connect(remote, alpn)
            check(IrohDiagnostics.endpointIdHex(connection.remoteId()) == remoteEndpointId) { "Dialed unexpected peer" }
            check(authorize(remoteEndpointId)) { "Peer no longer allowed" }
            val opened = RepoStream(connection, connection.openBi(), scope)
            link = opened
            links.add(opened)
            if (closed.get()) throw CancellationException("Protocol closed")
            if (!connectionFuture.complete(opened.transport)) return
            opened.awaitClosed()
        } catch (error: Throwable) {
            connectionFuture.completeExceptionally(error)
            if (error is CancellationException) throw error
        } finally {
            link?.close()
            connection?.close(0, "closed".encodeToByteArray())
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        links.toList().forEach { runCatching { it.close() } }
        links.clear()
    }

    private inner class RepoStream(
        private val connection: Connection,
        private val stream: BiStream,
        scope: CoroutineScope,
    ) : AutoCloseable {
        private val stopped = AtomicBoolean(false)
        private val writeLock = Mutex()
        val transport = Transport(
            { bytes ->
                runBlocking {
                    writeLock.withLock {
                        check(!stopped.get()) { "Samod stream closed" }
                        stream.send().writeAll(AutomergeFrame.encode(bytes))
                    }
                }
            },
            { close() },
        )
        private val reader: Job = scope.launch {
            try {
                while (!stopped.get()) {
                    val bytes = AutomergeFrame.read(stream.recv()) ?: break
                    transport.onMessage(bytes)
                }
            } finally {
                close()
                transport.onClose()
            }
        }

        suspend fun awaitClosed() = reader.join()

        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            links.remove(this)
            reader.cancel()
            runCatching { connection.close(0, "closed".encodeToByteArray()) }
            runCatching { stream.close() }
        }
    }
}

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

/** Session-owned store and handler; the caller's Iroh endpoint supplies identity and outbound dialing. */
class NotebookEndpointSession(
    directory: Path,
    endpoint: Endpoint,
    peers: Set<String>,
    scope: CoroutineScope,
) : IrohNodeProtocolHandler, AutoCloseable {
    private val store = NotebookLocalStore(directory, IrohDiagnostics.endpointIdHex(endpoint.addr().id()))
    private val protocol = AutomergeIrohRepoProtocol(store.repo, peers, scope, endpoint::connect)
    init {
        store.startPolling(1_000)
    }
    override val alpn: ByteArray get() = protocol.alpn
    override fun authorize(remoteEndpointId: String): Boolean = protocol.authorize(remoteEndpointId)
    override suspend fun accept(connection: Connection, remoteEndpointId: String) = protocol.accept(connection, remoteEndpointId)
    fun documents(): NotebookLocalStore = store

    override fun close() {
        protocol.close()
        store.close()
    }
}

/** Four-byte big-endian length, bounded before allocation or writing. */
internal object AutomergeFrame {
    private const val MAX_BYTES = 4 * 1024 * 1024

    fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_BYTES) { "Automerge frame too large: ${payload.size}" }
        return ByteArray(4 + payload.size).also { frame ->
            frame[0] = (payload.size ushr 24).toByte()
            frame[1] = (payload.size ushr 16).toByte()
            frame[2] = (payload.size ushr 8).toByte()
            frame[3] = payload.size.toByte()
            payload.copyInto(frame, 4)
        }
    }

    suspend fun read(stream: RecvStream): ByteArray? {
        val prefix = ByteArray(4)
        var offset = 0
        while (offset < 4) {
            val chunk = stream.read((4 - offset).toUInt())
            if (chunk.isEmpty()) {
                if (offset == 0) return null
                error("Truncated Automerge frame prefix")
            }
            chunk.copyInto(prefix, offset)
            offset += chunk.size
        }
        val length = ((prefix[0].toInt() and 255) shl 24) or
            ((prefix[1].toInt() and 255) shl 16) or
            ((prefix[2].toInt() and 255) shl 8) or (prefix[3].toInt() and 255)
        require(length in 0..MAX_BYTES) { "Invalid Automerge frame length: $length" }
        return stream.readExact(length.toUInt())
    }
}
