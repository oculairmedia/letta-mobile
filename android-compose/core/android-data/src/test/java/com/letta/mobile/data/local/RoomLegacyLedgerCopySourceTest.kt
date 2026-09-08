package com.letta.mobile.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RoomLegacyLedgerCopySourceTest {
    @Test fun realLegacySqlSlicesBodiesAndPreservesRollbackSource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val legacy = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, TimelineLedgerDatabase::class.java).build()
        try {
            val scope = TimelineScope("b", "c", "a")
            val payload = ByteArray(1024 * 1024 + 19) { (it % 251).toByte() }
            val dao = legacy.confirmedTimelineSnapshotDao()
            val row = NormalizedTimelineSnapshotRowEntity("b", "c", 12, 34, 0, payload, checksum(payload))
            val envelope = com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope(
                schemaVersion = 1, scope = scope, revision = 9, liveCursor = "live", backfillCursor = "older", writtenAtMillis = 100,
            )
            val digest = normalizedRowDigest(listOf(row))
            dao.insertNormalizedHead(NormalizedTimelineSnapshotHeadEntity(
                "b", "c", "a", NORMALIZED_LAYOUT_VERSION, 9, 1, "live", "older", 0,
                1, normalizedRootDigest(envelope, digest), digest, 4, 100,
            ))
            dao.insertNormalizedRows(listOf(row))
            val source = RoomLegacyLedgerCopySource(legacy)
            source.snapshot(scope) {
                assertTrue(head().supported)
                val row = metadata(-1, 128).single()
                assertEquals(payload.size.toLong(), row.bytes)
                assertArrayEquals(payload.copyOfRange(65530, 65550), chunk(row, 65530, 20))
            }
            source.snapshot(scope.copy(agentId = "foreign")) { assertFalse(head().supported) }
            var result: LegacyLedgerCopyResult.Progress
            do {
                result = RoomLegacyLedgerCopy(source, target).step(scope) as LegacyLedgerCopyResult.Progress
            } while (!result.complete)
            assertEquals(9L, dao.getNormalizedHead("b", "c")!!.revision)
            source.snapshot(scope) { assertEquals(checksum(payload), metadata(-1, 1).single().checksum) }
            assertNull(target.ledger().head(ledgerScopeKey(scope)))
            val token = source.snapshot(scope) { head().token }
            assertTrue(source.validateRoot(scope, token))
            val flatHead = dao.getNormalizedHead("b", "c")!!
            val chained = incrementalNormalizedRowDigest(normalizedRowDigest(emptyList()), listOf(row))
            dao.upsertNormalizedHead(flatHead.copy(rowDigest = chained, rootDigest = normalizedRootDigest(envelope, chained)))
            val chainToken = source.snapshot(scope) { head().token }
            assertTrue(source.validateRoot(scope, chainToken))
            assertFalse(source.validateRoot(scope, token))
            dao.upsertNormalizedHead(flatHead)
            // Same row count and valid body checksum do not prove the ordered root.
            dao.upsertNormalizedRows(listOf(row.copy(checksum = "0".repeat(64))))
            assertFalse(source.validateRoot(scope, token))
            dao.upsertNormalizedRows(listOf(row))
            val original = dao.getNormalizedHead("b", "c")!!
            dao.upsertNormalizedHead(original.copy(rootDigest = "0".repeat(64)))
            try {
                source.validateRoot(scope, token)
                fail("Corrupt root accepted")
            } catch (_: IllegalStateException) { }
            dao.upsertNormalizedHead(original.copy(
                revision = 10, rootDigest = normalizedRootDigest(envelope.copy(revision = 10), digest),
            ))
            assertFalse(source.validateRoot(scope, token))
            assertEquals(LegacyLedgerCopyResult.SourceChanged, RoomLegacyLedgerCopy(source, target).step(scope))
        } finally { legacy.close(); target.close() }
    }

    @Test fun missingNormalizedHeadIsEmptySupportedSource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val legacy = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, TimelineLedgerDatabase::class.java).build()
        try {
            val scope = TimelineScope("b", "c", "a")
            val source = RoomLegacyLedgerCopySource(legacy)
            val token = source.snapshot(scope) {
                val head = head()
                assertTrue(head.supported)
                assertEquals(LegacyLedgerCopyKind.Empty, head.kind)
                assertEquals(0L, head.rowCount)
                assertTrue(metadata(-1, 1).isEmpty())
                head.token
            }
            assertEquals("empty-normalized", token)
            assertTrue(source.validateRoot(scope, token))
            var result: LegacyLedgerCopyResult.Progress
            do {
                result = RoomLegacyLedgerCopy(source, target).step(scope) as LegacyLedgerCopyResult.Progress
            } while (!result.complete)
            assertEquals(0, result.rows)
        } finally { legacy.close(); target.close() }
    }

    @Test fun manifestOnlyHistoryIsNotAnEmptySupportedSource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val legacy = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java).build()
        try {
            val scope = TimelineScope("b", "c", "a")
            val source = RoomLegacyLedgerCopySource(legacy)
            val emptyToken = source.snapshot(scope) { head().token }
            val dao = legacy.confirmedTimelineSnapshotDao()
            dao.replaceHead(ConfirmedTimelineSnapshotHeadEntity(
                "b", "c", "a", "manifest-only", null, 5, 100,
            ))
            dao.insertManifest(ConfirmedTimelineSnapshotManifestEntity(
                "manifest-only", "b", "c", "a", 5, 1, 0, 0, "0".repeat(64), 100,
            ))
            val manifest = source.snapshot(scope) { head() }
            assertEquals(LegacyLedgerCopyKind.ManifestOnly, manifest.kind)
            assertFalse(manifest.supported)
            assertEquals(0L, manifest.rowCount)
            assertNotEquals(emptyToken, manifest.token)
            assertFalse(source.validateRoot(scope, emptyToken))
            assertFalse(source.validateRoot(scope, manifest.token))
        } finally { legacy.close() }
    }

    @Test fun expandedManifestCopyServesOneRowAtATimeWithoutRecodingTheEnvelope() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val legacy = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java).build()
        try {
            val scope = TimelineScope("b", "c", "a")
            val envelope = com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope(
                scope = scope, revision = 3, events = listOf(
                    com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent(
                        position = 0.0, otid = "one", serverId = "s1", messageType = "USER",
                        dateIso = "2026-01-01T00:00:00Z", content = "first",
                    ),
                    com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent(
                        position = 1.0, otid = "two", serverId = "s2", messageType = "ASSISTANT",
                        dateIso = "2026-01-01T00:00:01Z", content = "second",
                    ),
                ),
            )
            assertTrue(RoomConfirmedTimelineStore(legacy).writeSnapshot(envelope))
            val cache = mutableMapOf<String, ManifestCopyCache>()
            val source = RoomLegacyLedgerCopySource(legacy, expandManifest = true, manifestCache = cache)
            val decodeBefore = com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec.envelopeDecodeCount
            val first = source.snapshot(scope) {
                val head = head()
                assertTrue(head.supported)
                assertEquals(LegacyLedgerCopyKind.ManifestOnly, head.kind)
                assertEquals(2L, head.rowCount)
                val row = metadata(-1, 1).single()
                assertEquals(0L, row.order)
                val bytes = chunk(row, 0, 65536)
                assertEquals(row.bytes, bytes.size.toLong())
                head.token
            }
            val afterFirst = com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec.envelopeDecodeCount
            assertEquals(1, afterFirst - decodeBefore)
            source.snapshot(scope) {
                assertEquals(first, head().token)
                assertEquals(1L, metadata(0, 1).single().order)
            }
            assertEquals(afterFirst, com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec.envelopeDecodeCount)
        } finally { legacy.close() }
    }
}
