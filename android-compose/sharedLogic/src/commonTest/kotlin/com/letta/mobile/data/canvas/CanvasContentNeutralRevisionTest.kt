package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.CanvasActorId
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A change that leaves the board as it was is no revision (letta-mobile-v3qf9). Every client re-read
 * the whole scene on each revision, so a writer re-sending what the board already held kept every
 * open board re-importing it about once a second. The op still lands in the CRDT: its provenance is
 * what keeps the replicas converging.
 */
class CanvasContentNeutralRevisionTest {
    private val canvasId = CanvasId("canvas-neutral")
    private val note = """{"id":"n1","type":"Text","text":"same"}"""

    @Test
    fun replicasExchangingAContentNeutralOpConvergeAndNeitherGainsARevision() = runTest {
        val transport = LoopbackCanvasSyncTransport()
        val a = session(transport)
        val b = session(transport)
        a.startSync(backgroundScope)
        b.startSync(backgroundScope)
        runCurrent()
        a.applyLocal(CanvasOp.AddElementOp("op-add", "client_a", 5L, "n1", note))
        runCurrent()
        val revisionsBefore = a.revision() to b.revision()
        val historyBefore = a.checkpoints.value.size to b.checkpoints.value.size

        // B sends the value the board already holds, newer: nothing on the board changes.
        b.applyLocal(CanvasOp.UpdateElementOp("op-same", "client_b", 6L, "n1", note))
        runCurrent()

        assertEquals(revisionsBefore, a.revision() to b.revision(), "a content-neutral op is no revision on either replica")
        assertEquals(historyBefore, a.checkpoints.value.size to b.checkpoints.value.size, "and no history entry")
        assertEquals(a.sceneJsonOrEmpty(), b.sceneJsonOrEmpty(), "the replicas converge")
        assertEquals(6L, CanvasOpProjector.maxLamport(a.sceneJsonOrEmpty()), "the op's provenance landed in the CRDT")

        // The provenance is what decides the next race: an edit older than B's re-send loses on both.
        a.applyLocal(CanvasOp.UpdateElementOp("op-stale", "client_a", 5L, "n1", """{"id":"n1","type":"Text","text":"stale"}"""))
        runCurrent()
        assertEquals(a.sceneJsonOrEmpty(), b.sceneJsonOrEmpty())
        assertTrue("same" in b.sceneJsonOrEmpty() && "stale" !in b.sceneJsonOrEmpty())
    }

    @Test
    fun aRemoteOpThatLosesLastWriterWinsIsLoggedButIsNoRevision() = runTest {
        val s = session(transport = null)
        s.applyLocal(CanvasOp.AddElementOp("op-add", "client_a", 5L, "n1", note))
        val before = s.document.value
        assertNotNull(before)

        s.applyRemote(CanvasOp.UpdateElementOp("op-old", "client_b", 1L, "n1", """{"id":"n1","type":"Text","text":"old"}"""))

        assertEquals(before, s.document.value)
        assertTrue(s.opLog.has(canvasId, CanvasOp.UpdateElementOp("op-old", "client_b", 1L, "n1", note).canvasOpId))
    }

    @Test
    fun aRealChangeIsStillARevisionWithHistory() = runTest {
        val s = session(transport = null)
        s.applyLocal(CanvasOp.AddElementOp("op-add", "client_a", 5L, "n1", note))
        val revision = s.revision()
        val history = s.checkpoints.value.size

        s.applyRemote(CanvasOp.UpdateElementOp("op-edit", "client_b", 6L, "n1", """{"id":"n1","type":"Text","text":"edited"}"""))

        assertEquals(revision + 1, s.revision())
        assertEquals(history + 1, s.checkpoints.value.size)
    }

    @Test
    fun applyLocalSceneOfTheSceneAlreadyHeldEmitsNoOps() = runTest {
        val s = session(transport = null)
        s.applyLocal(CanvasOp.AddElementOp("op-add", "client_a", 5L, "n1", note))
        val revision = s.revision()
        // What DrawBox exports for the same board: no bookkeeping, keys in its own order.
        val exported = """{"elements":[{"text":"same","type":"Text","id":"n1"}],"bgColor":"#ffffffff"}"""
        assertNotEquals(exported, s.sceneJsonOrEmpty())

        val ops = s.applyLocalScene(exported, CanvasActorId("client_a"))

        assertEquals(emptyList(), ops)
        assertEquals(revision, s.revision())
    }

    @Test
    fun scenesDifferingOnlyInProvenanceHoldTheSameContent() {
        val first = CanvasOpProjector.project("", listOf(CanvasOp.AddElementOp("op-1", "a", 1L, "n1", note)))
        val resent = CanvasOpProjector.project(first, listOf(CanvasOp.UpdateElementOp("op-2", "b", 9L, "n1", note)))
        val removedGhost = CanvasOpProjector.project(resent, listOf(CanvasOp.RemoveElementOp("op-3", "b", 10L, "ghost")))
        val other = """{"id":"n1","type":"Text","text":"x"}"""
        val edited = CanvasOpProjector.project(first, listOf(CanvasOp.UpdateElementOp("op-4", "b", 9L, "n1", other)))

        assertNotEquals(first, resent)
        assertTrue(sameSceneContent(first, resent))
        assertTrue(sameSceneContent(resent, removedGhost), "a tombstone for what is gone either way changes nothing")
        assertTrue(!sameSceneContent(first, edited))
        assertTrue(!sameSceneContent("", first), "the first write to a board is a change")
    }

    private suspend fun session(transport: CanvasSyncTransport?): CanvasSession = CanvasSession.create(
        store = InMemoryCanvasDocumentStore(),
        options = CanvasCreateOptions(
            canvasId = canvasId,
            syncTransport = transport,
            acl = CanvasAcl(ownerUserId = "client_a", writerUserIds = setOf("client_b")),
        ),
    )

    private fun CanvasSession.revision(): Long = document.value?.revision ?: 0L
}
