package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.*
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.*

class DesktopTimelineBoundedStoreTest {
    private val scope = TimelineScope("backend", "conversation")
    private val root = Files.createTempDirectory("bounded-store-test")
    private val codec = object : DesktopTimelineCheckpointCodec {
        override fun encode(value: TimelineDurableCheckpoint): ByteArray {
            require(value.continuation == null)
            return "${value.revision}:${value.hasMore}".toByteArray()
        }
        override fun decode(bytes: ByteArray): TimelineDurableCheckpoint {
            val parts = bytes.toString(Charsets.UTF_8).split(':')
            return TimelineDurableCheckpoint(parts[0].toLong(), null, parts[1].toBooleanStrict())
        }
    }
    private var legacy = false
    private fun store() = DesktopTimelineBoundedStore(root, codec, persistentIndexForNewScopes = !legacy)
    private fun record(order: Long, id: String = "id-$order", bytes: ByteArray = byteArrayOf(1)) =
        TimelineStoredRecord(TimelinePageKey(order, TimelineMessageId(id)), "opaque", bytes)

    @Test fun transactionRestartExactEvidenceAndScopedPointers() = runTest {
        val bytes = ByteArray(180000) { (it % 251).toByte() }
        store().transaction(scope) {
            assertEquals(1L, nextRevision())
            put(record(Long.MIN_VALUE)); put(record(Long.MAX_VALUE, bytes = bytes))
            putEvidence("exact/tool/run/one", bytes)
            cursor(null, false)
            assertEquals(record(Long.MAX_VALUE).key, locate(TimelineMessageId("id-${Long.MAX_VALUE}")))
            val page = metadata(TimelineReadPosition.Tail, 2)
            assertContentEquals(bytes.copyOfRange(65530, 65600), body(page.rows.last().body, 65530, 70))
        }
        bytes.fill(0)
        lateinit var pointer: TimelineBodyPointer
        store().read(scope) {
            assertEquals(TimelineDurableCheckpoint(1, null, false), checkpoint())
            val page = metadata(TimelineReadPosition.Tail, 2)
            assertEquals(listOf(Long.MIN_VALUE, Long.MAX_VALUE), page.rows.map { it.key.order })
            pointer = page.rows.last().body
            assertEquals(1L, page.rows.last().revision)
            assertContentEquals(ByteArray(70) { ((65530 + it) % 251).toByte() }, body(pointer, 65530, 70))
            assertFailsWith<IllegalArgumentException> { evidence("exact/tool/run/one", 10) }
            assertEquals(180000, evidence("exact/tool/run/one", 180000)?.size)
            assertNull(evidence("exact/tool/run/two", 180000))
        }
        store().read(TimelineScope("backend", "other")) {
            assertEquals(0L, checkpoint().revision)
            assertFailsWith<IllegalArgumentException> { body(pointer, 0, 1) }
        }
    }

    @Test fun rollbackRevisionAndEscapedCallbacks() = runTest {
        var escaped: TimelineStoreTransaction? = null
        assertFailsWith<CancellationException> {
            store().transaction(scope) {
                escaped = this
                nextRevision(); put(record(1)); putEvidence("e", byteArrayOf(3))
                throw CancellationException("before commit")
            }
        }
        assertFailsWith<IllegalStateException> { escaped!!.checkpoint() }
        store().read(scope) {
            assertEquals(0L, checkpoint().revision)
            assertNull(locate(TimelineMessageId("id-1"))); assertNull(evidence("e", 1))
        }
        assertFailsWith<IllegalArgumentException> { store().transaction(scope) { put(record(1)) } }
        assertFailsWith<IllegalStateException> { store().transaction(scope) { nextRevision(); nextRevision() } }
        store().transaction(scope) { nextRevision(); put(record(2)) }
        store().read(scope) { assertEquals(1L, checkpoint().revision) }
    }

    @Test fun retainedSnapshotReplacementDeletionAndBoundaryMapping() = runTest {
        val store = store()
        store.transaction(scope) {
            nextRevision()
            for (i in 0L..9) put(record(i))
            putEvidence("keep", byteArrayOf(8)); putEvidence("delete", byteArrayOf(9))
        }
        store.read(scope) {
            val old = metadata(TimelineReadPosition.Tail, 3)
            assertEquals(listOf(7L, 8L, 9L), old.rows.map { it.key.order })
            assertEquals(old.rows.first().key, old.older); assertNull(old.newer)
            store.transaction(scope) {
                nextRevision(); put(record(20, "id-9", byteArrayOf(7)))
                delete(TimelineMessageId("id-8"), TimelineDurableDeleteReason.UserDeletion)
                deleteEvidence("delete")
            }
            assertContentEquals(byteArrayOf(1), body(old.rows.last().body, 0, 1))
            store.read(scope) {
                assertFailsWith<IllegalArgumentException> { body(old.rows.last().body, 0, 1) }
                assertEquals(20L, locate(TimelineMessageId("id-9"))?.order)
                assertNull(locate(TimelineMessageId("id-8")))
                assertContentEquals(byteArrayOf(8), evidence("keep", 1)); assertNull(evidence("delete", 1))
                val around = metadata(TimelineReadPosition.Around(record(5).key), 3)
                assertEquals(listOf(4L, 5L, 6L), around.rows.map { it.key.order })
                val before = metadata(TimelineReadPosition.Before(record(4).key), 2)
                assertEquals(listOf(2L, 3L), before.rows.map { it.key.order })
                assertEquals(before.rows.last().key, before.newer)
                val after = metadata(TimelineReadPosition.After(record(6).key), 2)
                assertEquals(listOf(7L, 20L), after.rows.map { it.key.order })
            }
        }
    }

    @Test fun exactUtf16KeysAndAuxiliaryCorruptionFailClosed() = runTest {
        legacy = true // Explicit v1 compatibility/corruption regression.
        val ids = listOf("\uD800", "\uD801", "?")
        store().transaction(scope) {
            nextRevision()
            ids.forEachIndexed { index, id -> put(record(index.toLong(), id)); putEvidence(id, byteArrayOf(index.toByte())) }
        }
        store().read(scope) {
            ids.forEachIndexed { index, id ->
                assertEquals(index.toLong(), locate(TimelineMessageId(id))?.order)
                assertContentEquals(byteArrayOf(index.toByte()), evidence(id, 1))
            }
        }
        val evidencePath = Files.walk(root).use { paths ->
            paths.filter { it.fileName.toString().startsWith("e-") }.findFirst().orElseThrow()
        }
        java.io.RandomAccessFile(evidencePath.toFile(), "rw").use { it.seek(it.length() - 1); it.write(123) }
        store().read(scope) {
            var failures = 0
            for (id in ids) try { evidence(id, 1) } catch (_: IllegalArgumentException) { failures++ }
            assertEquals(1, failures)
        }
    }

    @Test fun concurrentWriterIsFencedAndStagedReadsAreBounded() = runTest {
        store().transaction(scope) {
            nextRevision(); for (i in 0L..10) put(record(i))
            assertFailsWith<java.nio.channels.OverlappingFileLockException> {
                store().transaction(scope) { nextRevision(); put(record(12)) }
            }
            val around = metadata(TimelineReadPosition.Around(record(5).key), 3)
            assertEquals(listOf(4L, 5L, 6L), around.rows.map { it.key.order })
            val before = metadata(TimelineReadPosition.Before(record(4).key), 2)
            assertEquals(listOf(2L, 3L), before.rows.map { it.key.order })
            assertEquals(before.rows.last().key, before.newer)
            val after = metadata(TimelineReadPosition.After(record(6).key), 2)
            assertEquals(listOf(7L, 8L), after.rows.map { it.key.order })
        }
    }

    @Test fun publishedEnginePagesRealFilesAndPersistsCursorOnlyRevision() = runTest {
        val backend = store()
        backend.transaction(scope) {
            for (i in 0L..9) put(record(i))
            assertEquals(3, metadata(TimelineReadPosition.Tail, 3).rows.size)
            nextRevision()
        }
        val engine = CanonicalTimelineEngine(backend, TimelineCanonicalWriter { _, _ -> error("empty page") },
            TimelinePageBudget(3, 100), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val source = TimelineLedgerPagingSource(engine, selection)
        val tail = assertIs<androidx.paging.PagingSource.LoadResult.Page<TimelinePageKey, TimelineSettledRecord>>(
            source.load(androidx.paging.PagingSource.LoadParams.Refresh(null, 3, false)),
        )
        assertEquals(listOf(9L, 8L, 7L), tail.data.map { it.key.order })
        val older = assertIs<androidx.paging.PagingSource.LoadResult.Page<TimelinePageKey, TimelineSettledRecord>>(
            source.load(androidx.paging.PagingSource.LoadParams.Append(requireNotNull(tail.nextKey), 3, false)),
        )
        assertEquals(listOf(6L, 5L, 4L), older.data.map { it.key.order })
        val request = engine.beginPage(selection)
        assertEquals(TimelineEnginePageOutcome.Applied, engine.applyPage(request, TimelineRemotePageResult.Page(
            request.remote.requestId, selection.generation, emptyList(), null, false, 0,
        )))
        assertIs<androidx.paging.PagingSource.LoadResult.Invalid<TimelinePageKey, TimelineSettledRecord>>(
            source.load(androidx.paging.PagingSource.LoadParams.Refresh(null, 3, false)),
        )
        engine.release(selection)
        store().read(scope) {
            assertEquals(TimelineDurableCheckpoint(2, null, false), checkpoint())
            assertEquals(10, metadata(TimelineReadPosition.Tail, 20).rows.size)
        }
        val target = assertIs<TimelineEngineOpen.Opened>(engine.open(scope, TimelineMessageId("id-5"))).selection
        val centered = assertIs<androidx.paging.PagingSource.LoadResult.Page<TimelinePageKey, TimelineSettledRecord>>(
            TimelineLedgerPagingSource(engine, target).load(androidx.paging.PagingSource.LoadParams.Refresh(target.anchor, 3, false)),
        )
        assertEquals(listOf(6L, 5L, 4L), centered.data.map { it.key.order })
    }

    @Test fun substitutedOrdinalAndOtherCallbackPointersFailClosed() = runTest {
        store().transaction(scope) { nextRevision(); put(record(1)); put(record(2, bytes = byteArrayOf(2))) }
        store().read(scope) {
            val rows = metadata(TimelineReadPosition.Tail, 2).rows
            val parts = rows.first().body.value.split(':').toMutableList()
            parts[1] = rows.last().body.value.split(':')[1]
            assertFailsWith<IllegalArgumentException> {
                body(TimelineBodyPointer(parts.joinToString(":"), rows.first().body.encodedBytes), 0, 1)
            }
            val issued = rows.first().body
            assertEquals(1, body(issued, 0, 1).size)
            store().read(scope) {
                assertFailsWith<IllegalArgumentException> { body(issued, 0, 1) }
            }
        }
    }

    @Test fun cursorOnlyRevisionsReuseHistoryWithoutOverlayChains() = runTest(timeout = kotlin.time.Duration.parse("5m")) {
        legacy = true // Keep the published v1 corpus compatibility gate.
        val backend = store()
        backend.transaction(scope) {
            nextRevision()
            for (i in 0L until 28000) put(record(i))
            putEvidence("keep", byteArrayOf(42))
        }
        val directory = Files.list(root).use { it.findFirst().orElseThrow() }
        fun generation() = java.io.RandomAccessFile(directory.resolve("active").toFile(), "r").use { it.readUTF() }
        val original = generation()
        repeat(8) { iteration ->
            backend.transaction(scope) { nextRevision(); cursor(null, iteration % 2 == 0) }
            val current = generation()
            assertTrue(Files.isSameFile(directory.resolve("$original.index"), directory.resolve("$current.index")))
            assertTrue(Files.isSameFile(directory.resolve("$original.body"), directory.resolve("$current.body")))
            val aux = directory.resolve("$current.aux")
            assertEquals(2L, Files.list(aux).use { it.count() })
            assertTrue(Files.size(aux.resolve("data-generation")) < 128)
            store().read(scope) {
                assertEquals(iteration + 2L, checkpoint().revision)
                assertEquals(27999L, locate(TimelineMessageId("id-27999"))?.order)
                assertEquals(40, metadata(TimelineReadPosition.Tail, 40).rows.size)
                assertContentEquals(byteArrayOf(42), evidence("keep", 1))
            }
        }
        assertFailsWith<CancellationException> {
            backend.transaction(scope) { nextRevision(); cursor(null, false); throw CancellationException() }
        }
        store().read(scope) { assertEquals(9L, checkpoint().revision) }
        // A later row mutation must resolve the data owner, not the cursor-only auxiliary directory.
        backend.transaction(scope) { nextRevision(); put(record(28000)) }
        store().read(scope) {
            assertEquals(10L, checkpoint().revision)
            assertContentEquals(byteArrayOf(42), evidence("keep", 1))
            assertEquals(28000L, locate(TimelineMessageId("id-28000"))?.order)
        }
    }

    @Test fun persistentWritesKeepIndependentRootsAndRejectOversizedBatches() = runTest(timeout = kotlin.time.Duration.parse("5m")) {
        val backend = store()
        repeat(4) { batch ->
            backend.transaction(scope) {
                nextRevision()
                repeat(64) { put(record((batch * 64 + it).toLong())) }
                putEvidence("exact-\uD800", byteArrayOf(batch.toByte()))
            }
        }
        val directory = Files.list(root).use { it.findFirst().orElseThrow() }.resolve("v2")
        val index = DesktopPersistentTimelineIndex(directory)
        val original = assertNotNull(index.open())
        backend.transaction(scope) { nextRevision(); cursor(null, false) }
        assertEquals(original.roots, index.open()?.roots)
        val before = Files.list(directory).use { paths -> paths.mapToLong { Files.size(it) }.sum() }
        backend.read(scope) {
            val old = metadata(TimelineReadPosition.Tail, 1).rows.single()
            backend.transaction(scope) { nextRevision(); put(record(-100, "id-255", byteArrayOf(9))) }
            assertContentEquals(byteArrayOf(1), body(old.body, 0, 1))
        }
        val after = Files.list(directory).use { paths -> paths.mapToLong { Files.size(it) }.sum() }
        assertTrue(after - before < 512 * 1024, "ordinary write bytes=${after - before}")
        assertEquals(original.roots.evidence, index.open()?.roots?.evidence)
        backend.read(scope) {
            assertEquals(-100, locate(TimelineMessageId("id-255"))?.order?.toInt())
            assertContentEquals(byteArrayOf(3), evidence("exact-\uD800", 1))
            val page = metadata(TimelineReadPosition.After(record(Long.MIN_VALUE).key), 1)
            assertContentEquals(byteArrayOf(9), body(page.rows.single().body, 0, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            backend.transaction(scope) { nextRevision(); put(record(999, bytes = ByteArray(8 * 1024 * 1024))) }
        }
        assertFailsWith<IllegalArgumentException> {
            backend.transaction(scope) { nextRevision(); repeat(257) { put(record((1000 + it).toLong())) } }
        }
        backend.read(scope) { assertEquals(6L, checkpoint().revision); assertNull(locate(TimelineMessageId("id-999"))) }
    }

    @Test fun emptyBodyAndRevisionExhaustion() = runTest {
        store().transaction(scope) { nextRevision(); put(record(1, bytes = ByteArray(0))) }
        store().read(scope) {
            val page = TimelineBoundedReader(store()).load(scope, TimelineReadPosition.Tail, TimelinePageBudget(1, 1))
            assertEquals(0, page.bodies.single().size)
        }
        val overflowCodec = object : DesktopTimelineCheckpointCodec {
            override fun encode(value: TimelineDurableCheckpoint) = codec.encode(value)
            override fun decode(bytes: ByteArray) = TimelineDurableCheckpoint(Long.MAX_VALUE, null, false)
        }
        assertFailsWith<ArithmeticException> {
            DesktopTimelineBoundedStore(root, overflowCodec).transaction(scope) { nextRevision() }
        }
    }
}
