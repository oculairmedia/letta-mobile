package com.letta.mobile.data.canvas

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CanvasHistoryTest {

    @Test
    fun testCheckpointCaptureAndRestore() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val docId = CanvasId("canvas-history-1")
        val session = CanvasSession(canvasId = docId, store = store)
        session.load()

        // 1. Initial state (empty)
        session.applyAgentReplace("""{"bgColor":-1,"elements":[]}""", actorId = "agent-1")
        val checkpointsAfterFirst = session.checkpoints.value
        assertTrue(checkpointsAfterFirst.isNotEmpty())
        val firstCp = checkpointsAfterFirst.first()
        assertEquals(1L, firstCp.revision)

        // 2. Second state (added element)
        val addOp = CanvasOp.AddElementOp(
            opId = "op-add-1",
            actorId = "user-1",
            lamport = 1L,
            elementId = "elem-1",
            elementJson = """{"id":"elem-1","type":"rect"}""",
        )
        session.applyLocal(addOp)
        val checkpointsAfterSecond = session.checkpoints.value
        assertEquals(2, checkpointsAfterSecond.size)
        val secondCp = checkpointsAfterSecond.first()
        assertEquals(2L, secondCp.revision)
        assertTrue(session.sceneJsonOrEmpty().contains("elem-1"))

        // 3. Third state (different background)
        val bgOp = CanvasOp.SetBackgroundOp(
            opId = "op-bg-1",
            actorId = "user-1",
            lamport = 2L,
            colorHex = "#FF0000",
        )
        session.applyLocal(bgOp)
        assertEquals(3, session.checkpoints.value.size)
        assertEquals(3L, session.document.value?.revision)

        // 4. Restore to first checkpoint (rev 1: empty elements)
        val restoredDoc = session.restoreCheckpoint(firstCp.checkpointId, actorId = "user-1")
        assertEquals(4L, restoredDoc.revision) // Revision advances
        assertEquals(firstCp.sceneJson, restoredDoc.sceneJson)
        assertFalse(restoredDoc.sceneJson.contains("elem-1"))

        // New checkpoint was recorded for the restore
        val latestCp = session.checkpoints.value.first()
        assertEquals(4L, latestCp.revision)
        assertTrue(latestCp.description.contains("Restored to rev 1"))
    }

    @Test
    fun testRestoreEnforcesAcl() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val docId = CanvasId("canvas-history-acl")
        val acl = CanvasAcl(
            ownerUserId = "alice",
            writerUserIds = setOf("bob"),
        )
        val doc = CanvasDocument(
            id = docId,
            title = "Protected History",
            revision = 1L,
            sceneJson = """{"bgColor":-1,"elements":[]}""",
            acl = acl,
        )
        store.upsert(doc)

        val session = CanvasSession(canvasId = docId, store = store)
        session.load()

        val addOp = CanvasOp.AddElementOp(
            opId = "op-add",
            actorId = "alice",
            lamport = 1L,
            elementId = "e1",
            elementJson = """{"id":"e1"}""",
        )
        session.applyLocal(addOp)
        val checkpoints = session.checkpoints.value
        val initialCp = checkpoints.last()

        // Eve is not allowed to restore
        assertFailsWith<UnauthorizedCanvasMutationException> {
            session.restoreCheckpoint(initialCp.checkpointId, actorId = "eve")
        }

        // Bob (authorized writer) can restore
        val restored = session.restoreCheckpoint(initialCp.checkpointId, actorId = "bob")
        assertEquals(3L, restored.revision)
    }

    @Test
    fun testHistoryBoundedByMaxCheckpoints() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val docId = CanvasId("canvas-history-bound")
        val session = CanvasSession(canvasId = docId, store = store)

        // Perform 40 edits
        for (i in 1..40) {
            session.applyLocal(
                CanvasOp.SetBackgroundOp(
                    opId = "op-$i",
                    actorId = "user-1",
                    lamport = i.toLong(),
                    colorHex = "#0000$i",
                )
            )
        }

        assertTrue(session.checkpoints.value.size <= CanvasSession.MAX_CHECKPOINTS)
        assertEquals(CanvasSession.MAX_CHECKPOINTS, session.checkpoints.value.size)
        // Most recent should be rev 40
        assertEquals(40L, session.checkpoints.value.first().revision)
    }
}
