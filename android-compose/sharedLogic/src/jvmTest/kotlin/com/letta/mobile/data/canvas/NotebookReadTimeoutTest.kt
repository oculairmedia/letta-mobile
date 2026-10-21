package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.CanvasId
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.runBlocking
import org.automerge.repo.Storage
import org.automerge.repo.StorageKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NotebookReadTimeoutTest {
    @Test
    fun timedOutReadPreservesTheDocumentAndCanBeRetried() = runBlocking {
        val path = Files.createTempDirectory("notebook-read-timeout-")
        val board = CanvasDocument(id = CanvasId("keep-on-timeout"), title = "Durable board")
        NotebookLocalStore(path, "timeout-test").use { notebooks ->
            NotebookCanvasDocumentStore(notebooks).upsert(board)
        }
        val release = CompletableFuture<Unit>()
        val delayed = { delegate: Storage ->
            object : Storage by delegate {
                override fun loadRange(key: StorageKey): CompletableFuture<Map<StorageKey, ByteArray>> =
                    release.thenCompose { delegate.loadRange(key) }
            }
        }
        try {
            NotebookLocalStore(path, "timeout-test", storage = delayed).use { notebooks ->
                try {
                    val id = notebooks.listDocuments().single()
                    val timeout = assertFailsWith<TimeoutException> { notebooks.open(id) }
                    assertTrue(timeout.message.orEmpty().contains("did not load within"))
                    val fault = notebooks.storageFaults.value.single { it.kind == CanvasStorageFault.Kind.LOAD_FAILED }
                    assertNotNull(fault)
                    assertEquals(listOf(id), notebooks.listDocuments())
                    release.complete(Unit)
                    assertNotNull(notebooks.open(id))
                    assertEquals(board.title, NotebookCanvasDocumentStore(notebooks).get(board.id)?.title)
                } finally {
                    release.complete(Unit)
                }
            }
            NotebookLocalStore(path, "timeout-test").use { notebooks ->
                assertEquals(board.title, NotebookCanvasDocumentStore(notebooks).get(board.id)?.title)
            }
        } finally {
            path.toFile().deleteRecursively()
        }
    }
}
