package com.letta.mobile.data.canvas

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanvasArchiveTest {
    private fun doc(id: String) = CanvasDocument(id = CanvasId(id), title = id)

    @Test
    fun theFilterSplitsTheLibraryAndKeepsItsOrder() {
        val docs = listOf(doc("c"), doc("a"), doc("b"))
        val archived = setOf(CanvasId("a"))
        assertEquals(listOf("c", "b"), CanvasLibrary.filter(docs, archived, CanvasArchiveFilter.ACTIVE).map { it.id.value })
        assertEquals(listOf("a"), CanvasLibrary.filter(docs, archived, CanvasArchiveFilter.ARCHIVED).map { it.id.value })
        assertEquals(listOf("c", "a", "b"), CanvasLibrary.filter(docs, archived, CanvasArchiveFilter.ALL).map { it.id.value })
    }

    @Test
    fun archivingAndRestoring() = runTest {
        val store = InMemoryCanvasArchiveStore()
        store.setArchived(CanvasId("a"), true)
        store.setArchived(CanvasId("b"), true)
        store.setArchived(CanvasId("a"), false)
        assertEquals(setOf(CanvasId("b")), store.archivedIds())
        store.setArchived(CanvasId("b"), false)
        assertTrue(store.archivedIds().isEmpty())
    }
}
