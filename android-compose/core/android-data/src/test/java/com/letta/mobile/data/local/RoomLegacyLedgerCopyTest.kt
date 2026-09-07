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
class RoomLegacyLedgerCopyTest {
    @Test fun resumableDigestMatchesJdkAcrossPaddingAndChunkBoundaries() {
        for (size in listOf(0, 1, 55, 56, 63, 64, 65, 65535, 65536, 65537, 2097171)) {
            val bytes = ByteArray(size) { (it % 251).toByte() }
            var digest = ResumableLedgerSha256.restore("")
            var at = 0
            while (at + 65536 <= size) {
                digest.update(bytes.copyOfRange(at, at + 65536))
                digest = ResumableLedgerSha256.restore(digest.checkpoint())
                at += 65536
            }
            digest.update(bytes.copyOfRange(at, size))
            assertEquals("size=$size", checksum(bytes), digest.finish())
        }
    }

    @Test fun restartEveryChunkPreservesExactBodiesAndFencesSourceMutation() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TimelineLedgerDatabase::class.java).build()
        try {
            val scope = TimelineScope("backend", "conversation")
            val bytes = ByteArray(2 * 1024 * 1024 + 7) { (it % 251).toByte() }
            var token = "generation1:revision2:exact-root"
            var supported = true
            val source = object : LegacyLedgerCopySource {
                override suspend fun <T> snapshot(scope: TimelineScope, block: suspend LegacyLedgerCopyReader.() -> T): T =
                    object : LegacyLedgerCopyReader {
                        override suspend fun head() = LegacyLedgerCopyHead(token, 1, supported)
                        override suspend fun metadata(afterOrder: Long, maxRows: Int): List<LegacyLedgerCopyRow> {
                            assertEquals(1, maxRows)
                            return if (afterOrder < 0) listOf(LegacyLedgerCopyRow(0, 12, 34, bytes.size.toLong(), checksum(bytes))) else emptyList()
                        }
                        override suspend fun chunk(row: LegacyLedgerCopyRow, offset: Long, maxBytes: Int): ByteArray {
                            assertTrue(maxBytes <= 65536)
                            return bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + maxBytes))
                        }
                    }.block()
            }
            var copy = RoomLegacyLedgerCopy(source, db)
            assertEquals(LegacyLedgerCopyResult.Progress(0, 65536, false), copy.step(scope))
            val old = db.ledger().migration(ledgerScopeKey(scope))!!
            token = "generation2:revision3:other-root"
            assertEquals(LegacyLedgerCopyResult.SourceChanged, copy.step(scope))
            assertEquals(old.pendingOffset, db.ledger().migration(ledgerScopeKey(scope))!!.pendingOffset)
            supported = false
            assertEquals(LegacyLedgerCopyResult.SchemaMismatch, copy.step(scope))
            supported = true
            token = old.sourceToken
            var total = 65536
            do {
                copy = RoomLegacyLedgerCopy(source, db)
                val result = copy.step(scope) as LegacyLedgerCopyResult.Progress
                assertTrue(result.bytes <= 65536 && result.rows <= 1)
                total += result.bytes
            } while (!result.complete)
            assertEquals(bytes.size, total)
            val state = db.ledger().migration(ledgerScopeKey(scope))!!
            assertTrue(state.complete)
            val row = db.ledger().migrationRows(ledgerScopeKey(scope), state.generation, Long.MIN_VALUE, 128).single()
            assertEquals(checksum(bytes), row.checksum)
            assertEquals(bytes.size.toLong(), row.bytes)
            assertNull(db.ledger().head(ledgerScopeKey(scope)))
            assertTrue(db.ledger().tail(ledgerScopeKey(scope), 128).isEmpty())
        } finally { db.close() }
    }
}
