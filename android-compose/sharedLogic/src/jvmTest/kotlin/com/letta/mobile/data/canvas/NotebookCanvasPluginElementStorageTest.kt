package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures
import com.letta.mobile.data.canvas.plugin.CanvasPluginElements
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.automerge.AmValue
import org.automerge.ChangeHash
import org.automerge.ObjectId
import org.automerge.PatchAction
import org.automerge.Prop
import org.automerge.repo.Storage
import org.automerge.repo.StorageKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `_pluginElements` in the notebook (letta-mobile-s416w.1, .3). The notebook keeps it like
 * `_documents` (NotebookBoardStorage layout 3: per entry, per field, under `ROOT.boardArrays`), so
 * it needs no storage change: a board read back after a restart holds the same plugin elements,
 * provenance and tombstones; a plugin's state update rewrites that entry's changed fields and
 * nothing else, so many of them keep the history small; what a newer build wrote survives; and a
 * failed save is loud, not fatal.
 */
class NotebookCanvasPluginElementStorageTest {
    private val fixtures = CanvasPluginElementFixtures
    private val acl = CanvasAcl(CanvasSession.LOCAL_USER_ACTOR_ID, writerAgentIds = setOf(fixtures.AGENT))

    private suspend fun session(notebooks: NotebookLocalStore, canvasId: CanvasId): CanvasSession =
        CanvasSession.create(NotebookCanvasDocumentStore(notebooks), CanvasCreateOptions(canvasId = canvasId, acl = acl))

    private fun <T> NotebookLocalStore.readDocument(block: (org.automerge.Document) -> T): T =
        open(listDocuments().single())!!.withDocument { document -> block(document) }.get(5, TimeUnit.SECONDS)

    @Test
    fun pluginElementsSurviveTheNotebookAndARestartWithNoLayoutChange(): Unit = runBlocking {
        val path = Files.createTempDirectory("canvas-plugin-storage-")
        val canvasId = CanvasId("plugin-storage")
        val written: String
        NotebookLocalStore(path, "plugin-peer").use { notebooks ->
            val session = session(notebooks, canvasId)
            session.applyAgentBatch(listOf(fixtures.place(0), fixtures.place(0, id = "pe-gone")), fixtures.AGENT)
            assertNotNull(session.movePluginElement(fixtures.ID, fixtures.moved))
            session.applyAgentBatch(listOf(fixtures.progress(0, 0.6), fixtures.remove(0, id = "pe-gone")), fixtures.AGENT)
            written = session.sceneJsonOrEmpty()
        }
        NotebookLocalStore(path, "plugin-peer").use { notebooks ->
            val stored = assertNotNull(NotebookCanvasDocumentStore(notebooks).get(canvasId))
            val elements = CanvasOpProjector.pluginElementsOf(stored.sceneJson)
            assertEquals(CanvasOpProjector.pluginElementsOf(written), elements)
            val element = elements.single()
            assertEquals(fixtures.moved, element.frame)
            assertEquals(CanvasGeometryOwner.USER, element.owner)
            assertEquals(fixtures.props("status" to "running", "progress" to 0.6), element.props)
            // Provenance and the tombstone are stored too, so a late op still loses after a restart.
            val late = CanvasOpProjector.project(stored.sceneJson, listOf(fixtures.place(1, id = "pe-gone")))
            assertEquals(listOf(fixtures.ID), CanvasOpProjector.pluginElementsOf(late).map { it.id })

            // A session opened after the restart writes after everything stored, so its move lands.
            val reopened = CanvasSession.open(NotebookCanvasDocumentStore(notebooks), canvasId)!!
            assertNotNull(reopened.movePluginElement(fixtures.ID, fixtures.frame))
            assertEquals(fixtures.frame, reopened.pluginElements().single().frame)

            notebooks.readDocument { read ->
                assertEquals(3L, NotebookBoardStorage.LAYOUT_VERSION.toLong(), "no new layout for plugin elements")
                assertEquals(NotebookBoardStorage.LAYOUT_VERSION.toLong(), NotebookBoardStorage.layoutVersion(read))
                val arrays = (read.get(ObjectId.ROOT, "boardArrays").orElse(null) as AmValue.Map).id
                assertTrue(CanvasPluginElements.KEY in read.keys(arrays).orElseThrow().toList(), "kept entry by entry")
                val entries = (read.get(arrays, CanvasPluginElements.KEY).orElseThrow() as AmValue.Map).id
                assertEquals(listOf("pe-gone", fixtures.ID), read.keys(entries).orElseThrow().toList().sorted(), "the tombstone is an entry")
            }
        }
    }

    /** Every map key a change between [before] and [after] wrote or deleted, as a path from the root. */
    private fun org.automerge.Document.writtenPaths(before: Array<ChangeHash>, after: Array<ChangeHash>): List<String> =
        diff(before, after).map { patch ->
            val path = patch.path.map { (it.prop as? Prop.Key)?.value ?: "#" }
            val key = when (val action = patch.action) {
                is PatchAction.PutMap -> action.key
                is PatchAction.DeleteMap -> action.key
                else -> "~"
            }
            (path + key).joinToString("/")
        }

    @Test
    fun aStateUpdateRewritesOnlyThatEntrysChangedFields(): Unit = runBlocking {
        val path = Files.createTempDirectory("canvas-plugin-fields-")
        NotebookLocalStore(path, "fields-peer").use { notebooks ->
            val session = session(notebooks, CanvasId("plugin-fields"))
            session.applyAgentBatch((0 until 5).map { fixtures.place(0, id = "pe-$it") }, fixtures.AGENT)
            val before = notebooks.readDocument { it.heads }
            session.applyAgentBatch(listOf(fixtures.progress(0, 0.3, id = "pe-2")), fixtures.AGENT)
            val written = notebooks.readDocument { it.writtenPaths(before, it.heads) }

            val entryPrefix = "boardArrays/${CanvasPluginElements.KEY}/pe-2/"
            val board = written.filter { it.startsWith("boardArrays/") || it == "board" }
            assertTrue(board.all { it.startsWith(entryPrefix) }, "only pe-2 is rewritten, not the board string or other entries: $board")
            // The changed register, its clock, the state summary and the entry's newest writer; no frame, snapshot or fallback.
            val fields = board.map { it.removePrefix(entryPrefix) }.toSet()
            assertTrue("props" in fields && "_clock" in fields, "the update itself is written: $fields")
            assertTrue(fields.all { it in setOf("props", "_clock", "_state", "_lamport", "_actorId", "_opId") }, "only what changed: $fields")
        }
    }

    @Test
    fun manyStateUpdatesKeepTheHistoryBounded(): Unit = runBlocking {
        // The whole history as Automerge saves it: what the files on disk add up to once
        // compacted, independent of when the repository last compacted them.
        fun grow(updates: Int): Pair<Long, String> =
            NotebookLocalStore(Files.createTempDirectory("canvas-plugin-growth-"), "growth-peer").use { notebooks ->
                runBlocking {
                    val session = session(notebooks, CanvasId("plugin-growth"))
                    session.applyAgentBatch((0 until ELEMENTS).map { fixtures.place(0, id = "pe-$it") }, fixtures.AGENT)
                    repeat(updates) { i -> session.applyAgentBatch(listOf(fixtures.progress(0, i / 1000.0, id = "pe-3")), fixtures.AGENT) }
                    notebooks.readDocument { it.save().size.toLong() } to session.sceneJsonOrEmpty()
                }
            }
        val (baseline, _) = grow(0)
        val (grown, scene) = grow(UPDATES)
        val oneEntry = Json.parseToJsonElement(scene).jsonObject[CanvasPluginElements.KEY]!!.jsonArray.first().toString().length
        val perUpdate = (grown - baseline) / UPDATES
        println("canvas growth [plugin state]: $UPDATES updates of one of $ELEMENTS plugin elements -> $perUpdate bytes/update (one entry ~$oneEntry bytes)")
        // The entry is not rewritten per update, only its props, clock and summaries (and the
        // board's revision). Rewriting the collection would cost about ELEMENTS x oneEntry.
        // Measured on CI: 79 bytes per update for a 1477 byte entry.
        assertTrue(perUpdate < oneEntry / 4, "a state update cost $perUpdate bytes (one entry is $oneEntry bytes)")
    }

    @Test
    fun whatANewerBuildWroteInAnEntrySurvivesThisBuildsWritesAndAReload(): Unit = runBlocking {
        val path = Files.createTempDirectory("canvas-plugin-newer-")
        val canvasId = CanvasId("plugin-newer")
        NotebookLocalStore(path, "newer-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            val placed = CanvasOpProjector.project(CanvasOpProjector.emptySceneJson(), listOf(fixtures.place(1)))
            // A newer build's entry: a field and a register this build does not know.
            val newer = withEntry(placed) { entry ->
                val clock = entry["_clock"]!!.jsonObject + (FUTURE_REGISTER to writer(5))
                JsonObject(entry + (FUTURE_FIELD to JsonPrimitive("kept")) + ("_clock" to JsonObject(clock)))
            }
            store.upsert(CanvasDocument(canvasId, title = "Newer", sceneJson = newer, acl = acl))

            val session = CanvasSession.open(store, canvasId)!!
            assertEquals(fixtures.ID, session.pluginElements().single().id, "it still loads")
            session.applyAgentBatch(listOf(fixtures.progress(0, 0.4)), fixtures.AGENT)
            assertNotNull(session.movePluginElement(fixtures.ID, fixtures.moved))
        }
        NotebookLocalStore(path, "newer-peer").use { notebooks ->
            val stored = assertNotNull(NotebookCanvasDocumentStore(notebooks).get(canvasId)).sceneJson
            val entry = entryOf(stored)
            assertEquals(JsonPrimitive("kept"), entry[FUTURE_FIELD])
            assertEquals(writer(5), entry["_clock"]!!.jsonObject[FUTURE_REGISTER])
            assertEquals(fixtures.moved, CanvasOpProjector.pluginElementsOf(stored).single().frame)

            // A removal newer than everything clears what it cannot attribute.
            val removed = entryOf(CanvasOpProjector.project(stored, listOf(fixtures.remove(1_000))))
            assertNull(removed[FUTURE_FIELD])
            assertFalse(removed["_clock"] is JsonObject)
        }
    }

    @Test
    fun aFailedSaveOfAPluginWriteIsLoudAndTheBoardCarriesOn(): Unit = runBlocking {
        val path = Files.createTempDirectory("canvas-plugin-failing-")
        var failPuts = false
        val failing = { delegate: Storage ->
            object : Storage by delegate {
                override fun put(key: StorageKey, value: ByteArray): CompletableFuture<Void> {
                    if (failPuts) throw OutOfMemoryError("Java heap space (injected)")
                    return delegate.put(key, value)
                }
            }
        }
        NotebookLocalStore(path, "failing-peer", storage = failing).use { notebooks ->
            val canvasId = CanvasId("plugin-failing")
            val session = session(notebooks, canvasId)
            session.applyAgentBatch(listOf(fixtures.place(0)), fixtures.AGENT)
            failPuts = true
            session.applyAgentBatch(listOf(fixtures.progress(0, 0.7)), fixtures.AGENT)
            val deadline = System.currentTimeMillis() + 10_000
            while (session.storageFaults.value.affecting(canvasId).none { it.kind == CanvasStorageFault.Kind.SAVE_FAILED }) {
                check(System.currentTimeMillis() < deadline) { "no SAVE_FAILED fault for the plugin write" }
                Thread.sleep(20)
            }
            // Not fatal: the session still has the write and takes the next one.
            failPuts = false
            assertNotNull(session.movePluginElement(fixtures.ID, fixtures.moved))
            val element = session.pluginElements().single()
            assertEquals(fixtures.props("status" to "running", "progress" to 0.7), element.props)
            assertEquals(fixtures.moved, element.frame)
        }
    }

    private fun writer(lamport: Long) = JsonObject(
        mapOf("_lamport" to JsonPrimitive(lamport), "_actorId" to JsonPrimitive("newer"), "_opId" to JsonPrimitive("newer-$lamport")),
    )

    private fun entryOf(scene: String): JsonObject =
        Json.parseToJsonElement(scene).jsonObject[CanvasPluginElements.KEY]!!.jsonArray.single().jsonObject

    private fun withEntry(scene: String, change: (JsonObject) -> JsonObject): String {
        val root = Json.parseToJsonElement(scene).jsonObject
        return JsonObject(root + (CanvasPluginElements.KEY to JsonArray(listOf(change(entryOf(scene)))))).toString()
    }

    private companion object {
        const val ELEMENTS = 20
        const val UPDATES = 200
        const val FUTURE_FIELD = "layer"
        const val FUTURE_REGISTER = "layer"
    }
}
