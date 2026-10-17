package com.letta.mobile.data.canvas

import com.letta.mobile.data.document.HistoryBudget
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.storage.InMemoryKeyValueStore
import com.letta.mobile.data.storage.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** letta-mobile-ckmub.17.7: boards kept in a key-value store survive a reload; failures are faults, not crashes. */
class KeyValueCanvasStorageTest {
    private val store = InMemoryKeyValueStore()

    private fun board(id: String = "c-1", conversation: String? = "conv-1", revision: Long = 1) =
        CanvasDocument(CanvasId(id), conversationId = conversation, title = "Board", revision = revision, sceneJson = "{\"elements\":[]}")

    @Test
    fun aBoardSurvivesAReloadAndIsFoundByItsConversation() = runTest {
        KeyValueCanvasStorage(store).documents.createForConversationIfAbsent(board())

        val reloaded = KeyValueCanvasStorage(store).documents

        assertEquals(board(), reloaded.get(CanvasId("c-1")))
        assertEquals(board(), reloaded.getForConversation(ConversationId("conv-1")))
        assertEquals(board(), reloaded.createForConversationIfAbsent(board(id = "c-2")))
    }

    @Test
    fun theRevisionCheckHoldsAcrossAReload() = runTest {
        KeyValueCanvasStorage(store).documents.upsert(board())
        val reloaded = KeyValueCanvasStorage(store).documents

        assertTrue(reloaded.upsertIfRevision(board(revision = 2), expectedRevision = 1))
        assertEquals(false, reloaded.upsertIfRevision(board(revision = 3), expectedRevision = 1))
        assertEquals(2L, KeyValueCanvasStorage(store).documents.get(CanvasId("c-1"))?.revision)
    }

    @Test
    fun anUnsettledOpIsStillPendingAfterAReload() = runTest {
        val canvas = CanvasId("c-1")
        val log = KeyValueCanvasStorage(store).opLog
        log.append(canvas, op("op-1", 1))
        log.append(canvas, op("op-2", 2))
        log.settle(canvas, listOf(com.letta.mobile.data.model.CanvasOpId("op-1")))

        val reloaded = KeyValueCanvasStorage(store).opLog

        assertEquals(listOf("op-2"), reloaded.unsettled(canvas).map { it.opId })
        assertEquals(listOf("op-1", "op-2"), reloaded.getOps(canvas, 0L).map { it.opId })
        assertTrue(reloaded.has(canvas, com.letta.mobile.data.model.CanvasOpId("op-1")))
    }

    @Test
    fun settledHistoryIsBoundedAndUnsettledOpsAreKept() = runTest {
        val canvas = CanvasId("c-1")
        val storage = KeyValueCanvasStorage(store, budget = HistoryBudget(maxOps = 4, warnFraction = 0.5))
        (1..4).forEach { storage.opLog.append(canvas, op("op-$it", it.toLong())) }
        storage.opLog.settle(canvas, (1..4).map { com.letta.mobile.data.model.CanvasOpId("op-$it") })
        storage.opLog.append(canvas, op("op-5", 5))

        val kept = KeyValueCanvasStorage(store).opLog.getOps(canvas, 0L).map { it.opId }

        assertEquals(listOf("op-4", "op-5"), kept)
        assertTrue(storage.storageFaults.value.any { it.kind == CanvasStorageFault.Kind.COMPACTED })
    }

    @Test
    fun aFailingStoreIsAVisibleFaultAndTheBoardKeepsWorkingFromMemory() = runTest {
        val storage = KeyValueCanvasStorage(FailingStore)

        storage.documents.upsert(board())

        assertEquals(board(), storage.documents.get(CanvasId("c-1")))
        val faults = (storage.documents as CanvasStorageHealth).storageFaults.value
        assertNotNull(faults.firstOrNull { it.kind == CanvasStorageFault.Kind.SAVE_FAILED && it.isError })
        assertNotNull(faults.firstOrNull { it.kind == CanvasStorageFault.Kind.LOAD_FAILED })
    }

    private fun op(id: String, lamport: Long): CanvasOp =
        CanvasOp.RemoveElementOp(opId = id, actorId = "person", lamport = lamport, elementId = "e-$id")

    private object FailingStore : KeyValueStore {
        override suspend fun get(key: String): String? = error("storage unavailable")
        override suspend fun put(key: String, value: String) = error("quota exceeded")
        override suspend fun remove(key: String) = error("storage unavailable")
        override suspend fun keys(prefix: String): List<String> = error("storage unavailable")
    }
}
