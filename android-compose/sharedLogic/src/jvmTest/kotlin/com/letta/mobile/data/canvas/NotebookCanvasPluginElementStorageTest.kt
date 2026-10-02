package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures
import com.letta.mobile.data.canvas.plugin.CanvasPluginElements
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.automerge.AmValue
import org.automerge.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * letta-mobile-s416w.1: `_pluginElements` needs no storage change. The notebook keeps it like
 * `_documents` (NotebookBoardStorage layout 3: per entry, per field, under `ROOT.boardArrays`), and a
 * board read back after a restart holds the same plugin elements, provenance and all.
 */
class NotebookCanvasPluginElementStorageTest {
    private val fixtures = CanvasPluginElementFixtures

    @Test
    fun pluginElementsSurviveTheNotebookAndARestartWithNoLayoutChange(): Unit = runBlocking {
        val path = Files.createTempDirectory("canvas-plugin-storage-")
        val canvasId = CanvasId("plugin-storage")
        val written: String
        NotebookLocalStore(path, "plugin-peer").use { notebooks ->
            val acl = CanvasAcl(CanvasSession.LOCAL_USER_ACTOR_ID, writerAgentIds = setOf(fixtures.AGENT))
            val session = CanvasSession.create(NotebookCanvasDocumentStore(notebooks), CanvasCreateOptions(canvasId = canvasId, acl = acl))
            session.applyAgentBatch(listOf(fixtures.place(0), fixtures.place(0, id = "pe-gone")), fixtures.AGENT)
            session.applyLocalStamped(listOf(fixtures.move(0)))
            session.applyAgentBatch(listOf(fixtures.progress(0, 0.6), fixtures.remove(0, id = "pe-gone")), fixtures.AGENT)
            written = session.sceneJsonOrEmpty()
        }
        NotebookLocalStore(path, "plugin-peer").use { notebooks ->
            val stored = assertNotNull(NotebookCanvasDocumentStore(notebooks).get(canvasId))
            val elements = CanvasOpProjector.pluginElementsOf(stored.sceneJson)
            assertEquals(CanvasOpProjector.pluginElementsOf(written), elements)
            val element = elements.single()
            assertEquals(fixtures.moved, element.frame)
            assertEquals(fixtures.props("status" to "running", "progress" to 0.6), element.props)
            // Provenance and the tombstone are stored too, so a late op still loses after a restart.
            val late = CanvasOpProjector.project(stored.sceneJson, listOf(fixtures.place(1, id = "pe-gone")))
            assertEquals(listOf(fixtures.ID), CanvasOpProjector.pluginElementsOf(late).map { it.id })

            val document = notebooks.listDocuments().single()
            notebooks.open(document)!!.withDocument { read ->
                assertEquals(3L, NotebookBoardStorage.LAYOUT_VERSION.toLong(), "no new layout for plugin elements")
                assertEquals(NotebookBoardStorage.LAYOUT_VERSION.toLong(), NotebookBoardStorage.layoutVersion(read))
                val arrays = (read.get(ObjectId.ROOT, "boardArrays").orElse(null) as AmValue.Map).id
                assertTrue(CanvasPluginElements.KEY in read.keys(arrays).orElseThrow().toList(), "kept entry by entry")
            }.get(5, TimeUnit.SECONDS)
        }
    }
}
