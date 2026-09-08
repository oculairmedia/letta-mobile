package com.letta.mobile.data.local

import androidx.room.Room
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
class TimelineOwnedStorageHandoffTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = TimelineScope("b", "c", "a")

    @Test fun verifiedPrepareSurvivesAuthorityRestartAndSwitchRechecksSource() = runBlocking {
        fixture { legacy, target, authority, factory, lease, revision ->
            val prepared = factory.prepareAfterDrain(lease, revision)
            assertEquals(scope, prepared.scope)
            assertEquals(lease.epoch, prepared.epoch)
            assertEquals(revision, prepared.receipt!!.targetRevision)
            val restarted = TimelineOwnershipAuthority(temporary.root.toPath())
            assertEquals(prepared, restarted.state(scope))
            val dao = legacy.confirmedTimelineSnapshotDao()
            val head = dao.getNormalizedHead("b", "c")!!
            // Simulate a bypassing old binary. Switch must revalidate, never trust cached readiness.
            dao.upsertNormalizedHead(head.copy(revision = head.revision + 1))
            try { factory.switchPreparedAfterDrain(lease); fail("Stale source accepted") }
            catch (_: IllegalStateException) { }
            assertEquals(TimelineOwnershipAuthority.Phase.Prepared, authority.state(scope).phase)
            dao.upsertNormalizedHead(head)
            val canonical = TimelineOwnedStorageFactory(legacy, target, restarted).switchPreparedAfterDrain(lease)
            assertEquals(TimelineOwnershipAuthority.Route.Canonical, canonical.route)
            factory.canonical(canonical).read(scope) { assertEquals(revision, checkpoint().revision) }
        }
    }

    @Test fun noReceiptBeforeConversionAndContentionCannotPublishPrepared() = runBlocking {
        fixture { _, _, authority, factory, lease, revision ->
            try { factory.prepareAfterDrain(lease, revision + 1); fail("Wrong revision accepted") }
            catch (_: IllegalStateException) { }
            assertEquals(TimelineOwnershipAuthority.Phase.Migrating, authority.state(scope).phase)
            authority.withLease(lease) {
                try { factory.prepareAfterDrain(lease, revision); fail("Recursive lock accepted") }
                catch (_: IllegalStateException) { }
            }
            // Cancellation in validation cannot publish a prepared record.
            try { authority.prepare(lease) { throw CancellationException("validation interrupted") } }
            catch (_: CancellationException) { }
            assertEquals(TimelineOwnershipAuthority.Phase.Migrating, authority.state(scope).phase)
            factory.prepareAfterDrain(lease, revision)
        }
    }

    @Test fun corruptedCertificateBodyCannotAuthorizePrepare() = runBlocking {
        fixture { _, target, _, factory, lease, revision ->
            val scopeKey = ledgerScopeKey(scope)
            target.openHelper.writableDatabase.execSQL(
                "UPDATE ledger_validation SET payload = X'00' WHERE scope = ?",
                arrayOf(scopeKey),
            )
            try { factory.prepareAfterDrain(lease, revision); fail("Corrupt certificate accepted") }
            catch (_: IllegalStateException) { }
        }
    }

    @Test fun canonicalRevisionChangeInvalidatesCertificate() = runBlocking {
        fixture { _, target, _, factory, lease, revision ->
            RoomTimelineBoundedStore(target).transaction(scope) { nextRevision(); putEvidence("changed", byteArrayOf(1)) }
            try { factory.prepareAfterDrain(lease, revision); fail("Stale canonical revision accepted") }
            catch (_: IllegalStateException) { }
            try { factory.validationStep(lease); fail("Stale progress resumed") }
            catch (_: IllegalStateException) { }
        }
    }

    @Test fun mappedMigrationUsesRawSourceAndReopensOnlyCanonicalTarget() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val legacy = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, TimelineLedgerDatabase::class.java).build()
        val canonicalScope = scope.copy(backendId = "remote-letta:b")
        try {
            val envelope = StoredTimelineEnvelope(scope = scope, revision = 7, events = listOf(
                StoredTimelineEvent(position = 0.0, otid = "mapped-otid", serverId = "mapped-server",
                    messageType = "ASSISTANT", dateIso = "2026-01-01T00:00:00Z", content = "captured raw body"),
            ))
            RoomConfirmedTimelineStore(legacy).commitNormalized(
                NormalizedTimelineCommitPlanner.plan(null, envelope), envelope, true,
            )
            fun authority() = TimelineOwnershipAuthority(temporary.root.toPath())
            fun factory() = TimelineOwnedStorageFactory(legacy, target, authority())
            val source = authority().acquire(scope, TimelineOwnershipAuthority.Route.Legacy)
            val lease = factory().beginMappedMigrationAfterDrain(source, canonicalScope)
            assertEquals(lease, factory().beginMappedMigrationAfterDrain(source, canonicalScope))
            try { factory().legacy(source).commitNormalized(
                NormalizedTimelineCommitPlanner.plan(envelope, envelope.copy(revision = 8)), envelope.copy(revision = 8), true,
            ); fail("source writer accepted") } catch (_: IllegalStateException) { }
            do {
                val progress = factory().copyStep(lease) as LegacyLedgerCopyResult.Progress
                assertTrue(progress.rows <= 1 && progress.bytes <= 65536)
            } while (!progress.complete)
            do {
                val progress = factory().convertStep(lease) as RoomCanonicalMigrationResult.Progress
            } while (!progress.complete)
            var audit: TimelineOwnedStorageFactory.ValidationProgress
            do {
                audit = factory().validationStep(lease)
                assertTrue(audit.metadataRows <= 128 && audit.bodyBytes <= 65536)
            } while (!audit.complete)
            val revision = checkNotNull(audit.certifiedRevision)
            val mapping = authority().capturedMapping(lease)!!
            val boundToken = RoomLegacyLedgerCopySource(legacy, mapping).snapshot(canonicalScope) { head().token }
            for (changed in listOf(mapping.copy(sourceEpoch = mapping.sourceEpoch + 1),
                mapping.copy(targetEpoch = mapping.targetEpoch + 1))) {
                assertNotEquals(boundToken, RoomLegacyLedgerCopySource(legacy, changed).snapshot(canonicalScope) { head().token })
                try { RoomTimelineValidation(legacy, target, changed).receipt(lease, revision)
                    fail("certificate accepted different epoch") } catch (_: IllegalStateException) { }
            }
            factory().prepareAfterDrain(lease, revision)
            val dao = legacy.confirmedTimelineSnapshotDao()
            val head = dao.getNormalizedHead(scope.backendId, scope.conversationId)!!
            dao.upsertNormalizedHead(head.copy(revision = head.revision + 1))
            try { factory().switchPreparedAfterDrain(lease); fail("changed source accepted") }
            catch (_: IllegalStateException) { }
            dao.upsertNormalizedHead(head)
            val canonical = factory().switchPreparedAfterDrain(lease)
            assertEquals(canonical, factory().reopenCanonical(canonicalScope))
            factory().canonical(canonical).read(canonicalScope) {
                assertEquals(revision, checkpoint().revision)
                val row = metadata(com.letta.mobile.data.timeline.TimelineReadPosition.Tail, 1).rows.single()
                val event = TimelineSnapshotCodec.json.decodeFromString(StoredTimelineEvent.serializer(),
                    body(row.body, 0, 65536).decodeToString())
                assertEquals("captured raw body", event.content)
            }
            assertNull(target.ledger().head(ledgerScopeKey(scope)))
            assertEquals(7L, dao.getNormalizedHead(scope.backendId, scope.conversationId)!!.revision)
            assertNull(dao.getNormalizedHead(canonicalScope.backendId, canonicalScope.conversationId))
        } finally { target.close(); legacy.close() }
    }

    @Test fun missingMappedSourceCannotFenceOrCopyAnEmptyTarget() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val legacy = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, TimelineLedgerDatabase::class.java).build()
        try {
            val authority = TimelineOwnershipAuthority(temporary.root.toPath())
            val factory = TimelineOwnedStorageFactory(legacy, target, authority)
            val source = authority.acquire(scope, TimelineOwnershipAuthority.Route.Legacy)
            try { factory.beginMappedMigrationAfterDrain(source, scope.copy(backendId = "remote-letta:b"))
                fail("missing source accepted") } catch (_: IllegalStateException) { }
            authority.withLease(source) { }
            assertEquals(TimelineOwnershipAuthority.Phase.Legacy, authority.state(scope).phase)
        } finally { target.close(); legacy.close() }
    }

    private suspend fun fixture(block: suspend (LettaDatabase, TimelineLedgerDatabase, TimelineOwnershipAuthority,
        TimelineOwnedStorageFactory, TimelineOwnershipAuthority.Lease, Long) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val legacy = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, TimelineLedgerDatabase::class.java).build()
        try {
            val envelope = StoredTimelineEnvelope(scope = scope, revision = 1)
            val store = RoomConfirmedTimelineStore(legacy)
            store.commitNormalized(NormalizedTimelineCommitPlanner.plan(null, envelope), envelope, true)
            val authority = TimelineOwnershipAuthority(temporary.root.toPath())
            val factory = TimelineOwnedStorageFactory(legacy, target, authority)
            val lease = authority.beginMigration(authority.acquire(scope, TimelineOwnershipAuthority.Route.Legacy))
            try { factory.prepareAfterDrain(lease, 0); fail("Missing copy accepted") }
            catch (_: IllegalStateException) { }
            do {
                val progress = factory.copyStep(lease) as LegacyLedgerCopyResult.Progress
            } while (!progress.complete)
            do {
                val progress = factory.convertStep(lease) as RoomCanonicalMigrationResult.Progress
            } while (!progress.complete)
            val revision = RoomTimelineBoundedStore(target).read(scope) { checkpoint().revision }
            assertEquals(RoomCanonicalMigrationResult.LegacyFallback("activation_dormant"), factory.validateDormant(lease, revision))
            try { factory.prepareAfterDrain(lease, revision); fail("Unaudited conversion accepted") }
            catch (_: IllegalStateException) { }
            do {
                // Reconstruct the validator/factory every call to exercise durable progress, not RAM state.
                val audit = TimelineOwnedStorageFactory(legacy, target, authority).validationStep(lease)
                assertTrue(audit.metadataRows <= 128 && audit.bodyBytes <= 65536)
            } while (!audit.complete)
            block(legacy, target, authority, factory, lease, revision)
        } finally { target.close(); legacy.close() }
    }
}
