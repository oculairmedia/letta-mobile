package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.CanvasOpId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-by4vm.22: the op log and the stored document are two stores with no shared
 * transaction. An op is appended first and settled only once the document write carrying it
 * succeeded, so a crash (simulated here by a store write that throws) between the two leaves an
 * unsettled op that the next session on the canvas folds in.
 */
class CanvasOpJournalTest {

    /** A document store whose writes can be made to fail: the "crash" between log and document. */
    private class FailingStore(private val inner: InMemoryCanvasDocumentStore = InMemoryCanvasDocumentStore()) :
        CanvasDocumentStore by inner {
        var failWrites = false

        override suspend fun upsert(doc: CanvasDocument) {
            if (failWrites) throw IllegalStateException("process died before the document was written")
            inner.upsert(doc)
        }
    }

    /** A log whose settle can be made to fail: the crash after the document write, before settling. */
    private class SettleFailingLog(private val inner: InMemoryCanvasOpLog = InMemoryCanvasOpLog()) : CanvasOpLog by inner {
        var failSettle = false

        override suspend fun settle(canvasId: CanvasId, opIds: Collection<CanvasOpId>) {
            if (failSettle) throw IllegalStateException("process died before the ops were settled")
            inner.settle(canvasId, opIds)
        }
    }

    private fun add(id: String, lamport: Long, actor: String = CanvasSession.LOCAL_USER_ACTOR_ID) = CanvasOp.AddElementOp(
        opId = "op-$id",
        actorId = actor,
        lamport = lamport,
        elementId = id,
        elementJson = """{"id":"$id","type":"Shape","zIndex":1,"points":["0.0,0.0","10.0,10.0"],"shapeType":"RECTANGLE"}""",
    )

    private fun String.hasElement(id: String): Boolean =
        CanvasOpProjector.stripMetadataForDrawBox(this).contains("\"id\":\"$id\"")

    private suspend fun board(store: CanvasDocumentStore, log: CanvasOpLog): CanvasSession =
        CanvasSession.create(store, CanvasCreateOptions(title = "Board", opLog = log))

    private suspend fun reopen(store: CanvasDocumentStore, log: CanvasOpLog, id: CanvasId): CanvasSession =
        requireNotNull(CanvasSession.open(store, id, CanvasConversationOptions(opLog = log)))

    @Test
    fun anOpLoggedBeforeAFailedDocumentWriteIsInTheDocumentWhenReopened() = runTest {
        val store = FailingStore()
        val log = InMemoryCanvasOpLog()
        val first = board(store, log)

        store.failWrites = true
        assertFailsWith<IllegalStateException> { first.applyLocal(add("rect-1", lamport = 5)) }
        store.failWrites = false
        assertTrue(log.has(first.canvasId, "op-rect-1"), "the op was not logged before the write")
        assertEquals(listOf("op-rect-1"), log.unsettled(first.canvasId).map { it.opId }, "the failed write left nothing pending")
        assertTrue(!requireNotNull(store.get(first.canvasId)).sceneJson.hasElement("rect-1"), "the document was written after all")

        val reopened = reopen(store, log, first.canvasId)

        assertTrue(reopened.sceneJsonOrEmpty().hasElement("rect-1"), "the reopened board lost the logged op")
        assertTrue(requireNotNull(store.get(first.canvasId)).sceneJson.hasElement("rect-1"), "the recovered op was not stored")
        assertEquals(emptyList(), log.unsettled(first.canvasId), "the recovered op is still pending")
    }

    @Test
    fun theSessionThatFailedToWriteFoldsTheOpInBeforeItsNextChange() = runTest {
        val store = FailingStore()
        val log = InMemoryCanvasOpLog()
        val session = board(store, log)

        store.failWrites = true
        assertFailsWith<IllegalStateException> { session.applyLocal(add("rect-1", lamport = 5)) }
        store.failWrites = false
        session.applyLocal(add("rect-2", lamport = 6))

        val stored = requireNotNull(store.get(session.canvasId)).sceneJson
        assertTrue(stored.hasElement("rect-1"), "the op whose write failed was dropped by the next write")
        assertTrue(stored.hasElement("rect-2"), "the next op was not written")
        assertEquals(emptyList(), log.unsettled(session.canvasId))
    }

    @Test
    fun aRemoteOpRedeliveredAfterTheCrashIsNotDeduplicatedAway() = runTest {
        val store = FailingStore()
        val log = InMemoryCanvasOpLog()
        val first = board(store, log)
        val remote = add("agent-rect", lamport = 9, actor = "agent-1")

        store.failWrites = true
        assertFailsWith<IllegalStateException> { first.applyRemote(remote, vouchedActor = "agent-1") }
        store.failWrites = false

        // Before the journal: the log already "has" it, the redelivery was dropped, and the
        // document never got the op. Now the reopened board folds it in first.
        val reopened = reopen(store, log, first.canvasId)
        assertNull(reopened.applyRemote(remote, vouchedActor = "agent-1"), "a logged op was applied twice")
        assertTrue(reopened.sceneJsonOrEmpty().hasElement("agent-rect"), "the redelivered op is missing")
        assertTrue(requireNotNull(store.get(first.canvasId)).sceneJson.hasElement("agent-rect"))
    }

    @Test
    fun anOpWrittenButNotSettledReopensWithoutAnotherRevision() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val log = SettleFailingLog()
        val first = board(store, log)

        log.failSettle = true
        first.applyLocal(add("rect-1", lamport = 5)) // a settle that fails does not fail the edit
        log.failSettle = false
        assertEquals(listOf("op-rect-1"), log.unsettled(first.canvasId).map { it.opId })
        val revision = requireNotNull(store.get(first.canvasId)).revision

        val reopened = reopen(store, log, first.canvasId)

        assertEquals(revision, requireNotNull(store.get(first.canvasId)).revision, "an op the document held was written again")
        assertTrue(reopened.sceneJsonOrEmpty().hasElement("rect-1"))
        assertEquals(emptyList(), log.unsettled(first.canvasId), "the op the document holds is still pending")
    }

    @Test
    fun aDocumentAheadOfItsLogOpensAsItIsAndTakesTheOpOnce() = runTest {
        // The document got an op this log never saw (peer sync of the notebook, another app's
        // session): nothing to replay, nothing to roll back, and its later delivery is idempotent.
        val store = InMemoryCanvasDocumentStore()
        val otherLog = InMemoryCanvasOpLog()
        val first = board(store, otherLog)
        val remote = add("agent-rect", lamport = 9, actor = "agent-1")
        first.applyRemote(remote, vouchedActor = "agent-1")
        val before = requireNotNull(store.get(first.canvasId))

        val log = InMemoryCanvasOpLog()
        val reopened = reopen(store, log, first.canvasId)
        assertEquals(before.revision, requireNotNull(store.get(first.canvasId)).revision, "opening rewrote the document")
        assertEquals(before.sceneJson, reopened.sceneJsonOrEmpty())

        reopened.applyRemote(remote, vouchedActor = "agent-1")
        val scene = CanvasOpProjector.stripMetadataForDrawBox(reopened.sceneJsonOrEmpty())
        assertEquals(1, Regex("\"id\":\"agent-rect\"").findAll(scene).count(), "the op landed twice")
        assertEquals(emptyList(), log.unsettled(first.canvasId))
    }
}
