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
}
