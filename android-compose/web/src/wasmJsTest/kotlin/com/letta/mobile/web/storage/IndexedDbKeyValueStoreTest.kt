package com.letta.mobile.web.storage

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.KeyValueCanvasStorage
import com.letta.mobile.data.model.CanvasId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** letta-mobile-ckmub.17.7: the browser's IndexedDB binding, and a board kept in it across store instances (a reload). */
class IndexedDbKeyValueStoreTest {
    private val database = "letta-web-test"

    @Test
    fun valuesRoundTripAndKeysFilterByPrefix() = runTest {
        val store = IndexedDbKeyValueStore(databaseName = database)
        store.put("a/1", "one")
        store.put("b/1", "two")

        assertEquals("one", IndexedDbKeyValueStore(databaseName = database).get("a/1"))
        assertEquals(listOf("a/1"), store.keys("a/"))

        store.remove("a/1")
        store.remove("b/1")
        assertNull(store.get("a/1"))
    }

    @Test
    fun aBoardSurvivesANewStoreOverTheSameDatabase() = runTest {
        val board = CanvasDocument(CanvasId("web-board"), conversationId = "conv-web", title = "Board", revision = 1)
        KeyValueCanvasStorage(IndexedDbKeyValueStore(databaseName = database)).documents.upsert(board)

        val reloaded = KeyValueCanvasStorage(IndexedDbKeyValueStore(databaseName = database)).documents

        assertEquals(board, reloaded.get(CanvasId("web-board")))
        IndexedDbKeyValueStore(databaseName = database).remove(KeyValueCanvasStorage.BOARD_PREFIX + "web-board")
    }
}
