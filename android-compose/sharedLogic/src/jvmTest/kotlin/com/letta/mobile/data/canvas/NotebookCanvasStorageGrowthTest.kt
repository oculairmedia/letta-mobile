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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A board's Automerge history must grow with what changed, not with the size of the board times
 * the number of saves. A phone died in an OOM loop on a 62 MB snapshot of a ~112 KB board: every
 * save wrote the whole scene twice (`board` and `canvasSceneBase`).
 */
class NotebookCanvasStorageGrowthTest {
    /** Stroke `s[seed]`, [STROKE_POINTS] points long, shifted right by [dx]. */
    private fun stroke(seed: Int, dx: Double = 0.0): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive("s$seed"),
            "type" to JsonPrimitive("Path"),
            "zIndex" to JsonPrimitive(seed),
            "strokeColor" to JsonPrimitive("#ff202020"),
            "strokeWidth" to JsonPrimitive(4.0),
            "points" to JsonArray((0 until STROKE_POINTS).map { i ->
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
                elements += stroke(i)
                save()
            }
            for (i in 0 until moves) {
                elements[0] = image("img", 100.0 + i)
                val index = 1 + i % strokes
                elements[index] = stroke(index - 1, dx = i * 2.5)
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
                val scene = scene((0 until 40).map { stroke(it) } + image("img", 100.0))
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

    /** Note [id]'s text: [NOTE_WORDS] words, then what was [typed]. */
    private class NoteText(private val id: String, private val typed: String = "") {
        fun json(): String = JsonObject(
            mapOf(
                "blocks" to JsonArray((0 until NOTE_WORDS).map { JsonPrimitive("word $it of note $id") } + JsonPrimitive(typed)),
            ),
        ).toString()
    }

    private fun noteFrame(index: Int) = CanvasDocumentFrame(x = 40f * index, y = 30f * index, width = 320f, height = 240f)

    /** How many notes to write, then how many moves of one note and keystrokes in one note's text. */
    private class NoteWork(val notes: Int, val moves: Int = 0, val edits: Int = 0)

    /**
     * Notes as the board makes them: [NoteWork.notes] notes written through [CanvasSession], so the
     * scene holds the projector's own `_documents` (an array of entries ordered by id), then
     * [NoteWork.moves] moves of one note and [NoteWork.edits] keystrokes in one note's text, one
     * save each.
     */
    private fun simulateNotes(path: Path, work: NoteWork): Run = runBlocking {
        val canvasId = CanvasId("notes")
        var saves = 0
        val lastScene = NotebookLocalStore(path, "notes-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            store.upsert(CanvasDocument(id = canvasId, title = "Notes", sceneJson = scene(listOf(image("img", 100.0)))))
            val session = CanvasSession(canvasId, store)
            session.load()
            for (i in 0 until work.notes) {
                assertNotNull(session.setDocument("note-$i", NoteText("note-$i").json(), frame = noteFrame(i)))
                saves++
            }
            for (i in 0 until work.moves) {
                assertNotNull(session.moveDocument("note-2", noteFrame(2).let { it.copy(x = it.x + i + 1f) }))
                saves++
            }
            for (i in 0 until work.edits) {
                assertNotNull(session.setDocument("note-2", NoteText("note-2", typed = "x".repeat(i + 1)).json()))
                saves++
            }
            session.sceneJsonOrEmpty()
        }
        Run(lastScene, saves, directoryBytes(path))
    }

    /** The store at [path] reads back [run]'s last scene. */
    private fun assertReloads(path: Path, run: Run, canvasId: CanvasId = CanvasId("notes")) {
        val scene = run.lastScene
        NotebookLocalStore(path, "notes-peer").use { notebooks ->
            runBlocking {
                val reloaded = NotebookCanvasDocumentStore(notebooks).get(canvasId)!!
                assertEquals(Json.parseToJsonElement(scene), Json.parseToJsonElement(reloaded.sceneJson))
            }
        }
    }

    /**
     * The projector writes `_documents` as an array, so the per-entry saving has to cover arrays
     * of id'd entries too: moving one of [NOTES] notes rewrites that note's frame, and a keystroke
     * rewrites that note, not the whole `_documents` array.
     */
    @Test
    fun movingOrEditingOneOfManyProjectedNotesWritesOnlyThatNote() {
        val base = simulateNotes(Files.createTempDirectory("canvas-notes-base-"), NoteWork(NOTES))
        val scene = Json.parseToJsonElement(base.lastScene).jsonObject
        assertTrue(scene["_documents"] is JsonArray, "the projector's _documents is an array")
        val allNotes = scene["_documents"].toString().length
        val oneNote = allNotes / NOTES

        val movePath = Files.createTempDirectory("canvas-notes-move-")
        val moved = simulateNotes(movePath, NoteWork(NOTES, moves = SAVES))
        val perMove = (moved.storedBytes - base.storedBytes) / SAVES
        report("move one of $NOTES notes", moved, base.storedBytes)
        assertReloads(movePath, moved)

        val editPath = Files.createTempDirectory("canvas-notes-edit-")
        val edited = simulateNotes(editPath, NoteWork(NOTES, edits = SAVES))
        val perEdit = (edited.storedBytes - base.storedBytes) / SAVES
        report("edit one of $NOTES notes", edited, base.storedBytes)
        assertReloads(editPath, edited)

        println("canvas growth [notes]: one note ~$oneNote bytes, all $NOTES notes ~$allNotes bytes; $perMove bytes/move, $perEdit bytes/edit")
        // Before: 10680 bytes/move and 3644 bytes/edit, the whole `_documents` array each save.
        // Now a move writes the note's frame and provenance, and a keystroke that note's text.
        assertTrue(perMove < 256, "moving one note cost $perMove bytes per save (one note is $oneNote bytes, all notes $allNotes)")
        assertTrue(perEdit < oneNote / 4, "editing one note cost $perEdit bytes per save (one note is $oneNote bytes, all notes $allNotes)")
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

    /** A layout-2 board kept its notes inline in `board`; it reads as is and the next write moves them out. */
    @Test
    fun aBoardWithItsNotesInlineReadsAndMovesThemOut() = runBlocking {
        val path = Files.createTempDirectory("canvas-notes-inline-")
        val canvasId = CanvasId("inline-notes")
        val notes = """[{"id":"n1","json":"{\"a\":1}","_lamport":1},{"id":"n2","json":"{}","_lamport":2}]"""
        val inlineScene = """{"bgColor":"#ffffffff","elements":[{"id":"a","type":"Path"}],"_documents":$notes}"""
        NotebookLocalStore(path, "inline-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            store.upsert(CanvasDocument(canvasId, title = "Inline", sceneJson = """{"elements":[{"id":"a","type":"Path"}]}"""))
            val id = notebooks.listDocuments().single()
            notebooks.open(id)!!.withDocument { document ->
                document.startTransaction().use { tx ->
                    val board = """{"bgColor":"#ffffffff","elements":[{"id":"a"}],"_documents":$notes,"schema":"notebook-board/1"}"""
                    tx.set(ObjectId.ROOT, "board", board)
                    tx.set(ObjectId.ROOT, "boardVersion", 2)
                    tx.commit()
                }
            }.get(5, TimeUnit.SECONDS)
            val inline = store.get(canvasId)!!
            assertEquals(Json.parseToJsonElement(inlineScene), Json.parseToJsonElement(inline.sceneJson))
            val edited = inlineScene.replace("\"_lamport\":2", "\"_lamport\":3")
            assertTrue(store.upsertIfRevision(inline.copy(revision = inline.revision + 1, sceneJson = edited), inline.revision))
            assertEquals(Json.parseToJsonElement(edited), Json.parseToJsonElement(store.get(canvasId)!!.sceneJson))
            notebooks.open(id)!!.withDocument { document ->
                val raw = (document.get(ObjectId.ROOT, "board").orElseThrow() as AmValue.Str).value
                assertEquals("""{"bgColor":"#ffffffff","elements":[{"id":"a"}],"_documents":[{"id":"n1"},{"id":"n2"}],"schema":"notebook-board/1"}""", raw)
                assertEquals(NotebookBoardStorage.LAYOUT_VERSION.toLong(), NotebookBoardStorage.layoutVersion(document))
                val arrays = (document.get(ObjectId.ROOT, "boardArrays").orElseThrow() as AmValue.Map).id
                val documents = (document.get(arrays, "_documents").orElseThrow() as AmValue.Map).id
                assertEquals(listOf("n1", "n2"), document.keys(documents).orElseThrow().toList())
            }.get(5, TimeUnit.SECONDS)
        }
        Unit
    }

    private companion object {
        const val NOTES = 20
        const val STROKE_POINTS = 160
        const val NOTE_WORDS = 60
        const val SAVES = 100
    }
}
