package com.letta.mobile.data.canvas

import com.letta.mobile.data.document.DocumentStore
import com.letta.mobile.data.document.DocumentStoreContract
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The Automerge notebook canvas store passes the shared-document store contract. */
class NotebookCanvasStoreContractTest : DocumentStoreContract<CanvasId, CanvasDocument>() {
    private val opened = mutableListOf<NotebookLocalStore>()

    override suspend fun newStore(): DocumentStore<CanvasId, CanvasDocument> {
        val notebooks = NotebookLocalStore(Files.createTempDirectory("shared-doc-contract-"), "contract-peer")
        opened += notebooks
        return NotebookCanvasDocumentStore(notebooks)
    }

    override fun document(key: String, revision: Long, updatedAtEpochMs: Long, payload: String) = CanvasDocument(
        id = CanvasId(key),
        title = payload,
        revision = revision,
        sceneJson = CanvasOpProjector.emptySceneJson(),
        updatedAtEpochMs = updatedAtEpochMs,
    )

    override fun payloadOf(doc: CanvasDocument): String = doc.title

    @AfterTest
    fun close() {
        opened.forEach { it.close() }
    }
}

/** A board opened through the primitive persists in the notebook format a [CanvasSession] reads back. */
class NotebookSharedDocumentTest {
    @Test
    fun aSharedCanvasDocumentPersistsThroughTheAutomergeStoreAndReopensInASession() = runBlocking {
        val canvasId = CanvasId("shared-doc-notebook")
        val ops = listOf(
            CanvasOp.AddElementOp("add-a", "local_user", 1, "a", """{"id":"a","text":"kept"}"""),
            CanvasOp.AddElementOp("add-b", "local_user", 2, "b", """{"id":"b"}"""),
            CanvasOp.RemoveElementOp("remove-b", "local_user", 3, "b"),
            CanvasOp.SetBackgroundOp("bg", "local_user", 4, "#abcdef"),
        )
        val options = CanvasCreateOptions(
            canvasId = canvasId, title = "Board", acl = CanvasAcl(ownerUserId = "local_user"),
            initialSceneJson = CanvasOpProjector.emptySceneJson(), clock = { 7L },
        )

        // The board as a session writes it today.
        val sessionPath = Files.createTempDirectory("shared-doc-session-")
        val created = NotebookLocalStore(sessionPath, "canvas-peer").use { notebooks ->
            val session = CanvasSession.create(NotebookCanvasDocumentStore(notebooks), options)
            val created = assertNotNull(session.document.value)
            ops.forEach { session.applyLocal(it) }
            created
        }

        // The same board written through the primitive.
        val documentPath = Files.createTempDirectory("shared-doc-primitive-")
        NotebookLocalStore(documentPath, "canvas-peer").use { notebooks ->
            val doc = openCanvasDocument(
                canvasId, NotebookCanvasDocumentStore(notebooks), InMemoryCanvasOpLog(),
                create = { created }, clock = { 7L },
            )
            ops.forEach { doc.apply(it) }
            assertEquals(emptyList(), doc.storageFaults.value.map { it.message })
        }

        val reopened = listOf(sessionPath, documentPath).map { path ->
            NotebookLocalStore(path, "canvas-peer").use { notebooks ->
                assertNotNull(NotebookCanvasDocumentStore(notebooks).get(canvasId))
            }
        }
        assertEquals(reopened[0].revision, reopened[1].revision)
        assertEquals(reopened[0].title, reopened[1].title)
        assertEquals(reopened[0].acl, reopened[1].acl)
        assertEquals(CanvasSceneDigest.of(reopened[0].sceneJson), CanvasSceneDigest.of(reopened[1].sceneJson))
    }
}
