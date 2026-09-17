package com.letta.mobile.desktop.canvas

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasId
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopCanvasDocumentStoreTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("canvas_store_test_")
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun processKillSimulationRestoresPersistedCanvasDocument() = runTest {
        val docId = CanvasId("canvas-proc-kill-1")
        val originalDoc = CanvasDocument(
            id = docId,
            agentId = "agent-42",
            conversationId = "conv-77",
            title = "Architecture Plan",
            revision = 5L,
            sceneJson = """{"bgColor":-16777216,"elements":[{"id":"e1"}]}""",
            updatedAtEpochMs = 1716123456789L,
        )

        // Step 1: Write using first store instance
        val store1 = DesktopCanvasDocumentStore(rootDirectory = tempDir)
        store1.upsert(originalDoc)

        // Verify store1 can read it back
        val readBack1 = store1.get(docId)
        assertNotNull(readBack1)
        assertEquals(originalDoc, readBack1)

        // Step 2: "Process kill" — abandon store1 instance completely and instantiate a new store instance
        val store2 = DesktopCanvasDocumentStore(rootDirectory = tempDir)

        // Step 3: Verify fresh store2 recovers document from disk
        val recoveredDoc = store2.get(docId)
        assertNotNull(recoveredDoc)
        assertEquals(originalDoc, recoveredDoc)
        assertEquals("agent-42", recoveredDoc.agentId)
        assertEquals("conv-77", recoveredDoc.conversationId)
        assertEquals("Architecture Plan", recoveredDoc.title)
        assertEquals(5L, recoveredDoc.revision)
        assertEquals("""{"bgColor":-16777216,"elements":[{"id":"e1"}]}""", recoveredDoc.sceneJson)

        // Verify conversation lookup
        val byConv = store2.getForConversation("conv-77")
        assertEquals(originalDoc, byConv)

        // Verify agent lookup
        val byAgent = store2.listForAgent("agent-42")
        assertEquals(listOf(originalDoc), byAgent)

        // Verify absent conversation
        assertNull(store2.getForConversation("non-existent-conv"))
    }

    @Test
    fun upsertOverwritesExistingDocumentAtomically() = runTest {
        val docId = CanvasId("canvas-overwrite-1")
        val docV1 = CanvasDocument(
            id = docId,
            agentId = "agent-1",
            conversationId = "conv-1",
            title = "V1",
            revision = 1L,
            sceneJson = "{}",
            updatedAtEpochMs = 1000L,
        )
        val docV2 = docV1.copy(
            title = "V2 Updated",
            revision = 2L,
            sceneJson = """{"elements":[{"id":"rect"}]}""",
            updatedAtEpochMs = 2000L,
        )

        val store = DesktopCanvasDocumentStore(rootDirectory = tempDir)
        store.upsert(docV1)
        assertEquals(1L, store.get(docId)?.revision)

        store.upsert(docV2)
        val afterUpdate = store.get(docId)
        assertEquals(docV2, afterUpdate)
        assertEquals("V2 Updated", afterUpdate?.title)
        assertEquals(2L, afterUpdate?.revision)
    }

    @Test
    fun upsertIfRevisionWritesOnlyWhileTheStoredRevisionMatches() = runTest {
        val docId = CanvasId("canvas-cas-1")
        val v1 = CanvasDocument(id = docId, title = "V1", revision = 1L, sceneJson = "{}", updatedAtEpochMs = 1000L)
        val store = DesktopCanvasDocumentStore(rootDirectory = tempDir)
        assertFalse(store.upsertIfRevision(v1, expectedRevision = 0L))
        store.upsert(v1)

        val v2 = v1.copy(revision = 2L, sceneJson = """{"elements":[{"id":"a"}]}""", updatedAtEpochMs = 2000L)
        assertTrue(store.upsertIfRevision(v2, expectedRevision = 1L))
        assertEquals(v2, store.get(docId))

        val stale = v1.copy(revision = 2L, sceneJson = """{"stale":true}""", updatedAtEpochMs = 3000L)
        assertFalse(store.upsertIfRevision(stale, expectedRevision = 1L))
        assertEquals(v2, DesktopCanvasDocumentStore(rootDirectory = tempDir).get(docId))
    }
}
