package com.letta.mobile.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BackendConversationCursorStoreTest {
    @Test fun capturedNamespacesReplacementAndClosedCallbacksAreIsolated() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LettaDatabase::class.java).build()
        try {
            db.conversationCursorDao().upsertCursor("same", 999, 1)
            val factory = BackendConversationCursorFactory(db)
            val old = factory.capture("backend-a")
            val next = factory.capture("backend-b")
            assertNull(old.getCursor("same"))
            assertNull(next.getCursor("same"))
            old.recordFrame("same", 100)
            next.recordFrame("same", 20)
            old.recordFrame("only-a", 5)
            assertEquals(mapOf("same" to 20L), next.getAllCursors())
            assertEquals(mapOf("same" to 100L, "only-a" to 5L), old.getAllCursors())
            assertTrue(old.replaceExpiredWatermark("same", 100, 3))
            assertEquals(3L, old.getCursor("same"))
            old.recordFrame("same", 4)
            assertFalse(old.replaceExpiredWatermark("same", 3, 1))
            assertFalse(old.clearExpiredWatermark("same", 3))
            assertEquals(4L, old.getCursor("same"))
            assertEquals(20L, next.getCursor("same"))
            assertFalse(old.replaceExpiredWatermark("absent", 0, 1))
            old.close()
            try { old.recordFrame("same", 200); fail("Closed graph accepted callback") }
            catch (_: IllegalStateException) { }
            try { old.replaceExpiredWatermark("same", 4, 0); fail("Closed graph accepted repair") }
            catch (_: IllegalStateException) { }
            assertEquals(20L, next.getCursor("same"))
            assertEquals(999L, db.conversationCursorDao().getCursor("same")!!.highestSeenSeq)
            assertEquals(4L, factory.capture("backend-a").getCursor("same"))
            assertTrue(next.clearExpiredWatermark("same", 20))
            assertNull(next.getCursor("same"))
        } finally { db.close() }
    }

    @Test fun delayedRepairCannotRecreateDeletedCursorOrOverwriteNewFrames() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LettaDatabase::class.java).build()
        try {
            val cursors = BackendConversationCursorFactory(db).capture("backend-a")
            cursors.recordFrame("conversation", 100)
            assertTrue(cursors.clearExpiredWatermark("conversation", 100))
            // Expiry deletion consumes the CAS precondition; repair must not invent ownership.
            assertFalse(cursors.replaceExpiredWatermark("conversation", 100, 7))
            assertNull(cursors.getCursor("conversation"))
            cursors.recordFrame("conversation", 8)
            assertFalse(cursors.clearExpiredWatermark("conversation", 100))
            assertFalse(cursors.replaceExpiredWatermark("conversation", 100, 7))
            assertEquals(8L, cursors.getCursor("conversation"))
            cursors.close()
        } finally {
            db.close()
        }
    }

    @Test fun retainedExpiryAllowsCommitSpanningReplacementUntilNewerFrameWins() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LettaDatabase::class.java).build()
        try {
            val cursors = BackendConversationCursorFactory(db).capture("backend-a")
            cursors.recordFrame("conversation", 100)
            // Captured expiry retains the expected row across reconcile.
            assertEquals(100L, cursors.getCursor("conversation"))
            assertTrue(cursors.replaceExpiredWatermark("conversation", 100, 7))
            assertEquals(7L, cursors.getCursor("conversation"))
            cursors.recordFrame("conversation", 100)
            cursors.recordFrame("conversation", 101)
            // A live frame that advanced past the expired expectation must win the race.
            assertFalse(cursors.replaceExpiredWatermark("conversation", 100, 9))
            assertEquals(101L, cursors.getCursor("conversation"))
            cursors.close()
        } finally {
            db.close()
        }
    }

    @Test fun additiveMigrationPreservesAmbiguousGlobalRowsWithoutImportingThem() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).callback(object : SupportSQLiteOpenHelper.Callback(13) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE conversation_cursors (conv_id TEXT NOT NULL PRIMARY KEY, highest_seen_seq INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                    db.execSQL("INSERT INTO conversation_cursors VALUES ('same', 999, 123)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build(),
        )
        try {
            val db = helper.writableDatabase
            LettaDatabaseMigrations.MIGRATION_13_14.migrate(db)
            db.query("SELECT highest_seen_seq, updated_at FROM conversation_cursors WHERE conv_id = 'same'").use {
                assertTrue(it.moveToFirst()); assertEquals(999L, it.getLong(0)); assertEquals(123L, it.getLong(1))
            }
            db.query("SELECT count(*) FROM backend_conversation_cursors").use {
                assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0))
            }
        } finally { helper.close() }
    }
}
