package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TimelineExactCanonicalWriterTest {
    @Test fun liveReductionCommitsOnceAndRejectsStaleFence() = runTest {
        val store = Store()
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val stale = engine.beginLive(selection)
        val fence = engine.beginLive(selection)
        assertFalse(engine.ingest(stale, TimelineStreamFrame.Message(message("old"))))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(message("hello"))))
        assertEquals(0L, engine.publication.value.durableRevision)
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        assertEquals(1, store.rows.size)
        assertEquals(1L, engine.publication.value.durableRevision)
        assertFalse(engine.acknowledgeSettlement(fence, emptyMap()))
        assertTrue(engine.acknowledgeSettlement(fence, mapOf(TimelineMessageId("id") to 1L)))
        assertEquals(null, engine.live.value)
    }

    @Test fun exactWriterPreservesKeyOnDuplicateAndReopen() = runTest {
        val store = Store()
        val writer = TimelineExactCanonicalWriter(scope, 100_000)
        val record = TimelineRemoteRecord(TimelineMessageId("id"), message("hello"), 0)
        store.transaction(scope) { assertTrue(writer.merge(this, record)); nextRevision() }
        val key = store.rows.keys.single()
        store.transaction(scope) { assertFalse(TimelineExactCanonicalWriter(scope, 100_000).merge(this, record)) }
        assertEquals(key, store.rows.keys.single())
        assertEquals(2, store.evidence.size)
    }

    @Test fun previewResolutionChecksRevisionAndReadsOnlySelectedBody() = runTest {
        val store = Store()
        val writer = TimelineExactCanonicalWriter(scope, 200_000)
        store.transaction(scope) {
            writer.merge(this, TimelineRemoteRecord(TimelineMessageId("id"), message("x".repeat(100_000)), 0))
            nextRevision()
        }
        val engine = CanonicalTimelineEngine(store, writer, enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val page = engine.load(selection, TimelineReadPosition.Tail, 1)
        val row = page.metadata.rows.single()
        val preview = TimelineSettledRecord(row.key, row.contentType, page.bodies.single(), page.metadata.revision, row.body)
        assertTrue(preview.isPreview)
        kotlin.test.assertFailsWith<IllegalArgumentException> { preview.toRenderItem() }
        val resolved = engine.resolveBody(selection, preview)
        assertFalse(resolved.isPreview)
        val item = assertIs<com.letta.mobile.data.chat.projection.ChatRenderItem.Single>(resolved.toRenderItem())
        assertEquals("x".repeat(100_000), item.message.content)
        assertEquals(row.body.encodedBytes, resolved.body.size.toLong())
        store.transaction(scope) { nextRevision() }
        kotlin.test.assertFailsWith<IllegalStateException> { engine.resolveBody(selection, preview) }
    }

    @Test fun historicalBodyReadsRespectPlatformChunkLimit() = runTest {
        val store = Store()
        val writer = TimelineExactCanonicalWriter(scope, 200_000)
        val record = TimelineRemoteRecord(TimelineMessageId("id"), message("x".repeat(100_000)), 0)
        store.transaction(scope) { writer.merge(this, record); nextRevision() }
        store.transaction(scope) { assertFalse(writer.merge(this, record)) }
        assertEquals(2, store.bodyReads)
    }

    @Test fun concurrentCursorCompletionsOnlyCommitCurrentRequest() = runTest {
        val store = Store()
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val requests = (1..64).map { engine.beginPage(selection) }
        val results = requests.map { request -> async {
            engine.applyPage(request, TimelineRemotePageResult.Page(request.remote.requestId, selection.generation,
                emptyList(), null, false, 0))
        } }.awaitAll()
        assertEquals(1, results.count { it == TimelineEnginePageOutcome.Applied })
        assertEquals(63, results.count { it == TimelineEnginePageOutcome.Stale })
        assertEquals(1L, store.current.revision)
    }

    @Test fun historicalCorrectionReadsOneBodyAmong28kRows() = runTest {
        val store = Store()
        val writer = TimelineExactCanonicalWriter(scope, 100_000)
        store.transaction(scope) { writer.merge(this, TimelineRemoteRecord(TimelineMessageId("id"), message("hello"), 0)) }
        val seed = store.rows.values.single()
        repeat(28_000) { index ->
            val key = TimelinePageKey(index.toLong(), TimelineMessageId("history-$index"))
            store.rows[key] = seed.copy(key = key)
        }
        store.bodyReads = 0
        store.transaction(scope) { writer.merge(this, TimelineRemoteRecord(TimelineMessageId("id"), message("hello world"), 0)) }
        assertEquals(1, store.bodyReads)
        assertEquals(28_001, store.rows.size)
    }

    @Test fun aliasedSettlementAcknowledgesCanonicalIdentity() = runTest {
        val store = Store()
        val writer = TimelineExactCanonicalWriter(scope, 100_000)
        val canonical = message("hello").toTimelineEvent(0.0)!!.copy(serverId = "canonical")
        store.transaction(scope) { writer.mergeEvent(this, canonical); nextRevision() }
        val engine = CanonicalTimelineEngine(store, writer, enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        engine.publishLive(fence, TimelineLiveBlock(listOf(TimelineRemoteRecord(TimelineMessageId("id"), message("hello"), 0)), true))
        val revision = engine.publication.value.durableRevision
        assertFalse(engine.acknowledgeSettlement(fence, mapOf(TimelineMessageId("id") to revision)))
        assertTrue(engine.acknowledgeSettlement(fence, mapOf(TimelineMessageId("canonical") to revision)))
        assertEquals(1, store.rows.size)
    }

    @Test fun oversizedHistoricalMergeRollsBackWithoutLosingRetry() = runTest {
        val store = Store()
        store.transaction(scope) { TimelineExactCanonicalWriter(scope, 100_000).merge(this,
            TimelineRemoteRecord(TimelineMessageId("id"), message("hello"), 0)); nextRevision() }
        val before = store.rows.values.single().body.copyOf()
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 1), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val request = engine.beginPage(selection)
        val page = TimelineRemotePageResult.Page(request.remote.requestId, selection.generation,
            listOf(TimelineRemoteRecord(TimelineMessageId("id"), message("hello world"), 0)), null, false, 0)
        repeat(2) {
            val failure = kotlin.test.assertFailsWith<TimelineMergeUnavailable> { engine.applyPage(request, page) }
            assertEquals("historical_body_budget", failure.reason)
            kotlin.test.assertContentEquals(before, store.rows.values.single().body)
            assertTrue(store.current.hasMore)
            assertEquals(1L, store.current.revision)
        }
    }

    private class Store : TimelineBoundedStore {
        var bodyReads = 0
        var current = TimelineDurableCheckpoint(0, TimelineContinuation.Initial, true)
        val rows = mutableMapOf<TimelinePageKey, TimelineStoredRecord>()
        val evidence = mutableMapOf<String, ByteArray>()
        override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T = block(Tx())
        override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T {
            val before = current
            val oldRows = rows.toMap()
            val oldEvidence = evidence.toMap()
            return try { block(Tx()) } catch (failure: Throwable) {
                current = before
                rows.clear(); rows.putAll(oldRows)
                evidence.clear(); evidence.putAll(oldEvidence)
                throw failure
            }
        }
        private inner class Tx : TimelineStoreTransaction {
            override suspend fun checkpoint() = current
            override suspend fun locate(identity: TimelineMessageId) = rows.keys.singleOrNull { it.identity == identity }
            override suspend fun metadata(position: TimelineReadPosition, maxRows: Int): TimelineMetadataPage {
                val selected = rows.values.sortedBy { it.key }.filter { position !is TimelineReadPosition.Around || it.key == position.key }.take(maxRows)
                return TimelineMetadataPage(selected.map { TimelineLedgerMetadata(it.key, TimelineBodyPointer(it.key.identity.value, it.body.size.toLong()), it.contentType, current.revision) }, null, null, current.revision)
            }
            override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray {
                require(maxBytes in 0..65_536)
                bodyReads++
                val bytes = rows.values.single { it.key.identity.value == pointer.value }.body
                return bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + maxBytes))
            }
            override suspend fun evidence(key: String, maxBytes: Int): ByteArray? = evidence[key]?.also { check(it.size <= maxBytes) }?.copyOf()
            override suspend fun put(record: TimelineStoredRecord) { rows[record.key] = record.copy(body = record.body.copyOf()) }
            override suspend fun putEvidence(key: String, value: ByteArray) { evidence[key] = value.copyOf() }
            override suspend fun deleteEvidence(key: String) { evidence.remove(key) }
            override suspend fun cursor(continuation: TimelineContinuation?, hasMore: Boolean) { current = current.copy(continuation = continuation, hasMore = hasMore) }
            override suspend fun nextRevision(): Long { current = current.copy(revision = current.revision + 1); return current.revision }
            override suspend fun delete(identity: TimelineMessageId, reason: TimelineDurableDeleteReason) { rows.keys.removeAll { it.identity == identity } }
        }
    }

    companion object {
        private val scope = TimelineScope("backend", "conversation")
        private fun message(content: String) = AssistantMessage(id = "id", contentRaw = kotlinx.serialization.json.JsonPrimitive(content), date = "2026-01-01T00:00:00Z")
    }
}
