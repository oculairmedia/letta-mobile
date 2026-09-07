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
            dao.insertNormalizedHead(NormalizedTimelineSnapshotHeadEntity(
                "b", "c", "a", NORMALIZED_LAYOUT_VERSION, 9, 1, "live", "older", 0,
                1, "exact-root", "exact-row-digest", 4, 100,
            ))
            dao.insertNormalizedRows(listOf(NormalizedTimelineSnapshotRowEntity("b", "c", 12, 34, 0, payload, checksum(payload))))
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
            dao.upsertNormalizedHead(dao.getNormalizedHead("b", "c")!!.copy(revision = 10))
            assertEquals(LegacyLedgerCopyResult.SourceChanged, RoomLegacyLedgerCopy(source, target).step(scope))
        } finally { legacy.close(); target.close() }
    }
}
