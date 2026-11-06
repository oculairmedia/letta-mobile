package com.letta.mobile.data.canvas

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.automerge.AmValue
import org.automerge.ObjectId
import org.automerge.Read
import org.automerge.Transaction
import org.automerge.repo.DocumentId

/**
 * A field a newer build adds to canvas metadata or to a notebook item, arriving through sync, must
 * not stop this build from opening the notebook, and must survive this build's own later writes.
 */
class NotebookForwardCompatibleFieldsTest {
    @Test
    fun canvasMetadataWithANewerFieldStillOpensAndKeepsTheField() = runBlocking {
        val path = Files.createTempDirectory("notebook-newer-metadata-")
        NotebookLocalStore(path, "older-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            val session = CanvasSession.create(store)
            val id = notebooks.listDocuments().single()
            rewrite(notebooks, id, "canvasMetadata") { stored -> stored.withNewerField() }

            val opened = assertNotNull(store.get(session.canvasId), "a newer metadata field must not hide the canvas")
            assertEquals(session.canvasId, opened.id)

            session.applyLocalScene("""{"elements":[{"id":"after-upgrade"}]}""")
            assertTrue(store.get(session.canvasId)!!.sceneJson.contains("after-upgrade"))
            assertEquals(2, stored(notebooks, id, "canvasMetadata").newerFieldValue())
        }
    }

    @Test
    fun aNewerFieldInsideTheCanvasAclSurvivesThisBuildsWrite() = runBlocking {
        val path = Files.createTempDirectory("notebook-newer-acl-")
        NotebookLocalStore(path, "older-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            val session = CanvasSession.create(store)
            val id = notebooks.listDocuments().single()
            val owner = assertNotNull(stored(notebooks, id, "canvasMetadata")["acl"], "the session's canvas has an acl")
                .jsonObject.getValue("ownerUserId").jsonPrimitive.content
            rewrite(notebooks, id, "canvasMetadata") { stored ->
                JsonObject(stored + ("acl" to stored.getValue("acl").jsonObject.withNewerField()))
            }

            session.applyLocalScene("""{"elements":[{"id":"after-upgrade"}]}""")

            val acl = stored(notebooks, id, "canvasMetadata").getValue("acl").jsonObject
            assertEquals(owner, acl.getValue("ownerUserId").jsonPrimitive.content)
            assertEquals(2, acl.newerFieldValue())
        }
    }

    @Test
    fun aNotebookItemWithANewerFieldStillReadsAndKeepsTheFieldWhenRewritten() {
        val path = Files.createTempDirectory("notebook-newer-item-")
        NotebookLocalStore(path, "older-peer").use { notebooks ->
            val id = notebooks.create("Source")
            notebooks.putItem(id, NotebookItem("embed", "reference"))
            rewriteItem(notebooks, id, "embed") { stored -> stored.withNewerField() }

            val item = notebooks.read(id)!!.items.single()
            assertEquals("reference", item.kind)

            notebooks.putItem(id, item.copy(payloadJson = """{"edited":true}"""))
            val rewritten = storedItem(notebooks, id, "embed")
            assertEquals("""{"edited":true}""", rewritten["payloadJson"]!!.jsonPrimitive.content)
            assertEquals(2, rewritten.newerFieldValue())
        }
    }

    private fun rewrite(notebooks: NotebookLocalStore, id: DocumentId, key: String, change: (JsonObject) -> JsonObject) {
        val updated = change(stored(notebooks, id, key)).toString()
        notebooks.change(id) { tx -> tx.set(ObjectId.ROOT, key, updated) }
    }

    private fun stored(notebooks: NotebookLocalStore, id: DocumentId, key: String): JsonObject =
        notebooks.open(id)!!.withDocument { document -> document.json(ObjectId.ROOT, key) }.get()

    private fun rewriteItem(notebooks: NotebookLocalStore, id: DocumentId, item: String, change: (JsonObject) -> JsonObject) {
        val updated = change(storedItem(notebooks, id, item)).toString()
        notebooks.change(id) { tx -> tx.set(tx.items(), item, updated) }
    }

    private fun storedItem(notebooks: NotebookLocalStore, id: DocumentId, item: String): JsonObject =
        notebooks.open(id)!!.withDocument { document -> document.json(document.items(), item) }.get()

    private fun NotebookLocalStore.change(id: DocumentId, write: (Transaction) -> Unit) {
        open(id)!!.withDocument { document ->
            document.startTransaction().use { tx ->
                write(tx)
                tx.commit()
            }
        }.get()
    }

    private fun Read.items(): ObjectId = (get(ObjectId.ROOT, "items").orElseThrow() as AmValue.Map).id

    private fun Read.json(parent: ObjectId, key: String): JsonObject =
        Json.parseToJsonElement((get(parent, key).orElseThrow() as AmValue.Str).value).jsonObject

    private fun JsonObject.newerFieldValue(): Int = getValue(NEWER_FIELD).jsonObject.getValue("v").jsonPrimitive.content.toInt()

    private fun JsonObject.withNewerField() = JsonObject(this + (NEWER_FIELD to buildJsonObject { put("v", 2) }))

    private companion object {
        const val NEWER_FIELD = "addedByANewerBuild"
    }
}
