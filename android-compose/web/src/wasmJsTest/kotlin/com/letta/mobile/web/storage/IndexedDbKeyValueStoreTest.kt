package com.letta.mobile.web.storage

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.KeyValueCanvasStorage
import com.letta.mobile.data.model.CanvasId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

/**
 * Exercises the browser's production IndexedDB adapter through [KeyValueCanvasStorage.documents].
 * The assertions mirror the common DocumentStoreContract semantics without adding a
 * test-source dependency from this module on sharedLogic's common test source set.
 */
class IndexedDbKeyValueStoreTest {
    @Test
    fun valuesRoundTripAndKeysFilterByPrefix() = runTest {
        withTestDatabase { database, store ->
            store.put("a/1", "one")
            store.put("b/1", "two")

            assertEquals("one", IndexedDbKeyValueStore(databaseName = database).get("a/1"))
            assertEquals(listOf("a/1"), store.keys("a/"))

            store.remove("a/1")
            store.remove("b/1")
            assertNull(store.get("a/1"))
        }
    }

    @Test
    fun productionDocumentsReadMissingAndUpsertCreatesAndReplaces() = runTest {
        withTestDatabase { database, backing ->
            val storage = KeyValueCanvasStorage(backing)
            val documents = storage.documents

            assertNull(documents.get(CanvasId("missing")))
            documents.upsert(document("replace", revision = 1, updatedAtEpochMs = 10, title = "first"))
            documents.upsert(document("replace", revision = 2, updatedAtEpochMs = 20, title = "second"))

            val replaced = assertNotNull(documents.get(CanvasId("replace")))
            assertEquals(2L, replaced.revision)
            assertEquals("second", replaced.title)
            assertNoStorageFaults(storage)

            val reopenedStorage = KeyValueCanvasStorage(IndexedDbKeyValueStore(databaseName = database))
            val persistedReplacement = assertNotNull(reopenedStorage.documents.get(CanvasId("replace")))
            assertEquals(2L, persistedReplacement.revision)
            assertEquals("second", persistedReplacement.title)
            assertNoStorageFaults(reopenedStorage)
        }
    }

    @Test
    fun productionDocumentsCompareAndSetOnlyTheCurrentRevision() = runTest {
        withTestDatabase { database, backing ->
            val storage = KeyValueCanvasStorage(backing)
            val documents = storage.documents

            assertFalse(
                documents.upsertIfRevision(
                    document("new", revision = 1, updatedAtEpochMs = 1, title = "not-created"),
                    expectedRevision = 0,
                ),
            )
            assertNull(documents.get(CanvasId("new")))

            documents.upsert(document("current", revision = 1, updatedAtEpochMs = 1, title = "base"))
            assertTrue(
                documents.upsertIfRevision(
                    document("current", revision = 2, updatedAtEpochMs = 2, title = "winner"),
                    expectedRevision = 1,
                ),
            )
            assertFalse(
                documents.upsertIfRevision(
                    document("current", revision = 2, updatedAtEpochMs = 3, title = "stale"),
                    expectedRevision = 1,
                ),
            )
            assertEquals("winner", assertNotNull(documents.get(CanvasId("current"))).title)
            assertNoStorageFaults(storage)

            val reopenedStorage = KeyValueCanvasStorage(IndexedDbKeyValueStore(databaseName = database))
            assertEquals("winner", assertNotNull(reopenedStorage.documents.get(CanvasId("current"))).title)
            assertNoStorageFaults(reopenedStorage)
        }
    }

    @Test
    fun sameInstanceConcurrentCompareAndSetHasOneWinner() = runTest {
        withTestDatabase { database, backing ->
            val storage = KeyValueCanvasStorage(backing)
            val documents = storage.documents
            documents.upsert(document("contended", revision = 1, updatedAtEpochMs = 1, title = "base"))

            val bothWritersReady = CompletableDeferred<Unit>()
            val releaseWriters = CompletableDeferred<Unit>()
            var readyWriters = 0
            suspend fun writeAfterBarrier(title: String): Boolean {
                readyWriters += 1
                if (readyWriters == 2) bothWritersReady.complete(Unit)
                releaseWriters.await()
                return documents.upsertIfRevision(
                    document("contended", revision = 2, updatedAtEpochMs = 2, title = title),
                    expectedRevision = 1,
                )
            }

            val writers = listOf("left", "right").map { title -> async { writeAfterBarrier(title) } }
            bothWritersReady.await()
            releaseWriters.complete(Unit)

            val landed = writers.awaitAll()
            assertEquals(1, landed.count { it })
            val winner = assertNotNull(documents.get(CanvasId("contended")))
            assertEquals(2L, winner.revision)
            assertTrue(winner.title == "left" || winner.title == "right")
            assertNoStorageFaults(storage)

            val reopenedStorage = KeyValueCanvasStorage(IndexedDbKeyValueStore(databaseName = database))
            assertEquals(winner, reopenedStorage.documents.get(winner.id))
            assertNoStorageFaults(reopenedStorage)
        }
    }

    @Test
    fun productionDocumentsListNewestUpdateFirst() = runTest {
        withTestDatabase { database, backing ->
            val storage = KeyValueCanvasStorage(backing)
            val documents = storage.documents
            documents.upsert(document("old", revision = 1, updatedAtEpochMs = 100, title = "old"))
            documents.upsert(document("new", revision = 1, updatedAtEpochMs = 300, title = "new"))
            documents.upsert(document("mid", revision = 1, updatedAtEpochMs = 200, title = "mid"))

            val reopenedStorage = KeyValueCanvasStorage(IndexedDbKeyValueStore(databaseName = database))
            assertEquals(listOf("new", "mid", "old"), reopenedStorage.documents.listAll().map { it.title })
            assertNoStorageFaults(storage)
            assertNoStorageFaults(reopenedStorage)
        }
    }

    @Test
    fun aDocumentSurvivesReopeningAStoreOverTheSameDatabase() = runTest {
        withTestDatabase { database, _ ->
            val board = document("web-board", revision = 4, updatedAtEpochMs = 42, title = "Persisted board")
            val firstStorage = KeyValueCanvasStorage(IndexedDbKeyValueStore(databaseName = database))
            firstStorage.documents.upsert(board)
            assertNoStorageFaults(firstStorage)

            val reopenedStorage = KeyValueCanvasStorage(IndexedDbKeyValueStore(databaseName = database))
            assertEquals(board, reopenedStorage.documents.get(board.id))
            assertNoStorageFaults(reopenedStorage)
        }
    }

    private fun document(key: String, revision: Long, updatedAtEpochMs: Long, title: String) =
        CanvasDocument(
            id = CanvasId(key),
            title = title,
            revision = revision,
            updatedAtEpochMs = updatedAtEpochMs,
        )

    private fun assertNoStorageFaults(storage: KeyValueCanvasStorage) {
        assertEquals(emptyList(), storage.storageFaults.value)
    }

    /** Each test gets an isolated database and removes all its records even after an assertion fails. */
    private suspend fun <T> withTestDatabase(block: suspend (String, IndexedDbKeyValueStore) -> T): T {
        val database = uniqueTestDb()
        val cleanupStore = IndexedDbKeyValueStore(databaseName = database)
        try {
            return block(database, cleanupStore)
        } finally {
            cleanupStore.keys(prefix = "").forEach { key -> cleanupStore.remove(key) }
        }
    }

    private fun uniqueTestDb(): String = "letta-web-test-${Random.nextLong().toULong().toString(16)}"
}
