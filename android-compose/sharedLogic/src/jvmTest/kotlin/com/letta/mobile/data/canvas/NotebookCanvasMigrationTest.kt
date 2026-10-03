package com.letta.mobile.data.canvas

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.automerge.repo.DocumentId

class NotebookCanvasMigrationTest {
    private val source = CanvasDocument(CanvasId("legacy-a"), title = "Legacy", sceneJson = """{"bgColor":"#ffffffff","elements":[]}""")

    @Test
    fun interruptedImportKeepsClaimAndRetriesSameTarget() {
        val dir = Files.createTempDirectory("mapping-retry-")
        val mappings = NotebookCanvasMappingStore(dir)
        NotebookLocalStore(dir, "mapping-peer").use { store ->
            val target = store.create("Empty")
            val other = store.create("Other")
            val interrupted = runCatching {
                mappings.migrate(source, target) { throw IllegalStateException("interrupted before import") }
            }
            assertEquals("interrupted before import", interrupted.exceptionOrNull()?.message)
            assertEquals(NotebookCanvasMapping(source.id, target, false), mappings.lookup(source.id))
            val migration = NotebookCanvasMigration(store, NotebookCanvasMappingStore(dir))
            assertEquals(NotebookCanvasImportResult.CONFLICT, migration.migrate(source, other))
            assertEquals(NotebookCanvasImportResult.CONFLICT, migration.migrate(source.copy(title = "Different"), target))
            assertEquals(NotebookCanvasImportResult.CONFLICT, migration.migrate(source.copy(id = CanvasId("other")), target))
            assertEquals("Empty", store.read(target)?.title)
        }
        NotebookLocalStore(dir, "mapping-peer").use { store ->
            val target = assertNotNull(NotebookCanvasMappingStore(dir).lookup(source.id)).target
            val migration = NotebookCanvasMigration(store, NotebookCanvasMappingStore(dir))
            assertEquals(NotebookCanvasImportResult.IMPORTED, migration.migrate(source, target))
            assertEquals(NotebookCanvasImportResult.ALREADY_IMPORTED, migration.migrate(source, target))
            assertEquals(NotebookCanvasMapping(source.id, target, true), mappings.lookup(source.id))
            assertEquals("Legacy", store.read(target)?.title)
        }
    }

    @Test
    fun interruptionAfterBridgeCommitRetriesWithoutReimporting() {
        val dir = Files.createTempDirectory("mapping-after-import-")
        NotebookLocalStore(dir, "after-import-peer").use { store ->
            val target = store.create("Empty")
            val mappings = NotebookCanvasMappingStore(dir)
            val interrupted = runCatching {
                mappings.migrate(source, target) {
                    assertEquals(NotebookCanvasImportResult.IMPORTED, NotebookCanvasBridge(store).import(source, target))
                    throw IllegalStateException("interrupted after import")
                }
            }
            assertEquals("interrupted after import", interrupted.exceptionOrNull()?.message)
            assertEquals(NotebookCanvasMapping(source.id, target, false), mappings.lookup(source.id))
            assertEquals(NotebookCanvasImportResult.ALREADY_IMPORTED, NotebookCanvasMigration(store, mappings).migrate(source, target))
            assertEquals(NotebookCanvasMapping(source.id, target, true), mappings.lookup(source.id))
        }
    }

    @Test
    fun bridgeConflictNeverCompletesClaimOrOverwritesEditedNotebook() {
        val dir = Files.createTempDirectory("mapping-conflict-")
        NotebookLocalStore(dir, "conflict-peer").use { store ->
            val target = store.create("Existing")
            store.insertMarkdown(target, 0, "keep me")
            val mappings = NotebookCanvasMappingStore(dir)
            val migration = NotebookCanvasMigration(store, mappings)
            assertEquals(NotebookCanvasImportResult.CONFLICT, migration.migrate(source, target))
            assertEquals(NotebookCanvasMapping(source.id, target, false), mappings.lookup(source.id))
            assertEquals(NotebookCanvasImportResult.CONFLICT, migration.migrate(source, target))
            assertEquals("keep me", store.read(target)?.markdown)
            assertEquals(NotebookCanvasImportResult.CONFLICT, migration.migrate(source.copy(sceneJson = "{}"), target))
            assertFalse(assertNotNull(mappings.lookup(source.id)).completed)
            val other = store.create("Unused")
            assertNull(mappings.lookup(CanvasId("unclaimed")))
            assertEquals(NotebookCanvasImportResult.CONFLICT, migration.migrate(source, other))
        }
    }

    @Test
    fun concurrentInstancesCannotClaimSameTargetTwice() {
        val dir = Files.createTempDirectory("mapping-concurrent-")
        NotebookLocalStore(dir, "concurrent-peer").use { store ->
            val target: DocumentId = store.create("Empty")
            val results = (0 until 8).map { index ->
                ThreadResult {
                    NotebookCanvasMigration(store, NotebookCanvasMappingStore(dir)).migrate(
                        source.copy(id = CanvasId("source-$index")), target,
                    )
                }
            }
            results.forEach { it.start() }
            val values = results.map { it.result() }
            assertEquals(1, values.count { it == NotebookCanvasImportResult.IMPORTED })
            assertEquals(7, values.count { it == NotebookCanvasImportResult.CONFLICT })
        }
    }

    private class ThreadResult(private val action: () -> NotebookCanvasImportResult) : Thread() {
        private var value: Result<NotebookCanvasImportResult>? = null
        override fun run() { value = runCatching(action) }
        fun result(): NotebookCanvasImportResult {
            join(20000)
            check(!isAlive) { "Migration deadlocked" }
            return checkNotNull(value).getOrThrow()
        }
    }
}
