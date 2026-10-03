package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.canvas.CanvasStorageFault
import com.letta.mobile.data.canvas.NotebookDocumentIndex
import com.letta.mobile.data.canvas.NotebookHistoryArchive
import com.letta.mobile.data.canvas.NotebookHistoryBudget
import com.letta.mobile.data.canvas.NotebookLocalStore
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.automerge.ObjectId
import org.automerge.repo.Dialer
import org.automerge.repo.DocumentId
import org.automerge.repo.PeerId
import org.automerge.repo.Repo
import org.automerge.repo.RepoConfig
import org.automerge.repo.Transport
import org.automerge.repo.storage.FileSystemStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomergeIrohRepoProtocolTest {
    @Test
    fun injectedStoreRemainsUsableAfterSessionCloses() = runBlocking {
        val endpoint = Endpoint.bind(EndpointOptions(relayMode = RelayMode.disabled()))
        val store = NotebookLocalStore(Files.createTempDirectory("notebook-shared-"), "test-peer")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val session = NotebookEndpointSession(store, endpoint, setOf("a".repeat(64)), scope)
            assertTrue(session.documents() === store)
            assertTrue(session.authorize("a".repeat(64)))
            session.close()
            assertFalse(session.authorize("a".repeat(64)))
            val id = store.create("still open")
            assertTrue(store.read(id)?.title == "still open")
        } finally {
            scope.cancel()
            store.close()
            endpoint.shutdown()
            endpoint.close()
        }
    }

    private fun DocumentId.key(): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { stream ->
            stream.iterator().asSequence().toList().forEach { source ->
                val target = to.resolve(from.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            }
        }
    }

    /** Two repositories connected in memory, as the Iroh protocol connects them over a stream. */
    private fun connect(acceptor: Repo, dialer: Repo): AutoCloseable {
        val toAcceptor = Executors.newSingleThreadExecutor()
        val toDialer = Executors.newSingleThreadExecutor()
        lateinit var accepted: Transport
        lateinit var dialed: Transport
        accepted = Transport({ bytes -> toDialer.execute { dialed.onMessage(bytes) } }, { toDialer.execute { dialed.onClose() } })
        dialed = Transport({ bytes -> toAcceptor.execute { accepted.onMessage(bytes) } }, { toAcceptor.execute { accepted.onClose() } })
        val listener = acceptor.makeAcceptor("memory://acceptor")
        listener.accept(accepted)
        val dial = dialer.dial(object : Dialer {
            override fun getUrl(): String = "memory://acceptor"
            override fun connect(): CompletableFuture<Transport> = CompletableFuture.completedFuture(dialed)
        })
        return AutoCloseable {
            runCatching { dial.close() }
            runCatching { listener.close() }
            toAcceptor.shutdownNow()
            toDialer.shutdownNow()
        }
    }

    /**
     * A synced store moves an over-budget document to a new id. A peer still holding the old id
     * offers it back: it is neither stored nor indexed, and nothing breaks.
     */
    @Test
    fun aPeerOfferingARetiredDocumentDoesNotBringItBack() = runBlocking {
        val path = Files.createTempDirectory("notebook-retired-phone-")
        val peerPath = Files.createTempDirectory("notebook-retired-peer-")
        val id = NotebookLocalStore(path, "phone-peer").use { store ->
            store.create("Moved").also { id ->
                store.insertMarkdown(id, 0, "Kept")
                repeat(20) { round ->
                    store.open(id)!!.withDocument { document ->
                        document.startTransaction().use { tx ->
                            tx.set(ObjectId.ROOT, "scratch", (0 until 500).joinToString(",") { "${kotlin.random.Random(round).nextLong()}" })
                            tx.commit()
                        }
                    }.get(5, TimeUnit.SECONDS)
                }
            }
        }
        val key = id.key()
        // The peer has its own copy of the document, under the old id.
        copyTree(NotebookHistoryArchive(path).documentDirectory(key), NotebookHistoryArchive(peerPath).documentDirectory(key))

        val moved = NotebookLocalStore(path, "phone-peer", NotebookHistoryBudget(maxDocumentBytes = 16 * 1024)).use { phone ->
            val repo = phone.repoForSync()
            val moved = phone.listDocuments().single()
            assertNotEquals(id, moved)
            assertTrue(phone.faults.faults.value.any { it.kind == CanvasStorageFault.Kind.COMPACTED }, phone.faults.faults.value.toString())
            val peer = Repo.load(RepoConfig.builder().storage(FileSystemStorage(peerPath)).peerId(PeerId.fromString("old-peer")).build())
            try {
                val handle = peer.find(id).get(10, TimeUnit.SECONDS).orElseThrow()
                handle.withDocument { document ->
                    document.startTransaction().use { tx ->
                        tx.set(ObjectId.ROOT, "title", "Edited on the peer")
                        tx.commit()
                    }
                }.get(5, TimeUnit.SECONDS)
                connect(repo, peer).use {
                    // The peer offers the old document; the phone's repository receives it, and its storage refuses it.
                    val deadline = System.currentTimeMillis() + 15_000
                    while (key !in phone.retiredDocumentsOffered()) {
                        // Ask for it too, as any lookup by the old id would.
                        repo.find(id)
                        check(System.currentTimeMillis() < deadline) { "The peer never offered the retired document" }
                        Thread.sleep(100)
                    }
                    Thread.sleep(500)
                    assertFalse(Files.exists(NotebookHistoryArchive(path).documentDirectory(key)))
                    assertEquals(listOf(moved), phone.listDocuments())
                    assertNull(phone.open(id))
                    assertFalse(phone.registerDocument(id))
                    assertEquals(listOf(moved.key()), NotebookDocumentIndex(path).read())
                    // Nothing broke: no error, not read-only, and the board takes writes.
                    assertFalse(phone.isReadOnly)
                    assertTrue(phone.faults.faults.value.none { it.isError }, phone.faults.faults.value.toString())
                    phone.insertMarkdown(moved, 0, ">")
                    assertEquals(">Kept", phone.read(moved)!!.markdown)
                    assertEquals("Moved", phone.read(moved)!!.title)
                }
            } finally {
                peer.close()
            }
            moved
        }
        // Nor does it come back on the next launch.
        NotebookLocalStore(path, "phone-peer").use { phone ->
            assertEquals(listOf(moved), phone.listDocuments())
            assertNull(phone.open(id))
        }
        assertFalse(Files.exists(NotebookHistoryArchive(path).documentDirectory(key)))
    }

    @Test
    fun noPeersLeavesProcessStoreAvailableWithoutBindingNotebookEndpoint() = runBlocking {
        val store = NotebookLocalStore(Files.createTempDirectory("notebook-unpaired-"), "test-peer")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val transport = IrohChannelTransport(
                scope = scope,
                notebookStore = store,
                notebookPeers = { emptySet() },
                secretKeyStore = object : com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore {
                    override suspend fun loadOrCreate(): ByteArray = error("Should not bind without peers")
                },
            )
            transport.startNotebook()
            val id = store.create("offline")
            assertTrue(store.read(id)?.title == "offline")
        } finally {
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun framesBoundPayloadBeforeWriting() {
        val frame = AutomergeFrame.encode(byteArrayOf(3, 4))
        assertContentEquals(byteArrayOf(0, 0, 0, 2, 3, 4), frame)
        assertFailsWith<IllegalArgumentException> { AutomergeFrame.encode(ByteArray(4 * 1024 * 1024 + 1)) }
    }

    @Test
    fun onlyExplicitEndpointIdentitiesCanEnterOrDial() {
        val dir = Files.createTempDirectory("samod-authorization-")
        val repo = Repo.load(RepoConfig.builder().storage(FileSystemStorage(dir)).peerId(PeerId.fromString("test-peer")).build())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val peer = "a".repeat(64)
        try {
            AutomergeIrohRepoProtocol(repo, setOf(peer), scope) { _, _ -> error("unexpected dial") }.use { protocol ->
                assertContentEquals("meridian/automerge-repo/1".encodeToByteArray(), protocol.alpn)
                assertTrue(protocol.authorize(peer))
                assertFalse(protocol.authorize("b".repeat(64)))
                assertFalse(protocol.authorize(peer.uppercase()))
            }
            val closed = AutomergeIrohRepoProtocol(repo, setOf(peer), scope) { _, _ -> error("unexpected dial") }
            closed.close()
            assertFalse(closed.authorize(peer))
            assertFailsWith<IllegalArgumentException> {
                AutomergeIrohRepoProtocol(repo, setOf("not-an-id"), scope) { _, _ -> error("unexpected dial") }
            }
        } finally {
            scope.cancel()
            repo.close()
        }
    }
}
