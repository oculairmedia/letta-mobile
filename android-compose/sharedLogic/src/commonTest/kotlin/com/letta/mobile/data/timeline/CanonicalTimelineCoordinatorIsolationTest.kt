package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class CanonicalTimelineCoordinatorIsolationTest {
    @Test fun twoConversationsKeepIndependentOwnersAndStaleHandles() = runTest {
        val store = ScopedStore()
        // Live ingest writes nothing now, so each conversation's rows come from its own sync page.
        val remote = mutableMapOf<String, List<TimelineRemoteRecord>>()
        val transport = object : TimelineTransport {
            override suspend fun listConversationMessagePage(
                request: TimelineRemotePageRequest,
                progress: TimelinePageProgress?,
            ) = TimelineRemotePageResult.Page(
                request.requestId, request.selectionGeneration,
                remote[request.scope.conversationId].orEmpty(), null, false, 0,
            )
            override suspend fun sendConversationMessage(
                conversationId: String,
                request: com.letta.mobile.data.model.MessageCreateRequest,
            ) = error("send")
            override suspend fun streamConversation(conversationId: String) = error("stream")
            override suspend fun listConversationMessages(
                conversationId: String, limit: Int?, after: String?, order: String?,
            ) = error("legacy hydration")
            override suspend fun listAgentMessages(
                agentId: String, limit: Int?, order: String?, conversationId: String?,
            ) = error("legacy hydration")
        }
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val firstScope = TimelineScope("backend", "first", "agent")
        val secondScope = TimelineScope("backend", "second", "agent")
        val first = coordinator.acquire(firstScope)
        val second = coordinator.acquire(secondScope)
        val firstFence = coordinator.beginLive(first)
        val secondFence = coordinator.beginLive(second)
        val firstMessage = message("first", "first-id")
        val secondMessage = message("second", "second-id")
        assertTrue(coordinator.ingest(first, firstFence, TimelineStreamFrame.Message(firstMessage)))
        assertTrue(coordinator.ingest(second, secondFence, TimelineStreamFrame.Message(secondMessage)))
        assertTrue(coordinator.ingest(first, firstFence, TimelineStreamFrame.Done))
        assertTrue(coordinator.ingest(second, secondFence, TimelineStreamFrame.Done))
        assertEquals(0, store.rows(firstScope).size)
        assertEquals(0, store.rows(secondScope).size)
        remote["first"] = listOf(TimelineRemoteRecord(TimelineMessageId("first-id"), firstMessage, 0))
        remote["second"] = listOf(TimelineRemoteRecord(TimelineMessageId("second-id"), secondMessage, 0))
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(first))
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(second))
        // A conversation's sync page lands in its own scope only.
        assertEquals(1, store.rows(firstScope).size)
        assertEquals(1, store.rows(secondScope).size)
        assertEquals(null, coordinator.attach(first, TimelineMessageId("missing")))
        val search = kotlin.test.assertNotNull(coordinator.attach(first, TimelineMessageId("first-id")))
        assertFalse(coordinator.retire(first))
        coordinator.detach(search)
        val lease = kotlin.test.assertNotNull(coordinator.attach(first))
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(first))
        assertFalse(coordinator.retire(first))
        coordinator.detach(lease)
        assertTrue(coordinator.retire(first))
        assertEquals(1, store.rows(firstScope).size)
        assertEquals(1, store.rows(secondScope).size)
        assertFalse(coordinator.ingest(first, firstFence, TimelineStreamFrame.Message(message("stale", "stale-id"))))
        val restarted = coordinator.acquire(firstScope)
        assertTrue(restarted !== first)
        assertEquals(restarted, coordinator.current(firstScope))
        val restartedFence = coordinator.beginLive(restarted)
        val restartedMessage = message("restarted", "restart-id")
        assertTrue(coordinator.ingest(restarted, restartedFence, TimelineStreamFrame.Message(restartedMessage)))
        assertTrue(coordinator.ingest(restarted, restartedFence, TimelineStreamFrame.Done))
        // The replacement owner reuses the same durable scope, and only sync appends to it.
        assertEquals(1, store.rows(firstScope).size)
        remote["first"] = remote.getValue("first") +
            TimelineRemoteRecord(TimelineMessageId("restart-id"), restartedMessage, 0)
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(restarted))
        assertEquals(2, store.rows(firstScope).size)
        assertFalse(coordinator.ingest(first, firstFence, TimelineStreamFrame.Message(message("after-restart", "late-id"))))
        assertTrue(coordinator.retire(restarted))
        assertEquals(second, coordinator.current(secondScope))
        coordinator.revoke()
        assertEquals(null, coordinator.current(secondScope))
        assertFalse(coordinator.ingest(second, secondFence, TimelineStreamFrame.Message(message("revoked", "revoked-id"))))
        assertFailsWith<IllegalStateException> { coordinator.acquire(firstScope) }
        assertEquals(2, store.rows(firstScope).size)
        assertEquals(1, store.rows(secondScope).size)
    }

    private fun message(content: String, id: String) = AssistantMessage(
        id = id,
        contentRaw = kotlinx.serialization.json.JsonPrimitive(content),
        date = "2026-01-01T00:00:00Z",
        otid = id,
    )

    private class ScopedStore : TimelineBoundedStore {
        private val checkpoints = mutableMapOf<TimelineScope, TimelineDurableCheckpoint>()
        private val scopedRows = mutableMapOf<TimelineScope, MutableMap<TimelinePageKey, TimelineStoredRecord>>()
        private val evidence = mutableMapOf<TimelineScope, MutableMap<String, ByteArray>>()
        private val tools = mutableMapOf<TimelineScope, TestToolIndexState>()
        fun rows(scope: TimelineScope) = scopedRows[scope].orEmpty()
        private fun checkpoint(scope: TimelineScope) =
            checkpoints.getOrPut(scope) { TimelineDurableCheckpoint(0, TimelineContinuation.Initial, false) }
        override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T =
            block(Tx(scope, tools[scope]?.snapshot() ?: TestToolIndexState()))
        override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T {
            val before = checkpoint(scope)
            val oldRows = scopedRows[scope]?.toMap().orEmpty()
            val oldEvidence = evidence[scope]?.toMap().orEmpty()
            val toolCopy = tools[scope]?.snapshot() ?: TestToolIndexState()
            return try {
                block(Tx(scope, toolCopy)).also { tools[scope] = toolCopy }
            } catch (failure: Throwable) {
                checkpoints[scope] = before
                scopedRows[scope] = oldRows.toMutableMap()
                evidence[scope] = oldEvidence.toMutableMap()
                throw failure
            }
        }
        private inner class Tx(
            private val scope: TimelineScope,
            private val toolState: TestToolIndexState,
        ) : TimelineStoreTransaction {
            override suspend fun toolCall(callId: String) = toolState.entries[callId]
            override suspend fun unresolvedTools(afterCallId: String?, maxRows: Int) =
                toolState.unresolved(afterCallId, maxRows)
            override suspend fun toolSweepGeneration() = toolState.generation
            override suspend fun putToolCall(entry: TimelineToolIndexEntry) = toolState.put(entry)
            override suspend fun setToolSweepGeneration(next: Long) = toolState.advance(next)
            override suspend fun checkpoint() = checkpoint(scope)
            override suspend fun locate(identity: TimelineMessageId) =
                scopedRows[scope]?.keys?.singleOrNull { it.identity == identity }
            override suspend fun metadata(position: TimelineReadPosition, maxRows: Int): TimelineMetadataPage {
                val selected = scopedRows[scope]?.values.orEmpty().sortedBy { it.key }.take(maxRows)
                val revision = checkpoint(scope).revision
                return TimelineMetadataPage(
                    selected.map {
                        TimelineLedgerMetadata(it.key, TimelineBodyPointer(it.key.identity.value, it.body.size.toLong()), it.contentType, revision)
                    },
                    null, null, revision,
                )
            }
            override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray {
                val bytes = scopedRows[scope]!!.values.single { it.key.identity.value == pointer.value }.body
                return bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + maxBytes))
            }
            override suspend fun evidence(key: String, maxBytes: Int): ByteArray? =
                evidence[scope]?.get(key)?.copyOf()
            override suspend fun put(record: TimelineStoredRecord) {
                scopedRows.getOrPut(scope) { mutableMapOf() }[record.key] = record.copy(body = record.body.copyOf())
            }
            override suspend fun putEvidence(key: String, value: ByteArray) {
                evidence.getOrPut(scope) { mutableMapOf() }[key] = value.copyOf()
            }
            override suspend fun deleteEvidence(key: String) { evidence[scope]?.remove(key) }
            override suspend fun cursor(continuation: TimelineContinuation?, hasMore: Boolean) {
                checkpoints[scope] = checkpoint(scope).copy(continuation = continuation, hasMore = hasMore)
            }
            override suspend fun nextRevision(): Long {
                val next = checkpoint(scope).copy(revision = checkpoint(scope).revision + 1)
                checkpoints[scope] = next
                return next.revision
            }
            override suspend fun delete(identity: TimelineMessageId, reason: TimelineDurableDeleteReason) {
                scopedRows[scope]?.keys?.removeAll { it.identity == identity }
            }
        }
    }
}
