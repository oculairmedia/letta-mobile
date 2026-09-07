package com.letta.mobile.data.local

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.timeline.*
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RoomLedgerCardinalityTest {
    private val codec = object : RoomTimelineCheckpointCodec {
        override fun encode(checkpoint: TimelineDurableCheckpoint) = checkpoint.revision.toString().encodeToByteArray()
        override fun decode(bytes: ByteArray) = TimelineDurableCheckpoint(bytes.decodeToString().toLong(), null, true)
    }

    @Test fun twentyEightThousandRowsUseIndexedBoundedMetadataAndStableSnapshot() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TimelineLedgerDatabase::class.java).build()
        try {
            val scope = TimelineScope("backend", "conversation")
            val key = ledgerScopeKey(scope)
            db.withTransaction {
                val sql = db.openHelper.writableDatabase.compileStatement("INSERT INTO ledger_row(scope, identity, position, pointer, bytes, contentType, revision) VALUES (?, ?, ?, ?, ?, ?, ?)")
                try {
                    repeat(28000) { index ->
                        sql.bindBlob(1, key)
                        sql.bindBlob(2, ledgerKey("id-$index"))
                        sql.bindLong(3, index.toLong())
                        sql.bindString(4, "not-loaded-$index")
                        sql.bindLong(5, 8L * 1024 * 1024)
                        sql.bindString(6, "opaque")
                        sql.bindLong(7, 1)
                        sql.executeInsert()
                    }
                } finally { sql.close() }
                db.ledger().head(LedgerHead(key, 1, codec.encode(TimelineDurableCheckpoint(1, null, true))))
            }
            val store = RoomTimelineBoundedStore(db, codec)
            store.read(scope) {
                val page = metadata(TimelineReadPosition.Tail, 128)
                assertEquals(128, page.rows.size)
                assertEquals(27872L, page.rows.first().key.order)
                assertEquals(27999L, page.rows.last().key.order)
                assertNull(page.newer)
                assertNotNull(page.older)
                val centered = metadata(TimelineReadPosition.Around(TimelinePageKey(14000, TimelineMessageId("id-14000"))), 64)
                assertEquals(64, centered.rows.size)
                assertTrue(centered.rows.any { it.key.order == 14000L })
            }
            db.withTransaction {
                db.openHelper.readableDatabase.query(androidx.sqlite.db.SimpleSQLiteQuery(
                    "EXPLAIN QUERY PLAN SELECT * FROM ledger_row WHERE scope = ? AND (position < ? OR (position = ? AND identity < ?)) ORDER BY position DESC, identity DESC LIMIT 128",
                    arrayOf<Any?>(key, 27000L, 27000L, ledgerKey("id-27000")),
                )).use { cursor ->
                    val details = buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString()
                    assertTrue(details, details.contains("USING INDEX index_ledger_row_scope_position_identity"))
                    assertFalse(details, details.contains("TEMP B-TREE"))
                }
            }
            val readStarted = CompletableDeferred<Unit>()
            val writerStarted = CompletableDeferred<Unit>()
            val read = async {
                store.read(scope) {
                    val old = checkpoint()
                    readStarted.complete(Unit)
                    writerStarted.await()
                    assertEquals(old, checkpoint())
                    assertNull(locate(TimelineMessageId("new")))
                }
            }
            val write = async {
                readStarted.await()
                writerStarted.complete(Unit)
                store.transaction(scope) {
                    put(TimelineStoredRecord(TimelinePageKey(28000, TimelineMessageId("new")), "test", byteArrayOf(1)))
                    nextRevision()
                }
            }
            read.await(); write.await()
            store.read(scope) { assertEquals(2L, checkpoint().revision); assertNotNull(locate(TimelineMessageId("new"))) }
        } finally { db.close() }
    }
}
