package com.letta.mobile.data.timeline.snapshot

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfirmedTimelineStoreTest {

    @Test
    fun writeAndReadSnapshotSucceeds() = runTest {
        val store = InMemoryConfirmedTimelineStore()
        val scope = TimelineScope(backendId = "b1", conversationId = "c1")
        val envelope = StoredTimelineEnvelope(
            schemaVersion = 1,
            scope = scope,
            revision = 1L,
            events = emptyList(),
            writtenAtMillis = 1000L,
        )

        val written = store.writeSnapshot(envelope)
        assertTrue(written)

        val read = store.readSnapshot(scope)
        assertNotNull(read)
        assertEquals(1L, read.revision)
    }

    @Test
    fun staleRevisionWriteIsRejected() = runTest {
        val store = InMemoryConfirmedTimelineStore()
        val scope = TimelineScope(backendId = "b1", conversationId = "c1")

        assertTrue(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 5L)))
        // Same revision -> rejected
        assertFalse(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 5L)))
        // Lower revision -> rejected
        assertFalse(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 4L)))
        // Higher revision -> accepted
        assertTrue(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 6L)))

        assertEquals(6L, store.readSnapshot(scope)?.revision)
    }

    @Test
    fun backendIsolationEnsuresNoCrossBackendLeakage() = runTest {
        val store = InMemoryConfirmedTimelineStore()
        val scopeA = TimelineScope(backendId = "backend-A", conversationId = "conv-1")
        val scopeB = TimelineScope(backendId = "backend-B", conversationId = "conv-1")

        store.writeSnapshot(StoredTimelineEnvelope(scope = scopeA, revision = 1L))

        assertNotNull(store.readSnapshot(scopeA))
        assertNull(store.readSnapshot(scopeB))

        // Clear backend-A only
        store.clearForBackend("backend-A")
        assertNull(store.readSnapshot(scopeA))
    }

    @Test
    fun pruneKeepsMostRecentConversationsPerBackend() = runTest {
        val store = InMemoryConfirmedTimelineStore()
        val backend = "b1"

        store.writeSnapshot(StoredTimelineEnvelope(scope = TimelineScope(backend, "c1"), revision = 1L, writtenAtMillis = 100L))
        store.writeSnapshot(StoredTimelineEnvelope(scope = TimelineScope(backend, "c2"), revision = 1L, writtenAtMillis = 200L))
        store.writeSnapshot(StoredTimelineEnvelope(scope = TimelineScope(backend, "c3"), revision = 1L, writtenAtMillis = 300L))
        store.writeSnapshot(StoredTimelineEnvelope(scope = TimelineScope(backend, "c4"), revision = 1L, writtenAtMillis = 400L))

        assertEquals(4, store.size())

        // Prune to 2
        store.prune(backend, maxRetainedConversations = 2)

        assertEquals(2, store.size())
        assertNotNull(store.readSnapshot(TimelineScope(backend, "c4")))
        assertNotNull(store.readSnapshot(TimelineScope(backend, "c3")))
        assertNull(store.readSnapshot(TimelineScope(backend, "c2")))
        assertNull(store.readSnapshot(TimelineScope(backend, "c1")))
    }
}
