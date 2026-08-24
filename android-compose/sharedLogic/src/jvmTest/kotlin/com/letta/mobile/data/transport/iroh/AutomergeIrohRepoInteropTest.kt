package com.letta.mobile.data.transport.iroh

import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RecvStream
import computer.iroh.RelayMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.automerge.AmValue
import org.automerge.ObjectId
import org.automerge.repo.AcceptorHandle
import org.automerge.repo.Dialer
import org.automerge.repo.DocHandle
import org.automerge.repo.DocumentId
import org.automerge.repo.PeerId
import org.automerge.repo.Repo
import org.automerge.repo.RepoConfig
import org.automerge.repo.Transport
import org.automerge.repo.storage.FileSystemStorage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Code-verified architecture spike for Meridian federation.
 *
 * This proves Automerge Java's Samod repository can synchronize and persist a
 * document over the same `computer.iroh` endpoint API already shipped by the
 * Android and desktop clients. It intentionally remains opt-in with the other
 * live-QUIC tests; normal CI does not depend on loopback networking.
 */
class AutomergeIrohRepoInteropTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closeables = CopyOnWriteArrayList<AutoCloseable>()
    private lateinit var clientEndpoint: Endpoint
    private lateinit var serverEndpoint: Endpoint

    @Before
    fun requireOptIn() {
        assumeTrue("set -DrunIrohLiveE2E=true", System.getProperty("runIrohLiveE2E") == "true")
    }

    @After
    fun tearDown() = runBlocking {
        closeables.asReversed().forEach { runCatching { it.close() } }
        scope.cancel()
        if (::clientEndpoint.isInitialized) runCatching { clientEndpoint.shutdown() }
        if (::serverEndpoint.isInitialized) runCatching { serverEndpoint.shutdown() }
    }

    @Test
    fun repositorySyncsOverExistingIrohBindingAndReloadsFromDisk() = runBlocking {
        val root = Files.createTempDirectory("meridian-automerge-iroh-")
        val clientStore = root.resolve("client")
        val serverStore = root.resolve("server")
        val clientPeer = PeerId.fromString("meridian-windows")
        val serverPeer = PeerId.fromString("meridian-android")

        clientEndpoint = bindEndpoint()
        serverEndpoint = bindEndpoint()

        val clientRepo = loadRepo(clientStore, clientPeer).also(closeables::add)
        val serverRepo = loadRepo(serverStore, serverPeer).also(closeables::add)
        val source = clientRepo.create().get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val documentId = source.documentId
        source.withDocument { document ->
            document.startTransaction().use { transaction ->
                transaction.set(ObjectId.ROOT, "agent", "meridian")
                transaction.set(ObjectId.ROOT, "revision", 1)
                transaction.commit()
            }
        }.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        connectRepositories(clientRepo, serverRepo)

        val replica = waitForDocument(serverRepo, documentId)
        assertEquals("meridian", waitForString(replica, "agent"))

        serverRepo.close()
        closeables.remove(serverRepo)

        loadRepo(serverStore, serverPeer).use { reopened ->
            val restored = reopened.find(documentId)
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .orElseThrow()
            assertEquals("meridian", restored.readString("agent"))
        }
    }

    private suspend fun bindEndpoint(): Endpoint = Endpoint.bind(
        EndpointOptions(
            relayMode = RelayMode.disabled(),
            alpns = listOf(AUTOMERGE_REPO_ALPN),
        ),
    )

    private fun loadRepo(path: Path, peerId: PeerId): Repo = Repo.load(
        RepoConfig.builder()
            .storage(FileSystemStorage(path))
            .peerId(peerId)
            .build(),
    )

    private suspend fun connectRepositories(clientRepo: Repo, serverRepo: Repo) {
        val acceptor = serverRepo.makeAcceptor("iroh://server").also(closeables::add)
        val accepted = CompletableDeferred<Unit>()
        scope.launch {
            val incoming = checkNotNull(serverEndpoint.acceptNext())
            val accepting = incoming.accept()
            assertEquals(AUTOMERGE_REPO_ALPN.toList(), accepting.alpn().toList())
            val connection = accepting.connect()
            val stream = connection.acceptBi()
            val transport = IrohRepoTransport(connection, stream, scope)
            closeables += transport
            acceptor.accept(transport.transport)
            accepted.complete(Unit)
        }

        clientRepo.dial(
            object : Dialer {
                override fun getUrl(): String = "iroh://server"

                override fun connect(): CompletableFuture<Transport> = CompletableFuture.supplyAsync {
                    runBlocking {
                        val connection = clientEndpoint.connect(serverEndpoint.addr(), AUTOMERGE_REPO_ALPN)
                        val stream = connection.openBi()
                        IrohRepoTransport(connection, stream, scope).also { closeables += it }.transport
                    }
                }
            },
        )
        withTimeout(10.seconds) { accepted.await() }
    }

    private suspend fun waitForDocument(repo: Repo, documentId: DocumentId): DocHandle =
        withTimeout(15.seconds) {
            while (true) {
                val found = withContext(Dispatchers.IO) {
                    repo.find(documentId).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
                if (found.isPresent) return@withTimeout found.get()
                kotlinx.coroutines.delay(50)
            }
            error("unreachable")
        }

    private fun DocHandle.readString(key: String): String = withDocument { document ->
        (document.get(ObjectId.ROOT, key).orElseThrow() as AmValue.Str).value
    }.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private suspend fun waitForString(handle: DocHandle, key: String): String = withTimeout(15.seconds) {
        while (true) {
            val value = withContext(Dispatchers.IO) {
                handle.withDocument { document ->
                    (document.get(ObjectId.ROOT, key).orElse(null) as? AmValue.Str)?.value
                }.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            if (value != null) return@withTimeout value
            kotlinx.coroutines.delay(50)
        }
        error("unreachable")
    }

    private class IrohRepoTransport(
        private val connection: Connection,
        private val stream: BiStream,
        scope: CoroutineScope,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val writeMutex = Mutex()
        private val readerJob: Job

        val transport = Transport(
            { payload ->
                runBlocking {
                    writeMutex.withLock {
                        stream.send().writeAll(BinaryFrame.encode(payload))
                    }
                }
            },
            { close() },
        )

        init {
            readerJob = scope.launch {
                runCatching {
                    while (true) {
                        val payload = BinaryFrame.read(stream.recv()) ?: break
                        transport.onMessage(payload)
                    }
                }
                transport.onClose()
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            readerJob.cancel()
            runCatching { connection.close(0, "closed".encodeToByteArray()) }
            runCatching { stream.close() }
        }
    }

    private object BinaryFrame {
        private const val PREFIX_BYTES = 4
        private const val MAX_BYTES = 4 * 1024 * 1024

        fun encode(payload: ByteArray): ByteArray {
            require(payload.size <= MAX_BYTES) { "Automerge frame too large: ${payload.size}" }
            return ByteArray(PREFIX_BYTES + payload.size).also { frame ->
                frame[0] = (payload.size ushr 24).toByte()
                frame[1] = (payload.size ushr 16).toByte()
                frame[2] = (payload.size ushr 8).toByte()
                frame[3] = payload.size.toByte()
                payload.copyInto(frame, PREFIX_BYTES)
            }
        }

        suspend fun read(stream: RecvStream): ByteArray? {
            val prefix = readPrefix(stream) ?: return null
            val length =
                ((prefix[0].toInt() and 0xff) shl 24) or
                    ((prefix[1].toInt() and 0xff) shl 16) or
                    ((prefix[2].toInt() and 0xff) shl 8) or
                    (prefix[3].toInt() and 0xff)
            require(length in 0..MAX_BYTES) { "Invalid Automerge frame length: $length" }
            return stream.readExact(length.toUInt())
        }

        private suspend fun readPrefix(stream: RecvStream): ByteArray? {
            val prefix = ByteArray(PREFIX_BYTES)
            var offset = 0
            while (offset < PREFIX_BYTES) {
                val chunk = stream.read((PREFIX_BYTES - offset).toUInt())
                if (chunk.isEmpty()) {
                    if (offset == 0) return null
                    error("Truncated Automerge frame prefix")
                }
                chunk.copyInto(prefix, destinationOffset = offset)
                offset += chunk.size
            }
            return prefix
        }
    }

    private companion object {
        val AUTOMERGE_REPO_ALPN = "meridian/automerge-repo/1".encodeToByteArray()
        const val FUTURE_TIMEOUT_SECONDS = 10L
    }
}
