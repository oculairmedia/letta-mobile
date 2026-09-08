package com.letta.mobile.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.timeline.*
import com.letta.mobile.data.timeline.snapshot.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RoomLegacyCanonicalMigrationTest {
    private val scope = TimelineScope("backend", "conversation")

    @Test fun conversionResumesWithExactEvidenceAndNeverActivates() = runBlocking {
        fixture { db, source ->
            val original = source.payload.copyOf()
            copy(source, db)
            fun migration() = RoomLegacyCanonicalMigration(source, db, { _, token -> token == source.token })
            assertEquals(RoomCanonicalMigrationResult.Progress(1, false), migration().step(scope))
            val store = RoomTimelineBoundedStore(db)
            store.read(scope) {
                assertNotNull(locate(TimelineMessageId("server")))
                assertEquals("server", evidence("identity/otid/otid", 65536)!!.decodeToString())
                assertNotNull(evidence("terminal/server/server", 65536))
                val row = metadata(TimelineReadPosition.Tail, 1).rows.single()
                assertEquals("application/vnd.letta.timeline-event+json;version=1", row.contentType)
                val event = TimelineSnapshotCodec.json.decodeFromString(StoredTimelineEvent.serializer(), body(row.body, 0, 65536).decodeToString())
                assertEquals("exact terminal body", event.content)
            }
            assertEquals(RoomCanonicalMigrationResult.Progress(1, true), migration().step(scope))
            assertEquals(RoomCanonicalMigrationResult.Progress(1, true), migration().step(scope))
            assertEquals(RoomCanonicalMigrationResult.LegacyFallback("activation_dormant"), migration().validateForActivation(scope, 2))
            assertEquals(RoomCanonicalMigrationResult.LegacyFallback("canonical_revision_changed"), migration().validateForActivation(scope, 1))
            source.token = "changed"
            assertEquals(RoomCanonicalMigrationResult.LegacyFallback("source_changed"), migration().step(scope))
            assertEquals(RoomCanonicalMigrationResult.LegacyFallback("source_changed"), migration().validateForActivation(scope, 2))
            assertArrayEquals(original, source.payload)
            assertEquals(2L, store.read(scope) { checkpoint().revision })
        }
    }

    @Test fun malformedAndOversizedRowsFallbackWithoutPublishingOrAdvancing() = runBlocking {
        fixture { db, source ->
            source.payload = "not-json".encodeToByteArray()
            copy(source, db)
            val migration = RoomLegacyCanonicalMigration(source, db, { _, _ -> true })
            assertEquals(RoomCanonicalMigrationResult.LegacyFallback("integrity_or_storage_failure"), migration.step(scope))
            assertNull(db.ledger().head(ledgerScopeKey(scope)))
            assertTrue(db.ledger().tail(ledgerScopeKey(scope), 1).isEmpty())
            assertEquals(RoomCanonicalMigrationResult.Deferred(0, source.payload.size.toLong(), 2),
                RoomLegacyCanonicalMigration(source, db, { _, _ -> true }, maxEventBytes = 2).step(scope))
            assertNull(db.ledger().head(ledgerScopeKey(scope)))
        }
    }

    @Test fun conversionCompletionDoesNotRunWholeHistoryValidation() = runBlocking {
        fixture { db, source ->
            copy(source, db)
            val migration = RoomLegacyCanonicalMigration(source, db, { _, _ -> false })
            assertEquals(RoomCanonicalMigrationResult.Progress(1, false), migration.step(scope))
            assertEquals(RoomCanonicalMigrationResult.Progress(1, true), migration.step(scope))
            assertEquals(2L, RoomTimelineBoundedStore(db).read(scope) { checkpoint().revision })
            assertEquals(RoomCanonicalMigrationResult.LegacyFallback("integrity_or_storage_failure"), migration.validateForActivation(scope, 2))
            try {
                RoomLegacyCanonicalMigration(source, db, { _, _ -> throw CancellationException("cancel") }).validateForActivation(scope, 2)
                fail("Cancellation swallowed")
            } catch (_: CancellationException) { }
            assertEquals(2L, RoomTimelineBoundedStore(db).read(scope) { checkpoint().revision })
            assertEquals(RoomCanonicalMigrationResult.Progress(1, true), RoomLegacyCanonicalMigration(source, db, { _, _ -> true }).step(scope))
        }
    }

    @Test fun unrelatedWriterRevisionRejectsResumeAndReadiness() = runBlocking {
        fixture { db, source ->
            copy(source, db)
            val migration = RoomLegacyCanonicalMigration(source, db, { _, _ -> true })
            migration.step(scope)
            val store = RoomTimelineBoundedStore(db)
            store.transaction(scope) { nextRevision(); putEvidence("other", byteArrayOf(1)) }
            assertEquals(RoomCanonicalMigrationResult.LegacyFallback("canonical_revision_changed"), migration.step(scope))
            assertEquals(RoomCanonicalMigrationResult.LegacyFallback("conversion_incomplete"), migration.validateForActivation(scope, 2))
        }
    }

    @Test fun eightMiBRowIsDeferredBeforeAnyBodyReadOrDecode() = runBlocking {
        fixture { db, source ->
            source.payload = ByteArray(8 * 1024 * 1024) { 120 }
            copy(source, db)
            val state = db.ledger().migration(ledgerScopeKey(scope))!!
            val row = db.ledger().migrationRows(ledgerScopeKey(scope), state.generation, Long.MIN_VALUE, 1).single()
            // Remove the body: a regression that reads before checking the budget now fails integrity.
            db.openHelper.writableDatabase.execSQL("DELETE FROM ledger_chunk")
            repeat(2) {
                assertEquals(RoomCanonicalMigrationResult.Deferred(0, row.bytes, 2 * 1024 * 1024),
                    RoomLegacyCanonicalMigration(source, db, { _, _ -> true }).step(scope))
            }
            assertNull(db.ledger().head(ledgerScopeKey(scope)))
            assertTrue(db.ledger().tail(ledgerScopeKey(scope), 1).isEmpty())
            assertEquals(RoomCanonicalMigrationResult.LegacyFallback("conversion_missing"),
                RoomLegacyCanonicalMigration(source, db, { _, _ -> true }).validateForActivation(scope, 0))
            assertEquals(8 * 1024 * 1024, source.payload.size)
        }
    }

    @Test fun interruptionAtEveryCanonicalWriteRollsBackAllEvidenceAndProgress() = runBlocking {
        for (table in listOf("ledger_chunk", "ledger_blob", "ledger_evidence", "ledger_row", "ledger_head")) {
            fixture { db, source ->
                copy(source, db)
                db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER interrupt_write BEFORE INSERT ON $table BEGIN SELECT RAISE(ABORT, 'interrupted'); END")
                assertEquals("table=$table", RoomCanonicalMigrationResult.LegacyFallback("integrity_or_storage_failure"),
                    RoomLegacyCanonicalMigration(source, db, { _, _ -> true }).step(scope))
                assertNull(db.ledger().head(ledgerScopeKey(scope)))
                assertTrue(db.ledger().tail(ledgerScopeKey(scope), 1).isEmpty())
                assertNull(db.ledger().evidence(ledgerScopeKey(scope), ledgerKey("identity/otid/otid")))
                assertNull(db.ledger().evidence(ledgerScopeKey(scope), ledgerKey("terminal/server/server")))
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER interrupt_write")
                assertEquals(RoomCanonicalMigrationResult.Progress(1, false),
                    RoomLegacyCanonicalMigration(source, db, { _, _ -> true }).step(scope))
            }
        }
    }

    @Test fun failedCompletionRetainsLastGoodRevisionAndCanRetry() = runBlocking {
        fixture { db, source ->
            copy(source, db)
            val migration = RoomLegacyCanonicalMigration(source, db, { _, _ -> true })
            migration.step(scope)
            db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER interrupt_head BEFORE INSERT ON ledger_head BEGIN SELECT RAISE(ABORT, 'interrupted'); END")
            assertEquals(RoomCanonicalMigrationResult.LegacyFallback("integrity_or_storage_failure"), migration.step(scope))
            assertEquals(1L, RoomTimelineBoundedStore(db).read(scope) { checkpoint().revision })
            assertEquals(RoomCanonicalMigrationResult.LegacyFallback("conversion_incomplete"), migration.validateForActivation(scope, 1))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER interrupt_head")
            assertEquals(RoomCanonicalMigrationResult.Progress(1, true), migration.step(scope))
            db.openHelper.writableDatabase.execSQL("UPDATE ledger_chunk SET payload = X'00' WHERE pointer IN (SELECT pointer FROM ledger_evidence)")
            assertEquals(RoomCanonicalMigrationResult.LegacyFallback("integrity_or_storage_failure"), migration.validateForActivation(scope, 2))
            assertEquals("exact-source-root", source.token)
        }
    }

    @Test fun corruptedCanonicalBodyDoesNotPassDormantReadiness() = runBlocking {
        fixture { db, source ->
            copy(source, db)
            val migration = RoomLegacyCanonicalMigration(source, db, { _, _ -> true })
            migration.step(scope)
            migration.step(scope)
            db.openHelper.writableDatabase.execSQL("UPDATE ledger_chunk SET payload = X'00' WHERE pointer IN (SELECT pointer FROM ledger_row)")
            assertEquals(RoomCanonicalMigrationResult.LegacyFallback("integrity_or_storage_failure"), migration.validateForActivation(scope, 2))
            assertEquals(2L, RoomTimelineBoundedStore(db).read(scope) { checkpoint().revision })
            assertEquals("exact-source-root", source.token)
        }
    }

    @Test fun corruptSourceManifestCannotBeConvertedEvenWithIntactRawCopy() = runBlocking {
        fixture { db, source ->
            copy(source, db)
            source.payload = source.payload + byteArrayOf(32)
            assertEquals(RoomCanonicalMigrationResult.LegacyFallback("integrity_or_storage_failure"),
                RoomLegacyCanonicalMigration(source, db, { _, _ -> true }).step(scope))
            assertNull(db.ledger().head(ledgerScopeKey(scope)))
        }
    }

    @Test fun invalidLegacyDateNeverUsesWallClockForCanonicalOrdering() = runBlocking {
        fixture { db, source ->
            source.payload = source.payload.decodeToString().replace("2026-01-01T00:00:00Z", "not-a-date").encodeToByteArray()
            copy(source, db)
            assertEquals(RoomCanonicalMigrationResult.LegacyFallback("integrity_or_storage_failure"),
                RoomLegacyCanonicalMigration(source, db, { _, _ -> true }).step(scope))
            assertNull(db.ledger().head(ledgerScopeKey(scope)))
        }
    }

    private suspend fun copy(source: Source, db: TimelineLedgerDatabase) {
        do {
            val result = RoomLegacyLedgerCopy(source, db).step(scope) as LegacyLedgerCopyResult.Progress
        } while (!result.complete)
    }

    private suspend fun fixture(block: suspend (TimelineLedgerDatabase, Source) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TimelineLedgerDatabase::class.java).build()
        try { block(db, Source()) } finally { db.close() }
    }

    private class Source : LegacyLedgerCopySource {
        var token = "exact-source-root"
        var payload = TimelineSnapshotCodec.json.encodeToString(StoredTimelineEvent.serializer(), StoredTimelineEvent(
            position = 0.0, otid = "otid", serverId = "server", messageType = "ASSISTANT",
            dateIso = "2026-01-01T00:00:00Z", content = "exact terminal body",
        )).encodeToByteArray()
        override suspend fun <T> snapshot(scope: TimelineScope, block: suspend LegacyLedgerCopyReader.() -> T): T =
            object : LegacyLedgerCopyReader {
                override suspend fun head() = LegacyLedgerCopyHead(token, 1, true)
                override suspend fun metadata(afterOrder: Long, maxRows: Int) =
                    if (afterOrder < 0) listOf(LegacyLedgerCopyRow(0, 1, 2, payload.size.toLong(), checksum(payload))) else emptyList()
                override suspend fun chunk(row: LegacyLedgerCopyRow, offset: Long, maxBytes: Int) =
                    payload.copyOfRange(offset.toInt(), minOf(payload.size, offset.toInt() + maxBytes))
            }.block()
    }
}
