package com.letta.mobile.data.canvas

import com.letta.mobile.data.document.DocumentBacking
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InMemoryCanvasDurabilityTest {
    @Test
    fun durableSaveAcknowledgementDelegatesTheCanvasIdAndEveryOutcome() = runTest {
        val backing = HealthBacking()
        val store = InMemoryCanvasDocumentStore(backing)
        val failedId = CanvasId("failed")
        val savedId = CanvasId("saved")
        val exceptionalId = CanvasId("exceptional")
        backing.nextResult = false
        assertFalse(store.awaitDurableSave(failedId))

        backing.nextResult = true
        assertTrue(store.awaitDurableSave(savedId))

        val failure = IllegalStateException("health failed")
        backing.nextFailure = failure
        assertSame(failure, assertFailsWith<IllegalStateException> { store.awaitDurableSave(exceptionalId) })
        assertEquals(listOf(failedId, savedId, exceptionalId), backing.acknowledgedIds)
    }

    @Test
    fun noHealthBackingTreatsSynchronousMemoryWritesAsDurable() = runTest {
        assertTrue(InMemoryCanvasDocumentStore().awaitDurableSave(CanvasId("memory")))
    }

    private class HealthBacking : DocumentBacking<CanvasDocument>, CanvasStorageHealth {
        override val storageFaults = MutableStateFlow(emptyList<CanvasStorageFault>())
        val acknowledgedIds = mutableListOf<CanvasId>()
        var nextResult = true
        var nextFailure: Throwable? = null

        override suspend fun loadAll(): List<CanvasDocument> = emptyList()
        override suspend fun write(doc: CanvasDocument) = Unit
        override fun saveFailureGeneration(canvasId: CanvasId): Long = 0L
        override suspend fun awaitDurableSave(canvasId: CanvasId): Boolean {
            acknowledgedIds += canvasId
            nextFailure?.let { throw it }
            return nextResult
        }
    }
}
