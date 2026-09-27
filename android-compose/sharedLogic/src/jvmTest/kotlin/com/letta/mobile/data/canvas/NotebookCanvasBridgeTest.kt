package com.letta.mobile.data.canvas

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class NotebookCanvasBridgeTest {
    @Test
    fun importsSceneWithNotesAndAttachmentsWithoutTouchingSourceAndRetriesAfterRestart() {
        val directory = Files.createTempDirectory("canvas-bridge-")
        val scene = """{"bgColor":"#aabbccff","elements":[{"id":"image-1","type":"Image","zIndex":1,"points":["0,0","2,2"],"strokeColor":"#000000ff","strokeWidth":0,"imageRef":"asset-1"}],"documents":{"note-1":{"json":"note-body","frame":{"x":1}}},"images":{"asset-1":{"data":"payload"}},"bgPattern":{"kind":"dots"}}"""
        val source = CanvasDocument(CanvasId("canvas-a"), title = "Legacy", sceneJson = scene)
        val id = NotebookLocalStore(directory, "bridge-peer").use { store ->
            val target = store.create("Destination")
            val bridge = NotebookCanvasBridge(store)
            assertEquals(NotebookCanvasImportResult.IMPORTED, bridge.import(source, target))
            assertEquals(NotebookCanvasImportResult.ALREADY_IMPORTED, bridge.import(source, target))
            assertEquals(Json.parseToJsonElement(scene), Json.parseToJsonElement(assertNotNull(bridge.drawableScene(target))))
            assertEquals("Legacy", store.read(target)?.title)
            assertEquals(scene, source.sceneJson)
            target
        }
        NotebookLocalStore(directory, "bridge-peer").use { store ->
            val bridge = NotebookCanvasBridge(store)
            assertEquals(NotebookCanvasImportResult.ALREADY_IMPORTED, bridge.import(source, id))
            assertEquals(Json.parseToJsonElement(scene), Json.parseToJsonElement(assertNotNull(bridge.drawableScene(id))))
            assertEquals("asset-1", Json.parseToJsonElement(bridge.drawableScene(id)!!).jsonObject["elements"]!!.toString().let { if ("asset-1" in it) "asset-1" else "missing" })
            assertEquals(NotebookCanvasImportResult.CONFLICT, bridge.import(source.copy(id = CanvasId("other")), id))
            assertEquals(NotebookCanvasImportResult.CONFLICT, bridge.import(source.copy(sceneJson = """{"bgColor":"#ffffffff","elements":[]}"""), id))
            store.insertMarkdown(id, 0, "Changed")
            assertEquals(NotebookCanvasImportResult.CONFLICT, bridge.import(source, id))
        }
    }

    @Test
    fun editedNotebookAndLegacyBoardAreNotOverwritten() {
        NotebookLocalStore(Files.createTempDirectory("bridge-conflict-"), "bridge-conflict-peer").use { store ->
            val bridge = NotebookCanvasBridge(store)
            val source = CanvasDocument(CanvasId("source"), title = "Imported", sceneJson = """{"bgColor":"#ffffffff","elements":[]}""")
            val edited = store.create("Existing")
            store.insertMarkdown(edited, 0, "Local")
            assertEquals(NotebookCanvasImportResult.CONFLICT, bridge.import(source, edited))
            assertEquals("Existing", store.read(edited)?.title)
            assertEquals("Local", store.read(edited)?.markdown)
            val changedBoard = store.create("Board")
            store.setBoard(changedBoard, """{"schema":"notebook-board/1","elements":[1]}""")
            assertEquals(NotebookCanvasImportResult.CONFLICT, bridge.import(source, changedBoard))
            val changedTitle = store.create("Title")
            store.open(changedTitle)!!.withDocument { doc ->
                doc.startTransaction().use { tx ->
                    tx.set(org.automerge.ObjectId.ROOT, "title", "Changed")
                    tx.commit()
                }
            }.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(NotebookCanvasImportResult.CONFLICT, bridge.import(source, changedTitle))
        }
    }

    @Test
    fun readsOldBoardAsDrawableScene() {
        NotebookLocalStore(Files.createTempDirectory("bridge-old-"), "bridge-old-peer").use { store ->
            val id = store.create("Old")
            store.setBoard(id, """{"schema":"notebook-board/1","elements":[],"bgColor":"#123456ff"}""")
            val scene = Json.parseToJsonElement(NotebookCanvasBridge(store).drawableScene(id)!!).jsonObject
            assertEquals("#123456ff", scene["bgColor"]?.toString()?.trim('"'))
            assertEquals(null, scene["schema"])
        }
    }
}
