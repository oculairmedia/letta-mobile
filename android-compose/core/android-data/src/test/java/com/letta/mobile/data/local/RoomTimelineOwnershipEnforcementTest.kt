package com.letta.mobile.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.timeline.snapshot.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
class RoomTimelineOwnershipEnforcementTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = TimelineScope("backend", "conversation", "agent")
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected fence rejection") } catch (_: IllegalStateException) { }
    }

    @Test fun legacyWritesBootstrapAndMaintenanceCannotEnterAfterSwitch() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LettaDatabase::class.java).build()
        try {
            val authority = TimelineOwnershipAuthority(temporary.root.toPath())
            val legacy = authority.acquire(scope, TimelineOwnershipAuthority.Route.Legacy)
            val store = RoomConfirmedTimelineStore(db, ownership = authority, ownerLease = legacy)
            val envelope = StoredTimelineEnvelope(scope = scope, revision = 1)
            assertTrue(store.writeSnapshot(envelope))
            val migration = authority.beginMigration(legacy)
            rejected { store.readSnapshotResult(scope) }
            authority.prepare(migration) { TimelineOwnershipAuthority.Receipt("source", "generation", 1) }
            authority.commitSwitch(migration) { }
            rejected { store.writeSnapshot(envelope.copy(revision = 2)) }
            rejected { store.commitNormalized(NormalizedTimelineCommitPlanner.plan(null, envelope), envelope, true) }
            rejected { store.readSnapshotResult(scope) }
            rejected { store.deleteSnapshot(scope) }
            rejected { store.clearForBackend(scope.backendId) }
            rejected { store.prune(scope.backendId, 0) }
            assertNotNull(db.confirmedTimelineSnapshotDao().getHeadMetadata(scope.backendId, scope.conversationId))
            // Restarted default singleton stays epoch zero, rather than reacquiring Canonical/Legacy.
            rejected { RoomConfirmedTimelineStore(db, ownership = TimelineOwnershipAuthority(temporary.root.toPath())).readSnapshotResult(scope) }
        } finally { db.close() }
    }

    @Test fun finalCheckpointKeepsLeaseUntilItsPublicationCompletes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LettaDatabase::class.java).build()
        try {
            val authority = TimelineOwnershipAuthority(temporary.root.toPath())
            val legacy = authority.acquire(scope, TimelineOwnershipAuthority.Route.Legacy)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store = RoomConfirmedTimelineStore(db, ownership = authority, ownerLease = legacy,
                beforeHeadPublicationObserver = { entered.complete(Unit); release.await() })
            val envelope = StoredTimelineEnvelope(scope = scope, revision = 1)
            val write = async { store.commitNormalized(NormalizedTimelineCommitPlanner.plan(null, envelope), envelope, true) }
            entered.await()
            rejected { authority.beginMigration(legacy) }
            release.complete(Unit)
            write.await()
            // A reentrant authority acquisition by the checkpoint would fail and leave no manifest.
            assertNotNull(db.confirmedTimelineSnapshotDao().getHeadMetadata(scope.backendId, scope.conversationId))
            authority.beginMigration(legacy)
            rejected { store.writeSnapshot(envelope.copy(revision = 2)) }
        } finally { db.close() }
    }
}
