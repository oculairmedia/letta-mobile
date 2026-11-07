package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.CanvasId
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.CompletableFuture
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.automerge.repo.Storage
import org.automerge.repo.StorageKey

/**
 * After a restart, a canvas lookup tries the notebook saved for it instead of scanning every
 * notebook; a wrong or unreadable hints file only falls back to the scan.
 */
class NotebookCanvasHintsTest {
    @Test
    fun aRestartedStoreFindsACanvasWithoutScanningOtherNotebooks() = runBlocking {
        val path = Files.createTempDirectory("notebook-hints-restart-")
        val (first, second) = seed(path)
        val stuck = NotebookHistoryArchive(path).storageName(keyOf(path, first))
        val release = CompletableFuture<Unit>()

        try {
            NotebookLocalStore(path, PEER, storage = { HeldStorage(it, stuck, release) }).use { notebooks ->
                val elapsed = measureTimeMillis { assertEquals(keyOf(path, second), notebooks.notebookHolding(second)?.key()) }
                assertTrue(elapsed < HINTED_LOOKUP_MS, "the saved hint answered without touching the stuck notebook, took ${elapsed}ms")
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun aHintPointingAtTheWrongNotebookFallsBackToTheScanAndIsCorrected() = runBlocking {
        val path = Files.createTempDirectory("notebook-hints-stale-")
        val (first, second) = seed(path)
        val firstKey = keyOf(path, first)
        val secondKey = keyOf(path, second)
        path.writeCanvasHints(mapOf(second.value to firstKey))

        NotebookLocalStore(path, PEER).use { notebooks -> assertEquals(secondKey, notebooks.notebookHolding(second)?.key()) }

        assertEquals(secondKey, path.readCanvasHints()[second.value])
    }

    @Test
    fun anUnreadableHintsFileOnlyCostsAScan() = runBlocking {
        val path = Files.createTempDirectory("notebook-hints-corrupt-")
        val (_, second) = seed(path)
        Files.writeString(path.resolve(CANVAS_HINTS_FILE), "not json")

        NotebookLocalStore(path, PEER).use { notebooks -> assertEquals(keyOf(path, second), notebooks.notebookHolding(second)?.key()) }
    }

    private suspend fun seed(path: Path): Pair<CanvasId, CanvasId> = NotebookLocalStore(path, PEER).use { notebooks ->
        val store = NotebookCanvasDocumentStore(notebooks)
        val first = CanvasSession.create(store).canvasId
        val second = CanvasSession.create(store).canvasId
        notebooks.listDocuments().forEach { notebooks.persistCanvas(it) }
        first to second
    }

    /** Resolved by a fresh store with no hints file, so the answer comes from the notebooks themselves. */
    private fun keyOf(path: Path, canvasId: CanvasId): String {
        val scratch = Files.createTempDirectory("notebook-hints-copy-")
        path.toFile().copyRecursively(scratch.toFile(), overwrite = true)
        Files.deleteIfExists(scratch.resolve(CANVAS_HINTS_FILE))
        return NotebookLocalStore(scratch, PEER).use { requireNotNull(it.notebookHolding(canvasId)).key() }
    }

    private fun org.automerge.repo.DocumentId.key(): String = bytes.joinToString("") { "%02x".format(it) }

    /** Storage whose loads for one document never complete until [release]. */
    private class HeldStorage(
        private val delegate: Storage,
        private val heldName: String,
        private val release: CompletableFuture<Unit>,
    ) : Storage by delegate {
        private fun held(key: StorageKey) = key.parts.firstOrNull() == heldName

        override fun load(key: StorageKey): CompletableFuture<Optional<ByteArray>> =
            if (held(key)) release.thenCompose { delegate.load(key) } else delegate.load(key)

        override fun loadRange(prefix: StorageKey): CompletableFuture<MutableMap<StorageKey, ByteArray>> =
            if (held(prefix)) release.thenCompose { delegate.loadRange(prefix) } else delegate.loadRange(prefix)
    }

    private companion object {
        const val PEER = "hints-peer"
        const val HINTED_LOOKUP_MS = 5_000L
    }
}
