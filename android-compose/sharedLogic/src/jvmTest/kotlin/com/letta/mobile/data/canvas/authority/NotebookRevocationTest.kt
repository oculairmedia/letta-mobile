package com.letta.mobile.data.canvas.authority

import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.canvas.NotebookPeerGrant
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceOpen
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.automerge.repo.Storage
import org.automerge.repo.StorageKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotebookRevocationTest {
    @Test
    fun revokeWaitsForMutationAndBlocksTheNextSave() = runBlocking {
        val root = Files.createTempDirectory("notebook-revoke-mutation")
        val hold = AtomicBoolean(false)
        val entered = CompletableFuture<Unit>()
        val release = CompletableFuture<Void>()
        val wrapper = { delegate: Storage ->
            object : Storage by delegate {
                override fun put(key: StorageKey, value: ByteArray): CompletableFuture<Void> {
                    val barrier = Thread.currentThread().stackTrace.any {
                        it.className.endsWith("NotebookStorageHealth") && it.methodName == "persistChunk"
                    }
                    return if (hold.get() && barrier && entered.complete(Unit)) {
                        release.thenCompose { delegate.put(key, value) }
                    } else delegate.put(key, value)
                }
            }
        }
        NotebookLocalStore(Files.createDirectory(root.resolve("notebooks")), "peer", storage = wrapper).use { notebooks ->
            val storageId = notebooks.create("note")
            assertTrue(notebooks.persistCanvas(storageId))
            val id = requireNotNull(notebooks.read(storageId)).id
            val file = root.resolve("grants.json")
            val grants = FileNotebookGrantStore(file)
            grants.provision(id, NotebookPeerGrant("peer", mayRead = true, mayEdit = true))
            val opened = HostNotebookAccess(notebooks, grants, "peer").opener.open(id)
            val session = assertIs<DocumentWorkspaceOpen.Opened>(opened).sessions.note
            session.refresh()
            session.edit("first")
            hold.set(true)
            val save = async(Dispatchers.Default) { session.save() }
            try {
                withContext(Dispatchers.IO) { entered.get(10, TimeUnit.SECONDS) }
                val revoked = async(Dispatchers.Default) { FileNotebookGrantStore(file).revoke("peer", id) }
                assertEquals(null, withTimeoutOrNull(200) { revoked.await(); true })
                release.complete(null)
                assertTrue(save.await())
                revoked.await()
                session.edit("second")
                assertFalse(session.save())
                assertEquals("first", notebooks.read(storageId)?.markdown)
                assertEquals("second", session.state.value.draft)
            } finally {
                release.completeExceptionally(IOException("test cleanup"))
            }
        }
    }
    @Test
    fun revokedPendingWriteCannotFlushUntilRegranted() = runBlocking {
        val root = Files.createTempDirectory("notebook-revoke-flush")
        val fail = AtomicBoolean(false)
        val wrapper = { delegate: Storage ->
            object : Storage by delegate {
                override fun put(key: StorageKey, value: ByteArray): CompletableFuture<Void> =
                    if (fail.get()) CompletableFuture.failedFuture(IOException("offline")) else delegate.put(key, value)
            }
        }
        NotebookLocalStore(Files.createDirectory(root.resolve("notebooks")), "peer", storage = wrapper).use { notebooks ->
            val storageId = notebooks.create("note")
            assertTrue(notebooks.persistCanvas(storageId))
            val id = requireNotNull(notebooks.read(storageId)).id
            val grants = FileNotebookGrantStore(root.resolve("grants.json"))
            val grant = NotebookPeerGrant("peer", mayRead = true, mayEdit = true)
            grants.provision(id, grant)
            val opened = HostNotebookAccess(notebooks, grants, "peer").opener.open(id)
            val session = assertIs<DocumentWorkspaceOpen.Opened>(opened).sessions.note
            session.refresh()
            session.edit("accepted")
            fail.set(true)
            assertFalse(session.save())
            assertTrue(session.state.value.pendingDurability)
            grants.revoke("peer", id)
            fail.set(false)
            assertFalse(session.retryPersistence())
            assertTrue(session.state.value.pendingDurability)
            assertEquals("accepted", notebooks.read(storageId)?.markdown)
            grants.provision(id, grant)
            assertTrue(session.retryPersistence())
            assertFalse(session.state.value.dirty)
        }
    }

}
