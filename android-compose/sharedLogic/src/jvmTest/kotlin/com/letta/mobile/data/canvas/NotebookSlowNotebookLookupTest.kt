package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.notes.NotebookCanvasIdentity
import com.letta.mobile.data.model.CanvasId
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.automerge.repo.Storage
import org.automerge.repo.StorageKey

/**
 * One notebook that will not load must not break lookups of canvases in other notebooks, and
 * must never make the store answer "absent" for a canvas whose own notebook was only slow.
 */
class NotebookSlowNotebookLookupTest {
    @Test
    fun aSlowNotebookIsPassedOverButNeverReportedAbsent() = runBlocking {
        val path = Files.createTempDirectory("notebook-slow-lookup-")
        val (slowCanvas, fastCanvas) = seed(path)
        val slowStorage = NotebookHistoryArchive(path).storageName(notebookKeyOf(path, slowCanvas))
        val release = CompletableFuture<Unit>()
        val hold = { delegate: Storage -> HeldStorage(delegate, setOf(slowStorage), release) }

        try {
            NotebookLocalStore(path, "slow-lookup-peer", storage = hold).use { notebooks ->
                val store = NotebookCanvasDocumentStore(notebooks)
                val indexed = notebooks.listDocuments().size

                assertEquals(fastCanvas, store.get(fastCanvas)?.id, "a slow unrelated notebook must not hide this canvas")
                val listed = store.listAll().map { it.id }
                assertEquals(listOf(fastCanvas), listed, "listing skips the slow notebook instead of failing")

                val identity = NotebookCanvasIdentity(notebooks)
                val elapsed = measureTimeMillis { assertTrue(identity.notebookOf(fastCanvas) != null) }
                assertTrue(elapsed < HINTED_LOOKUP_MS, "a canvas seen this session resolves from its hint, took ${elapsed}ms")

                assertFailsWith<TimeoutException>("a canvas whose own notebook is slow is unknown, not absent") {
                    store.get(slowCanvas)
                }
                assertEquals(indexed, notebooks.listDocuments().size, "no second notebook was created")
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun aLookupStopsOnceItsScanBudgetIsSpent() = runBlocking {
        val path = Files.createTempDirectory("notebook-slow-budget-")
        val canvases = seedCanvases(path, count = 3)
        val held = canvases.take(2).map { NotebookHistoryArchive(path).storageName(notebookKeyOf(path, it)) }.toSet()
        val release = CompletableFuture<Unit>()

        try {
            NotebookLocalStore(path, "slow-lookup-peer", storage = { HeldStorage(it, held, release) }).use { notebooks ->
                val elapsed = measureTimeMillis {
                    assertFailsWith<TimeoutException>("two slow notebooks spend the budget before the third is reached") {
                        notebooks.notebookHolding(canvases[2])
                    }
                }
                assertTrue(elapsed < SCAN_BUDGET_MS + LOAD_TIMEOUT_MS, "stopped near the budget, took ${elapsed}ms")
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun aReadStartedNearTheDeadlineWaitsOnlyForWhatIsLeft() = runBlocking {
        val path = Files.createTempDirectory("notebook-slow-deadline-")
        val canvases = seedCanvases(path, count = 3)
        val (fullWait, cutWait) = canvases.take(2).map { notebookKeyOf(path, it) }
        val held = setOf(fullWait, cutWait).map { NotebookHistoryArchive(path).storageName(it) }.toSet()
        val release = CompletableFuture<Unit>()

        try {
            NotebookLocalStore(path, "slow-lookup-peer", storage = { HeldStorage(it, held, release) }).use { notebooks ->
                val budget = SHORT_BUDGET_MS.milliseconds
                val elapsed = measureTimeMillis {
                    assertFailsWith<TimeoutException> { notebooks.firstNotebook(budget) { it.id == canvases[2] } }
                }
                // The first stuck notebook takes its full 10 s; the second may only use the 5 s left.
                assertTrue(elapsed < SHORT_BUDGET_MS + DEADLINE_SLACK_MS, "the second wait was cut to the budget, took ${elapsed}ms")

                val slow = notebooks.storageFaults.value.filter { it.kind == CanvasStorageFault.Kind.LOAD_FAILED }.map { it.documentId }
                assertTrue(fullWait in slow, "a notebook that used its whole limit is reported slow")
                assertTrue(cutWait !in slow, "a wait the scan budget cut short is not the notebook's fault")
            }
        } finally {
            release.complete(Unit)
        }
    }

    private suspend fun seed(path: Path): Pair<CanvasId, CanvasId> = seedCanvases(path, count = 2).let { it[0] to it[1] }

    private suspend fun seedCanvases(path: Path, count: Int): List<CanvasId> =
        NotebookLocalStore(path, "slow-lookup-peer").use { notebooks -> createCanvases(notebooks, count) }

    private suspend fun createCanvases(notebooks: NotebookLocalStore, count: Int): List<CanvasId> {
        val store = NotebookCanvasDocumentStore(notebooks)
        val created = List(count) { CanvasSession.create(store).canvasId }
        notebooks.listDocuments().forEach { notebooks.persistCanvas(it) }
        return created
    }

    private fun notebookKeyOf(path: Path, canvasId: CanvasId): String = NotebookLocalStore(path, "slow-lookup-peer").use { notebooks ->
        val id = requireNotNull(notebooks.notebookHolding(canvasId))
        id.bytes.joinToString("") { "%02x".format(it) }
    }

    /** Storage whose loads for one document never complete until [release]. */
    private class HeldStorage(
        private val delegate: Storage,
        private val heldNames: Set<String>,
        private val release: CompletableFuture<Unit>,
    ) : Storage by delegate {
        private fun held(key: StorageKey) = key.parts.firstOrNull() in heldNames

        override fun load(key: StorageKey): CompletableFuture<Optional<ByteArray>> =
            if (held(key)) release.thenCompose { delegate.load(key) } else delegate.load(key)

        override fun loadRange(prefix: StorageKey): CompletableFuture<MutableMap<StorageKey, ByteArray>> =
            if (held(prefix)) release.thenCompose { delegate.loadRange(prefix) } else delegate.loadRange(prefix)
    }

    private companion object {
        const val HINTED_LOOKUP_MS = 5_000L

        /** NotebookLocalStore's scan budget and per-notebook load limit (20 s and 10 s). */
        const val SCAN_BUDGET_MS = 20_000L
        const val LOAD_TIMEOUT_MS = 10_000L
        const val SHORT_BUDGET_MS = 15_000L
        const val DEADLINE_SLACK_MS = 2_000L
    }
}
