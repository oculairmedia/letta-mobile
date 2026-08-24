package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class RoomConfirmedTimelineStoreTest {
    private var database: LettaDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        database = null
    }

    private fun inMemoryDatabase(): LettaDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { database = it }
    }

    @Test
    fun writeAndReadSnapshotPreservesData() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope(backendId = "b1", conversationId = "c1", agentId = "a1")

        val envelope = StoredTimelineEnvelope(
            schemaVersion = 1,
            scope = scope,
            revision = 10L,
            liveCursor = "srv-10",
            backfillCursor = "srv-1",
            releasedOlderCount = 2,
            events = listOf(
                StoredTimelineEvent(
                    position = 1.0,
                    otid = "otid-1",
                    content = "Hello from Android persistence",
                    serverId = "srv-1",
                    messageType = "USER",
                    dateIso = "2026-08-24T00:00:00Z",
                )
            ),
            writtenAtMillis = 1000L,
        )

        val written = store.writeSnapshot(envelope)
        assertTrue(written)

        val read = store.readSnapshot(scope)
        assertNotNull(read)
        assertEquals(10L, read?.revision)
        assertEquals("srv-10", read?.liveCursor)
        assertEquals(1, read?.events?.size)
        assertEquals("Hello from Android persistence", read?.events?.first()?.content)
    }

    @Test
    fun staleRevisionWritesAreRejected() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope(backendId = "b1", conversationId = "c1")

        assertTrue(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 5L)))
        assertFalse(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 5L)))
        assertFalse(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 4L)))
        assertTrue(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 6L)))

        assertEquals(6L, store.readSnapshot(scope)?.revision)
    }

    @Test
    fun backendIsolationAndPrune() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)

        val scopeA = TimelineScope(backendId = "backend-A", conversationId = "c1")
        val scopeB = TimelineScope(backendId = "backend-B", conversationId = "c1")

        store.writeSnapshot(StoredTimelineEnvelope(scope = scopeA, revision = 1L, writtenAtMillis = 100L))
        store.writeSnapshot(StoredTimelineEnvelope(scope = scopeB, revision = 1L, writtenAtMillis = 100L))

        assertNotNull(store.readSnapshot(scopeA))
        assertNotNull(store.readSnapshot(scopeB))

        store.clearForBackend("backend-A")
        assertNull(store.readSnapshot(scopeA))
        assertNotNull(store.readSnapshot(scopeB))
    }

    @Test
    fun corruptPayloadRecoversGracefully() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConfirmedTimelineStore(db)
        val scope = TimelineScope(backendId = "b1", conversationId = "c1")

        // Manually insert a corrupt row
        db.confirmedTimelineSnapshotDao().insertOrReplace(
            ConfirmedTimelineSnapshotEntity(
                backendId = "b1",
                conversationId = "c1",
                agentId = null,
                revision = 1L,
                schemaVersion = 1,
                payloadJson = "{corrupted json invalid syntax",
                writtenAtMillis = 1000L,
            )
        )

        val read = store.readSnapshot(scope)
        assertNull(read)
    }
}
