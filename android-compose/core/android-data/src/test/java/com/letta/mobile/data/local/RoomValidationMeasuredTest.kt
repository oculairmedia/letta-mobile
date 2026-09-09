package com.letta.mobile.data.local

import android.database.Cursor
import android.database.CursorWrapper
import android.os.CancellationSignal
import androidx.room.Room
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.timeline.snapshot.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RoomValidationMeasuredTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = TimelineScope("b", "c", "a")

    /** Counts actual cursor traversal and materialized body/checkpoint columns, not API estimates. */
    private class Meter : SupportSQLiteOpenHelper.Factory {
        private val lock = Any()
        private var rows = 0
        private var bytes = 0
        private var cancelBody = false
        private val sql = mutableListOf<String>()
        data class Snapshot(val rows: Int, val bytes: Int, val sql: List<String>)
        fun snapshot(): Snapshot = synchronized(lock) { Snapshot(rows, bytes, sql.toList()) }
        fun reset() = synchronized(lock) { rows = 0; bytes = 0; sql.clear() }
        fun cancelNextBody() = synchronized(lock) { cancelBody = true }
        private fun moved(success: Boolean) { if (success) synchronized(lock) { rows++ } }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
            fun wrap(db: SupportSQLiteDatabase): SupportSQLiteDatabase = object : SupportSQLiteDatabase by db {
                fun counted(query: String, cursor: Cursor): Cursor {
                    synchronized(lock) { sql.add(query) }
                    return object : CursorWrapper(cursor) {
                        override fun moveToNext(): Boolean = super.moveToNext().also(::moved)
                        override fun moveToFirst(): Boolean = super.moveToFirst().also(::moved)
                        override fun getBlob(columnIndex: Int): ByteArray {
                            val result = super.getBlob(columnIndex)
                            val name = getColumnName(columnIndex).lowercase()
                            val cancel = synchronized(lock) {
                                if (name == "payload" || name == "checkpoint" || name.startsWith("substr(payload")) bytes += result.size
                                (cancelBody && query.contains("substr(payload") && query.contains("ledger_chunk")).also {
                                    if (it) cancelBody = false
                                }
                            }
                            if (cancel) throw CancellationException("cancel actual body read")
                            return result
                        }
                    }
                }
                override fun query(query: SupportSQLiteQuery): Cursor = counted(query.sql, db.query(query))
                override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
                    counted(query.sql, db.query(query, cancellationSignal))
                override fun query(query: String): Cursor = counted(query, db.query(query))
                override fun query(query: String, bindArgs: Array<out Any?>): Cursor = counted(query, db.query(query, bindArgs))
            }
            return object : SupportSQLiteOpenHelper by helper {
                override val writableDatabase: SupportSQLiteDatabase get() = wrap(helper.writableDatabase)
                override val readableDatabase: SupportSQLiteDatabase get() = wrap(helper.readableDatabase)
            }
        }
    }

    @Test fun twentyEightThousandRowsAreMeasuredAndCheckpointStorageDoesNotGrow() = runBlocking {
        // The handoff cost must not scale with history: 28k rows still settle inside the bound.
        val measured = audit(28000, false)
        assertTrue("handoff read ${measured.rows} rows", measured.rows <= 128)
        assertTrue("handoff read ${measured.bytes} bytes", measured.bytes <= 65536)
    }

    @Test fun chainedRootMultiChunkRestartCancellationAndPointOnlyHandoff() = runBlocking {
        val measured = audit(2, true)
        assertTrue("handoff contains range queries: ${measured.sql}", measured.sql.none { it.contains("ORDER BY") })
    }

    private suspend fun audit(count: Int, chained: Boolean): Meter.Snapshot {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val meter = Meter()
        // Separate FIFO query executors permit nested legacy -> target transactions. Barriers
        // drain queued invalidation reads after the awaited operation; counting is synchronous.
        val legacyQueries = java.util.concurrent.Executors.newSingleThreadExecutor()
        val targetQueries = java.util.concurrent.Executors.newSingleThreadExecutor()
        suspend fun drainQueries() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            legacyQueries.submit { }.get(30, java.util.concurrent.TimeUnit.SECONDS)
            targetQueries.submit { }.get(30, java.util.concurrent.TimeUnit.SECONDS)
        }
        val name = "validation-${java.util.UUID.randomUUID()}.db"
        val legacy = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java).setQueryExecutor(legacyQueries).openHelperFactory(meter).build()
        fun open() = Room.databaseBuilder(context, TimelineLedgerDatabase::class.java, name)
            .addMigrations(TimelineLedgerDatabase.MIGRATION_1_2, TimelineLedgerDatabase.MIGRATION_2_3)
            .setQueryExecutor(targetQueries).openHelperFactory(meter).build()
        var target = open()
        try {
            val envelope = StoredTimelineEnvelope(scope = scope, revision = 1)
            // Seed real SQL manifests directly so 28k maintenance reads, not conversion CPU, dominate.
            val rows = (0 until count).map { i ->
                val bytes = byteArrayOf(i.toByte())
                NormalizedTimelineSnapshotRowEntity("b", "c", i.toLong() + 1, 0, i, bytes, checksum(bytes))
            }
            val digest = if (chained) rows.fold(normalizedRowDigest(emptyList())) { previous, row ->
                incrementalNormalizedRowDigest(previous, listOf(row))
            } else normalizedRowDigest(rows)
            legacy.confirmedTimelineSnapshotDao().insertNormalizedHead(NormalizedTimelineSnapshotHeadEntity(
                "b", "c", "a", NORMALIZED_LAYOUT_VERSION, 1, envelope.schemaVersion, null, null, 0,
                count, normalizedRootDigest(envelope, digest), digest, 1, envelope.writtenAtMillis,
            ))
            rows.chunked(128).forEach { legacy.confirmedTimelineSnapshotDao().insertNormalizedRows(it) }
            val sourceToken = RoomLegacyLedgerCopySource(legacy).snapshot(scope) { head().token }
            val scopeKey = ledgerScopeKey(scope)
            val generation = "fixture-generation"
            target.ledger().migration(LedgerMigrationState(scopeKey, sourceToken, generation, (count - 1).toLong(), count.toLong(), true))
            val body = ByteArray(65536 * 3 + 11) { (it % 251).toByte() }
            RoomTimelineBoundedStore(target).transaction(scope) {
                val revision = nextRevision()
                putEvidence("migration/legacy-canonical/v1", "$generation:${count - 1}:$count:true:$revision".encodeToByteArray())
                putEvidence("large-audit-body", body)
            }
            val authority = TimelineOwnershipAuthority(temporary.root.toPath())
            val lease = authority.beginMigration(authority.acquire(scope, TimelineOwnershipAuthority.Route.Legacy))
            fun factory() = TimelineOwnedStorageFactory(legacy, target, authority)
            fun blobCount(): Long = target.openHelper.readableDatabase.query("SELECT count(*) FROM ledger_blob").use { it.moveToFirst(); it.getLong(0) }
            val before = blobCount()
            var restarted = false
            var cancelled = false
            var steps = 0
            while (true) {
                drainQueries()
                meter.reset()
                val result = factory().validationStep(lease)
                drainQueries()
                val measured = meter.snapshot()
                if (result.complete) assertEquals(1L, result.certifiedRevision)
                else assertNull(result.certifiedRevision)
                assertTrue("metadata rows ${measured.rows}", measured.rows <= 128)
                assertTrue("body bytes ${measured.bytes}", measured.bytes <= 65536)
                assertTrue(measured.sql.none { it.contains("ORDER BY") && !it.contains("LIMIT") })
                val bodyRead = measured.sql.any { it.contains("substr(payload") && it.contains("ledger_chunk") }
                steps++
                if (bodyRead && !restarted) {
                    val checkpoint = target.ledger().validation(scopeKey)!!
                    target.close()
                    target = open()
                    assertArrayEquals(checkpoint.payload, target.ledger().validation(scopeKey)!!.payload)
                    restarted = true
                    meter.cancelNextBody()
                    try { factory().validationStep(lease); fail("Expected body cancellation") }
                    catch (_: CancellationException) { cancelled = true }
                    assertArrayEquals(checkpoint.payload, target.ledger().validation(scopeKey)!!.payload)
                }
                if (result.complete) break
                check(steps < count + 100)
            }
            assertTrue(restarted && cancelled)
            assertEquals(before, blobCount())
            target.openHelper.readableDatabase.query("SELECT count(*) FROM ledger_validation").use {
                assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0))
            }
            target.close()
            target = open()
            val completed = factory().validationStep(lease)
            assertTrue(completed.complete)
            assertEquals(1L, completed.certifiedRevision)
            try {
                factory().validationStep(lease.copy(epoch = lease.epoch + 1))
                fail("Stale migration epoch supplied a certified revision")
            } catch (_: IllegalStateException) { }
            drainQueries()
            meter.reset()
            factory().prepareAfterDrain(lease, checkNotNull(completed.certifiedRevision))
            factory().switchPreparedAfterDrain(lease)
            drainQueries()
            val measured = meter.snapshot()
            assertTrue("handoff contains range queries: ${measured.sql}", measured.sql.none { it.contains("ORDER BY") })
            assertTrue(measured.rows <= 128 && measured.bytes <= 65536)
            return measured
        } finally {
            target.close(); legacy.close()
            legacyQueries.shutdown(); targetQueries.shutdown()
            context.deleteDatabase(name)
        }
    }
}
