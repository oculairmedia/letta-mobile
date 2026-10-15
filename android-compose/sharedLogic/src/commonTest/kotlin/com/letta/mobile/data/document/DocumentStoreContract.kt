package com.letta.mobile.data.document

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.document.markdown.MarkdownDocId
import com.letta.mobile.data.document.markdown.MarkdownDocument
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What every [DocumentStore] must do, whatever the document kind. The in-memory stores run it
 * here; the Automerge notebook canvas store runs the same suite on the JVM.
 */
abstract class DocumentStoreContract<Id, D : DocumentSnapshot<Id>> {
    /** A fresh, empty store. */
    abstract suspend fun newStore(): DocumentStore<Id, D>

    /** A document under [key] at [revision], carrying [payload] where the kind keeps content. */
    abstract fun document(key: String, revision: Long, updatedAtEpochMs: Long, payload: String): D

    /** The [payload] [document] put in [doc]. */
    abstract fun payloadOf(doc: D): String

    @Test
    fun aMissingDocumentReadsAsNull() = runTest {
        assertNull(newStore().get(document("absent", 1, 1, "x").id))
    }

    @Test
    fun upsertCreatesAndReplaces() = runTest {
        val store = newStore()
        store.upsert(document("a", 1, 10, "first"))
        store.upsert(document("a", 2, 20, "second"))
        val stored = assertNotNull(store.get(document("a", 0, 0, "").id))
        assertEquals(2, stored.revision)
        assertEquals("second", payloadOf(stored))
    }

    @Test
    fun compareAndSetNeverCreates() = runTest {
        val store = newStore()
        assertFalse(store.upsertIfRevision(document("new", 1, 1, "x"), expectedRevision = 0))
        assertNull(store.get(document("new", 0, 0, "").id))
    }

    @Test
    fun compareAndSetLandsOnlyOnTheExpectedRevision() = runTest {
        val store = newStore()
        store.upsert(document("a", 1, 1, "base"))
        assertTrue(store.upsertIfRevision(document("a", 2, 2, "winner"), expectedRevision = 1))
        assertFalse(store.upsertIfRevision(document("a", 2, 3, "stale"), expectedRevision = 1))
        assertEquals("winner", payloadOf(assertNotNull(store.get(document("a", 0, 0, "").id))))
    }

    @Test
    fun twoWritersFromOneRevisionLandExactlyOnce() = runTest {
        val store = newStore()
        store.upsert(document("a", 1, 1, "base"))
        val landed = listOf("left", "right").map { payload ->
            async { store.upsertIfRevision(document("a", 2, 2, payload), expectedRevision = 1) }
        }.awaitAll()
        assertEquals(1, landed.count { it })
    }

    @Test
    fun theLibraryListsMostRecentlyUpdatedFirst() = runTest {
        val store = newStore()
        store.upsert(document("old", 1, 100, "o"))
        store.upsert(document("new", 1, 300, "n"))
        store.upsert(document("mid", 1, 200, "m"))
        assertEquals(listOf("n", "m", "o"), store.listAll().map(::payloadOf))
    }
}

class MarkdownInMemoryStoreContractTest : DocumentStoreContract<MarkdownDocId, MarkdownDocument>() {
    override suspend fun newStore(): DocumentStore<MarkdownDocId, MarkdownDocument> = InMemoryDocumentStore()

    override fun document(key: String, revision: Long, updatedAtEpochMs: Long, payload: String) =
        MarkdownDocument(MarkdownDocId(key), markdown = payload, revision = revision, updatedAtEpochMs = updatedAtEpochMs)

    override fun payloadOf(doc: MarkdownDocument): String = doc.markdown
}

class CanvasInMemoryStoreContractTest : DocumentStoreContract<CanvasId, CanvasDocument>() {
    override suspend fun newStore(): DocumentStore<CanvasId, CanvasDocument> = InMemoryCanvasDocumentStore()

    override fun document(key: String, revision: Long, updatedAtEpochMs: Long, payload: String) =
        CanvasDocument(CanvasId(key), title = payload, revision = revision, updatedAtEpochMs = updatedAtEpochMs)

    override fun payloadOf(doc: CanvasDocument): String = doc.title
}
