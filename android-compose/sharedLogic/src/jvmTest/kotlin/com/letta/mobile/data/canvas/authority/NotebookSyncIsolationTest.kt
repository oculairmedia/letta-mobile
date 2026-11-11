package com.letta.mobile.data.canvas.authority

import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.canvas.NotebookPeerGrant
import com.letta.mobile.data.transport.iroh.NotebookEndpointSession
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.automerge.repo.Repo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NotebookSyncIsolationTest {
    @Test
    fun grantsNeverPermitRawRepositoryExposureEvenForReadOnlyPeers() = runBlocking {
        assumeDurableGrantHost()
        val root = Files.createTempDirectory("notebook-sync-isolation")
        NotebookLocalStore(root.resolve("notebooks"), "host").use { notebooks ->
            val storageId = notebooks.create("Protected")
            val id = requireNotNull(notebooks.read(storageId)).id
            val grants = FileNotebookGrantStore(root.resolve("grants.json"))
            for (grant in listOf(null, NotebookPeerGrant("peer", mayRead = true),
                NotebookPeerGrant("peer", mayRead = true, mayEdit = true, mayReference = true))) {
                if (grant != null) grants.provision(id, grant)
                HostNotebookAccess(notebooks, grants, "peer")
                val failure = assertFailsWith<IllegalStateException> { notebooks.repoForSync() }
                assertTrue(failure.message.orEmpty().contains("unrestricted repository sync"))
            }
            grants.revoke("peer", id)
            assertFailsWith<IllegalStateException> { notebooks.repoForSync() }
            Files.writeString(root.resolve("grants.json"), "{corrupt")
            assertFailsWith<IllegalStateException> { notebooks.repoForSync() }
            assertEquals("", notebooks.read(storageId)?.markdown)
        }
    }

    @Test
    fun previouslyExposedRepositoryCannotActivateGrantsWithRetainedLinks() {
        val root = Files.createTempDirectory("notebook-retained-sync")
        NotebookLocalStore(root.resolve("notebooks"), "host").use { notebooks ->
            Repo.load().use { peer ->
                connectNotebookRepos(notebooks.repoForSync(), peer).use {
                    val failure = assertFailsWith<IllegalStateException> {
                        HostNotebookAccess(notebooks, FileNotebookGrantStore(root.resolve("grants.json")), "peer")
                    }
                    assertTrue(failure.message.orEmpty().contains("already exposed"))
                }
            }
        }
    }

    @Test
    fun endpointSessionCannotAttachProtectedRepository() = runBlocking {
        val root = Files.createTempDirectory("notebook-session-isolation")
        val endpoint = Endpoint.bind(EndpointOptions(relayMode = RelayMode.disabled()))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            NotebookLocalStore(root.resolve("notebooks"), "host").use { notebooks ->
                HostNotebookAccess(notebooks, FileNotebookGrantStore(root.resolve("grants.json")), "peer")
                val failure = assertFailsWith<IllegalStateException> {
                    NotebookEndpointSession(notebooks, endpoint, setOf("a".repeat(64)), scope).close()
                }
                assertTrue(failure.message.orEmpty().contains("unrestricted repository sync"))
            }
        } finally {
            scope.cancel()
            endpoint.shutdown()
            endpoint.close()
        }
    }
}
