package com.letta.mobile.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.timeline.*
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RoomTimelineToolIndexTest {
    private val scope = TimelineScope("backend", "conversation", "agent")

    @Test fun versionOneMigrationIsAdditiveAndRetainsDurableRows() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE ledger_head (scope BLOB NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, checkpoint BLOB NOT NULL)")
                        db.execSQL("CREATE TABLE ledger_blob (scope BLOB NOT NULL, pointer TEXT NOT NULL, bytes INTEGER NOT NULL, checksum TEXT NOT NULL, PRIMARY KEY(scope,pointer))")
                        db.execSQL("INSERT INTO ledger_head VALUES (X'0102', 42, X'0304')")
                        db.execSQL("INSERT INTO ledger_blob VALUES (X'0102', 'preserved', 8192, 'exact')")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            TimelineLedgerDatabase.MIGRATION_1_2.migrate(db)
            db.query("SELECT revision, hex(checkpoint) FROM ledger_head").use {
                assertTrue(it.moveToFirst()); assertEquals(42L, it.getLong(0)); assertEquals("0304", it.getString(1))
            }
            db.query("SELECT bytes, checksum FROM ledger_blob WHERE pointer = 'preserved'").use {
                assertTrue(it.moveToFirst()); assertEquals(8192L, it.getLong(0)); assertEquals("exact", it.getString(1))
            }
            db.query("SELECT count(*) FROM ledger_tool_call").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            db.query("SELECT count(*) FROM ledger_tool_sweep").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        } finally { helper.close() }
    }

    @Test fun restartRollbackAndReturnBeforeOwnerRemainExact() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "tool-index-${java.util.UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, TimelineLedgerDatabase::class.java, name)
            .addMigrations(TimelineLedgerDatabase.MIGRATION_1_2).build()
        var db = open()
        try {
            var store = RoomTimelineBoundedStore(db)
            store.transaction(scope) {
                putToolCall(TimelineToolIndexEntry("returned-first", null, true))
                putToolCall(TimelineToolIndexEntry("pending", TimelineMessageId("owner"), false))
                setToolSweepGeneration(3)
                nextRevision()
            }
            try {
                store.transaction(scope) {
                    putToolCall(TimelineToolIndexEntry("pending", TimelineMessageId("owner"), true))
                    setToolSweepGeneration(4)
                    nextRevision()
                    throw CancellationException("rollback")
                }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            db.close()
            db = open()
            store = RoomTimelineBoundedStore(db)
            store.transaction(scope) {
                val prior = toolCall("returned-first")!!
                // Shared writer preserves returned evidence when an owner arrives out of order.
                putToolCall(prior.copy(owner = TimelineMessageId("late-owner")))
                nextRevision()
            }
            store.read(scope) {
                assertEquals(3L, toolSweepGeneration())
                assertEquals(listOf("pending"), unresolvedTools(null, 128).map { it.callId })
                assertTrue(toolCall("returned-first")!!.returned)
                assertEquals(TimelineMessageId("late-owner"), toolCall("returned-first")!!.owner)
            }
            store.read(scope.copy(agentId = "other")) {
                assertNull(toolCall("pending"))
                assertEquals(0L, toolSweepGeneration())
            }
            try {
                store.transaction(scope) { setToolSweepGeneration(3); nextRevision() }
                fail("Generation must increase")
            } catch (_: IllegalStateException) { }
            store.transaction(scope) { setToolSweepGeneration(Long.MAX_VALUE); nextRevision() }
            try {
                store.transaction(scope) { setToolSweepGeneration(Long.MIN_VALUE); nextRevision() }
                fail("Generation must not wrap")
            } catch (_: IllegalStateException) { }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun twentyEightThousandEntriesEnumerateOnlyUnresolvedWithSqlCapsAndUtf16Ordering() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TimelineLedgerDatabase::class.java).build()
        try {
            val store = RoomTimelineBoundedStore(db)
            val expected = mutableListOf<String>()
            store.transaction(scope) {
                repeat(28000) { i ->
                    val id = "call-$i"
                    val owner = if (i % 3 == 0) null else TimelineMessageId("owner-$i")
                    val returned = i % 5 == 0
                    putToolCall(TimelineToolIndexEntry(id, owner, returned))
                    if (owner != null && !returned) expected.add(id)
                }
                for (id in listOf("\uD800", "\uE000", "a\u0000b")) {
                    putToolCall(TimelineToolIndexEntry(id, TimelineMessageId("owner"), false))
                    expected.add(id)
                }
                nextRevision()
            }
            assertEquals(128, db.ledger().unresolvedTools(ledgerScopeKey(scope), Int.MAX_VALUE).size)
            assertTrue(db.ledger().unresolvedTools(ledgerScopeKey(scope), -1).isEmpty())
            val actual = mutableListOf<String>()
            store.read(scope) {
                var after: String? = null
                while (true) {
                    val page = unresolvedTools(after, 37)
                    assertTrue(page.size <= 37)
                    if (page.isEmpty()) break
                    assertTrue(page.all { it.owner != null && !it.returned && (after == null || it.callId > after!!) })
                    actual.addAll(page.map { it.callId })
                    after = page.last().callId
                }
            }
            assertEquals(expected.sorted(), actual)
            // Fail if the filtered keyset lookup loses its scoped unresolved index range.
            db.openHelper.readableDatabase.query(androidx.sqlite.db.SimpleSQLiteQuery(
                "EXPLAIN QUERY PLAN SELECT * FROM ledger_tool_call WHERE scope = ? AND unresolved = 1 AND callId > ? AND owner IS NOT NULL AND returned = 0 ORDER BY callId LIMIT 37",
                arrayOf(ledgerScopeKey(scope), ledgerKey("call-100")),
            )).use { cursor ->
                val plans = mutableListOf<String>()
                while (cursor.moveToNext()) plans.add(cursor.getString(3))
                assertTrue(plans.any { it.contains("index_ledger_tool_call_scope_unresolved_callId") && it.contains("callId>?") })
                assertFalse(plans.any { it.contains("TEMP B-TREE") })
            }
        } finally { db.close() }
    }
}
