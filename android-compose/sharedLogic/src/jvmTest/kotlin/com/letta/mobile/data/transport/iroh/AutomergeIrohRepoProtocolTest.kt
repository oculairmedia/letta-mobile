package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.canvas.NotebookLocalStore
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import java.nio.file.Files
import org.automerge.repo.PeerId
import org.automerge.repo.Repo
import org.automerge.repo.RepoConfig
import org.automerge.repo.storage.FileSystemStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
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
