package com.letta.mobile.data.canvas

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.automerge.AmValue
import org.automerge.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A board's Automerge history must grow with what changed, not with the size of the board times
 * the number of saves. A phone died in an OOM loop on a 62 MB snapshot of a ~112 KB board: every
 * save wrote the whole scene twice (`board` and `canvasSceneBase`).
 */
class NotebookCanvasStorageGrowthTest {
    private fun stroke(id: String, seed: Int, points: Int, dx: Double = 0.0): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "type" to JsonPrimitive("Path"),
            "zIndex" to JsonPrimitive(seed),
            "strokeColor" to JsonPrimitive("#ff202020"),
            "strokeWidth" to JsonPrimitive(4.0),
            "points" to JsonArray((0 until points).map { i ->
                JsonPrimitive("${100.0 + dx + seed * 3 + i * 1.37},${200.0 + seed * 5 + (i * 7 % 113) * 0.91}")
            }),
        ),
    )

    private fun image(id: String, x: Double): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "type" to JsonPrimitive("Image"),
            "zIndex" to JsonPrimitive(999),
            "imageRef" to JsonPrimitive("asset:sha256-0123456789abcdef"),
            "points" to JsonArray(listOf(JsonPrimitive("$x,400.0"), JsonPrimitive("${x + 320},640.0"))),
        ),
    )

    private fun note(id: String, words: Int): JsonObject = JsonObject(
        mapOf(
            "json" to JsonPrimitive(
                JsonObject(mapOf("blocks" to JsonArray((0 until words).map { JsonPrimitive("word $it of note $id") }))).toString(),
            ),
            "frame" to JsonObject(mapOf("x" to JsonPrimitive(10), "y" to JsonPrimitive(20))),
        ),
    )

    private fun scene(elements: List<JsonObject>, notes: Map<String, JsonObject> = emptyMap()) = JsonObject(
        buildMap {
            put("bgColor", JsonPrimitive("#ffffffff"))
            put("elements", JsonArray(elements))
            if (notes.isNotEmpty()) put("_documents", JsonObject(notes))
        },
    ).toString()

    private fun directoryBytes(path: Path): Long = Files.walk(path).use { files ->
        files.iterator().asSequence()
            .filter { Files.isRegularFile(it) && it.fileName.toString() != "notebook-canvas.lock" }
            .sumOf { Files.size(it) }
    }

    private class Run(val lastScene: String, val saves: Int, val storedBytes: Long)

    /**
     * Saves as [CanvasSession] makes them: the whole scene on every op. Draw [strokes] strokes,
     * then [moves] moves (each translates a whole stroke and nudges the image), then [noteEdits]
     * edits to one of five notes.
     */
    private fun simulate(path: Path, strokes: Int, moves: Int, noteEdits: Int = 0): Run = runBlocking {
        val canvasId = CanvasId("growth")
        var lastScene = ""
        var saves = 0
        NotebookLocalStore(path, "growth-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            var doc = CanvasDocument(id = canvasId, title = "Growth", sceneJson = scene(emptyList()))
            store.upsert(doc)
            val elements = mutableListOf(image("img", 100.0))
            val notes = if (noteEdits > 0) (0 until 5).associate { "note-$it" to note("note-$it", 60) }.toMutableMap()
                else mutableMapOf()
            suspend fun save() {
                lastScene = scene(elements, notes)
                saves++
                doc = doc.copy(revision = saves.toLong(), sceneJson = lastScene, updatedAtEpochMs = 1_000L + saves)
                store.upsert(doc)
            }
            for (i in 0 until strokes) {
                elements += stroke("s$i", i, 160)
                save()
            }
            for (i in 0 until moves) {
                elements[0] = image("img", 100.0 + i)
                val index = 1 + i % strokes
                elements[index] = stroke("s${index - 1}", index - 1, 160, dx = i * 2.5)
                save()
            }
            for (i in 0 until noteEdits) {
                notes["note-2"] = note("note-2", 60 + i)
                save()
            }
        }
        Run(lastScene, saves, directoryBytes(path))
    }

    private fun report(label: String, run: Run, baseline: Long) {
        val board = run.lastScene.toByteArray().size
        println(
            "canvas growth [$label]: ${run.saves} saves of a ${board / 1024} KB board -> " +
                "${run.storedBytes / 1024} KB on disk, ${(run.storedBytes - baseline) / run.saves} bytes/save${if (baseline > 0) " beyond the same board saved once" else ""}",
        )
    }

    @Test
    fun repeatedSavesOfABoardDoNotStoreTheWholeBoardEachTime() {
        val path = Files.createTempDirectory("canvas-growth-")
        val run = simulate(path, strokes = 40, moves = 200)
        val boardBytes = run.lastScene.toByteArray().size
        report("draw + move", run, 0)
        // Before: 15.9 MB for this run (two whole-scene strings per save). The moves themselves
        // rewrite one stroke's points each, so a few board sizes is the floor, not the board × saves.
        assertTrue(run.storedBytes < boardBytes * 6L, "stored ${run.storedBytes} bytes for a $boardBytes byte board")
        NotebookLocalStore(path, "growth-peer").use { notebooks ->
            runBlocking {
                val reloaded = NotebookCanvasDocumentStore(notebooks).get(CanvasId("growth"))!!
                assertEquals(Json.parseToJsonElement(run.lastScene).jsonObject, Json.parseToJsonElement(reloaded.sceneJson).jsonObject)
            }
        }
    }

    @Test
    fun savesThatChangeNothingOnTheBoardRecordAlmostNothing() {
        val idle = simulate(Files.createTempDirectory("canvas-idle-a-"), strokes = 40, moves = 0)
        val path = Files.createTempDirectory("canvas-idle-b-")
        val run = runBlocking {
            NotebookLocalStore(path, "growth-peer").use { notebooks ->
                val store = NotebookCanvasDocumentStore(notebooks)
                val scene = scene((0 until 40).map { stroke("s$it", it, 160) } + image("img", 100.0))
                var doc = CanvasDocument(id = CanvasId("growth"), title = "Growth", sceneJson = scene)
                store.upsert(doc)
                repeat(500) { doc = doc.copy(revision = it + 1L, updatedAtEpochMs = 1_000L + it); store.upsert(doc) }
                Run(scene, 501, 0)
            }
        }.let { Run(it.lastScene, it.saves, directoryBytes(path)) }
        report("500 metadata-only saves", run, idle.storedBytes)
        assertTrue((run.storedBytes - idle.storedBytes) / 500 < 256, "a save with no board change cost ${(run.storedBytes - idle.storedBytes) / 500} bytes")
    }

    @Test
    fun editingOneNoteRewritesThatNoteNotEveryNote() {
        val path = Files.createTempDirectory("canvas-notes-")
        val run = simulate(path, strokes = 10, moves = 0, noteEdits = 200)
        val boardBytes = run.lastScene.toByteArray().size
        report("note edits", run, 0)
        assertTrue(run.storedBytes < boardBytes * 6L, "stored ${run.storedBytes} bytes for a $boardBytes byte board")
        NotebookLocalStore(path, "growth-peer").use { notebooks ->
            runBlocking {
                val reloaded = NotebookCanvasDocumentStore(notebooks).get(CanvasId("growth"))!!
                assertEquals(Json.parseToJsonElement(run.lastScene).jsonObject, Json.parseToJsonElement(reloaded.sceneJson).jsonObject)
            }
        }
    }

    /**
     * An image still carrying its bytes inline (no asset store, or not adopted yet) is written
     * once; moving it rewrites its geometry, not its bytes. (Excalidraw keeps such bytes in a
     * separate `files` map for the same reason; ours go to the asset store by ref.)
     */
    @Test
    fun movingAnImageDoesNotRewriteItsBytes() {
        val path = Files.createTempDirectory("canvas-image-")
        val random = kotlin.random.Random(7)
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val imageData = kotlin.io.encoding.Base64.encode(random.nextBytes(150 * 1024))
        fun imageAt(x: Double) = JsonObject(image("photo", x) + ("imageData" to JsonPrimitive(imageData)))
        val run = runBlocking {
            NotebookLocalStore(path, "image-peer").use { notebooks ->
                val store = NotebookCanvasDocumentStore(notebooks)
                var doc = CanvasDocument(id = CanvasId("image"), title = "Image", sceneJson = scene(listOf(imageAt(0.0))))
                store.upsert(doc)
                repeat(100) { i ->
                    doc = doc.copy(revision = i + 1L, sceneJson = scene(listOf(imageAt(i * 3.0))))
                    store.upsert(doc)
                }
                Run(doc.sceneJson, 101, 0)
            }
        }.let { Run(it.lastScene, it.saves, directoryBytes(path)) }
        report("image moves", run, 0)
        assertTrue(run.storedBytes < imageData.length * 2L, "stored ${run.storedBytes} bytes for a ${imageData.length} byte image")
    }

    @Test
    fun aLegacyDocumentReadsAndMovesToTheCompactForm() = runBlocking {
        val path = Files.createTempDirectory("canvas-legacy-")
        val canvasId = CanvasId("legacy")
        val legacyScene = """{"bgColor":"#ffffffff","elements":[{"id":"a","type":"Path","points":["1,2"]}],"_documents":{"n":{"json":"{}"}}}"""
        NotebookLocalStore(path, "legacy-peer").use { notebooks ->
            val id = notebooks.create("Legacy")
            // The old layout: the full scene in `board` and again in `canvasSceneBase`.
            notebooks.open(id)!!.withDocument { document ->
                document.startTransaction().use { tx ->
                    val board = JsonObject(Json.parseToJsonElement(legacyScene).jsonObject + ("schema" to JsonPrimitive("notebook-board/1")))
                    val elements = (tx.get(ObjectId.ROOT, "boardElements").orElseThrow() as AmValue.Map).id
                    val a = tx.set(elements, "a", org.automerge.ObjectType.MAP)
                    tx.set(a, "id", "\"a\"")
                    tx.set(a, "type", "\"Path\"")
                    tx.set(a, "points", "[\"1,2\"]")
                    tx.set(ObjectId.ROOT, "board", board.toString())
                    tx.set(ObjectId.ROOT, "canvasMetadata", Json.encodeToString(CanvasDocument.serializer(), CanvasDocument(canvasId, title = "Legacy", revision = 1)))
                    tx.set(ObjectId.ROOT, "canvasSceneBase", legacyScene)
                    tx.commit()
                }
            }.get(5, TimeUnit.SECONDS)
            val store = NotebookCanvasDocumentStore(notebooks)
            val legacy = store.get(canvasId)!!
            assertEquals(Json.parseToJsonElement(legacyScene), Json.parseToJsonElement(legacy.sceneJson))
            val moved = legacyScene.replace("\"1,2\"", "\"3,4\"")
            assertTrue(store.upsertIfRevision(legacy.copy(revision = 2, sceneJson = moved), 1))
            assertEquals(Json.parseToJsonElement(moved), Json.parseToJsonElement(store.get(canvasId)!!.sceneJson))
            notebooks.open(id)!!.withDocument { document ->
                assertNull(document.get(ObjectId.ROOT, "canvasSceneBase").orElse(null))
                val raw = (document.get(ObjectId.ROOT, "board").orElseThrow() as AmValue.Str).value
                assertEquals("""{"bgColor":"#ffffffff","elements":[{"id":"a"}],"_documents":{},"schema":"notebook-board/1"}""", raw)
            }.get(5, TimeUnit.SECONDS)
        }
        Unit
    }
}
