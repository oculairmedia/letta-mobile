package com.letta.mobile.data.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunCursorStoreCommonTest {

    @Test
    fun inMemoryStoreRecordsOnlyAdvancingSequences() {
        val store = RunCursorStore.inMemory()

        store.ensureLoaded()
        store.record(conversationId = "conversation-1", runId = "run-1", seq = 1L)
        store.record(conversationId = "conversation-1", runId = "run-1", seq = 1L)
        store.record(conversationId = "conversation-1", runId = "run-1", seq = 0L)
        store.record(conversationId = "conversation-1", runId = "run-1", seq = 3L)
        store.record(conversationId = "conversation-1", runId = "run-1", seq = 2L)

        assertEquals(
            expected = mapOf("run-1" to 3L),
            actual = store.activeRuns("conversation-1"),
        )
    }

    @Test
    fun inMemoryStoreIgnoresInvalidIdentityFields() {
        val store = RunCursorStore.inMemory()

        store.record(conversationId = "", runId = "run-1", seq = 1L)
        store.record(conversationId = "conversation-1", runId = "", seq = 1L)

        assertTrue(store.allActiveRuns().isEmpty())
    }

    @Test
    fun inMemoryStoreClearsRunsAndDropsEmptyConversations() {
        val store = RunCursorStore.inMemory()

        store.record(conversationId = "conversation-1", runId = "run-1", seq = 1L)
        store.record(conversationId = "conversation-1", runId = "run-2", seq = 2L)

        store.clear(conversationId = "conversation-1", runId = "run-1")
        assertEquals(
            expected = mapOf("run-2" to 2L),
            actual = store.activeRuns("conversation-1"),
        )

        store.clear(conversationId = "conversation-1", runId = "run-2")
        assertTrue(store.allActiveRuns().isEmpty())
    }

    @Test
    fun inMemoryStoreReturnsDefensiveSnapshots() {
        val store = RunCursorStore.inMemory()

        store.record(conversationId = "conversation-1", runId = "run-1", seq = 1L)
        val allRunsSnapshot = store.allActiveRuns()
        val conversationSnapshot = store.activeRuns("conversation-1")

        store.record(conversationId = "conversation-1", runId = "run-1", seq = 2L)

        assertEquals(
            expected = mapOf("conversation-1" to mapOf("run-1" to 1L)),
            actual = allRunsSnapshot,
        )
        assertEquals(
            expected = mapOf("run-1" to 1L),
            actual = conversationSnapshot,
        )
    }
}
