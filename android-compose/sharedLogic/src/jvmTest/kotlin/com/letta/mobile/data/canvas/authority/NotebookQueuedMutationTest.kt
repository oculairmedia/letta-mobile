package com.letta.mobile.data.canvas.authority

import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.canvas.NotebookPeerGrant
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotebookQueuedMutationTest {
    @Test
    fun cancelledQueuedMutationCannotExecuteAfterGrantRevocation() = queuedMutation(cancel = true)

    @Test
    fun timedOutQueuedMutationCannotExecuteAfterGrantRevocation() = queuedMutation(cancel = false)

    private suspend fun awaitQueuedWriter(writer: AtomicReference<Thread>) = withTimeout(5_000) {
        while (writer.get()?.stackTrace?.any {
            it.className.endsWith("NotebookLocalStore") && it.methodName == "await"
        } != true) delay(10)
    }

    private fun queuedMutation(cancel: Boolean) = runBlocking {
        assumeDurableGrantHost()
        val root = Files.createTempDirectory("notebook-queued-grant")
        NotebookLocalStore(root.resolve("notebooks"), "host").use { notebooks ->
            val storageId = notebooks.create("Note")
            val id = requireNotNull(notebooks.read(storageId)).id
            val grants = FileNotebookGrantStore(root.resolve("grants.json"))
            grants.provision(id, NotebookPeerGrant("peer", mayRead = true, mayEdit = true))
            HostNotebookAccess(notebooks, grants, "peer")
            val handle = requireNotNull(notebooks.open(storageId))
            val entered = CompletableFuture<Unit>()
            val pending = CopyOnWriteArrayList<Runnable>()
            val runtime = notebooks.repo.javaClass.getDeclaredField("runtime").apply { isAccessible = true }.get(notebooks.repo)
            val serial = runtime.javaClass.getDeclaredField("serializingDocumentExecutor")
                .apply { isAccessible = true }.get(runtime)
            val delegateField = serial.javaClass.getDeclaredField("delegate").apply { isAccessible = true }
            val original = delegateField.get(serial) as Executor
            // Pause before entering native document code, like a saturated executor. The real
            // callback and transaction remain queued, without holding the native actor mutex.
            delegateField.set(serial, Executor { pending.add(it); entered.complete(Unit) })
            try {
                val writer = AtomicReference<Thread>()
                val mutation = async(Dispatchers.IO) {
                    grants.withGrant("peer", id) { grant ->
                        assertTrue(grant?.mayEdit == true)
                        runCatching {
                            runInterruptible {
                                writer.set(Thread.currentThread())
                                notebooks.replaceMarkdown(storageId, "", "late edit")
                            }
                        }.exceptionOrNull()
                    }
                }
                withContext(Dispatchers.IO) { entered.get(5, TimeUnit.SECONDS) }
                awaitQueuedWriter(writer)
                if (cancel) mutation.cancelAndJoin() else assertTrue(mutation.await() is TimeoutException)
                grants.revoke("peer", id)
                delegateField.set(serial, original)
                pending.toList().also { pending.clear() }.forEach(original::execute)
                withContext(Dispatchers.IO) { handle.withDocument { }.get(5, TimeUnit.SECONDS) }
                assertEquals("", notebooks.read(storageId)?.markdown,
                    "A callback queued before revocation must not mutate after its authorization lock was released")
            } finally {
                delegateField.set(serial, original)
                pending.toList().also { pending.clear() }.forEach(original::execute)
            }
        }
    }
}
