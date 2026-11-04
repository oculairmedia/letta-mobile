package com.letta.mobile.data.canvas.authority

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.automerge.AmValue
import org.automerge.ObjectId
import org.automerge.repo.Dialer
import org.automerge.repo.DocHandle
import org.automerge.repo.DocumentId
import org.automerge.repo.PeerId
import org.automerge.repo.Repo
import org.automerge.repo.RepoConfig
import org.automerge.repo.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Evidence for the activation gate: a native announce policy is neither an edit ACL nor an identity binding. */
class NativeNotebookSyncBoundaryTest {
    @Test
    fun announcePolicyCannotPreventIncomingMutationOrAuthenticateClaimedPeer() = runBlocking {
        val claimedPeer = "a".repeat(64)
        val observedPeers = CopyOnWriteArrayList<String>()
        Repo.load(RepoConfig.builder().peerId(PeerId.fromString("host"))
            .announcePolicy { _, peer ->
                observedPeers.add(peer.toString())
                CompletableFuture.completedFuture(false)
            }.build()).use { host ->
            Repo.load(RepoConfig.builder().peerId(PeerId.fromString(claimedPeer)).build()).use { peer ->
                val source = peer.create().get(5, TimeUnit.SECONDS)
                source.write("initial")
                connectNotebookRepos(host, peer).use {
                    val replica = awaitDocument(host, source.documentId)
                    awaitText(replica, "initial")
                    source.write("peer edit")
                    awaitText(replica, "peer edit")
                    assertEquals("peer edit", replica.text())
                    assertTrue(observedPeers.isNotEmpty())
                    assertTrue(observedPeers.all { it == claimedPeer }, observedPeers.toString())
                }
            }
        }
    }

    private suspend fun awaitDocument(repo: Repo, id: DocumentId): DocHandle = withTimeout(5_000) {
        var handle = repo.find(id).get(5, TimeUnit.SECONDS).orElse(null)
        while (handle == null) {
            delay(10)
            handle = repo.find(id).get(5, TimeUnit.SECONDS).orElse(null)
        }
        handle
    }

    private suspend fun awaitText(handle: DocHandle, expected: String) = withTimeout(5_000) {
        while (handle.text() != expected) delay(10)
    }

    private fun DocHandle.write(text: String) = withDocument { document ->
        document.startTransaction().use { tx -> tx.set(ObjectId.ROOT, "text", text); tx.commit() }
    }.get(5, TimeUnit.SECONDS)

    private fun DocHandle.text(): String? = withDocument { document ->
        (document.get(ObjectId.ROOT, "text").orElse(null) as? AmValue.Str)?.value
    }.get(5, TimeUnit.SECONDS)
}

/** Native Samod link shared by policy evidence and active-link activation tests. */
internal fun connectNotebookRepos(host: Repo, peer: Repo): AutoCloseable {
    val toHost = Executors.newSingleThreadExecutor()
    val toPeer = Executors.newSingleThreadExecutor()
    lateinit var accepted: Transport
    lateinit var dialed: Transport
    accepted = Transport({ bytes -> toPeer.execute { dialed.onMessage(bytes) } }, {})
    dialed = Transport({ bytes -> toHost.execute { accepted.onMessage(bytes) } }, {})
    val acceptor = host.makeAcceptor("memory://host")
    acceptor.accept(accepted).get(5, TimeUnit.SECONDS)
    val dial = peer.dial(object : Dialer {
        override fun getUrl() = "memory://host"
        override fun connect(): CompletableFuture<Transport> = CompletableFuture.completedFuture(dialed)
    })
    dial.onEstablished().get(5, TimeUnit.SECONDS)
    return AutoCloseable {
        dial.close()
        acceptor.close()
        accepted.close()
        dialed.close()
        toHost.shutdownNow()
        toPeer.shutdownNow()
    }
}
