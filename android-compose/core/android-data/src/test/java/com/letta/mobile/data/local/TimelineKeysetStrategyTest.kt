package com.letta.mobile.data.local

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Android SQL prerequisite probe, not a generated Room PagingSource benchmark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TimelineKeysetStrategyTest {
    @Test
    fun `metadata seek remains stable when live insertion shifts offsets`() {
        for (size in listOf(2_106, 28_000)) {
            SQLiteDatabase.create(null).use { db ->
                seed(db, size)
                val before = page(db, SEEK, arrayOf("s", "128", "00000128"))
                assertEquals(64, before.size)
                assertEquals(before, page(db, OFFSET, arrayOf("s", (size - 128).toString())))
                db.execSQL("INSERT INTO metadata VALUES ('s', ?, 'new', 'body')", arrayOf(size))
                assertEquals(before, page(db, SEEK, arrayOf("s", "128", "00000128")))
                assertNotEquals(before, page(db, OFFSET, arrayOf("s", (size - 128).toString())))
                db.rawQuery("EXPLAIN QUERY PLAN $SEEK", arrayOf("s", "128", "00000128")).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getString(3).contains("USING COVERING INDEX seek"))
                }
            }
        }
    }

    @Test
    fun `metadata projection excludes heavy bodies and body slices obey byte limit`() {
        SQLiteDatabase.create(null).use { db ->
            seed(db, 128)
            db.execSQL("CREATE TABLE bodies(body_key TEXT PRIMARY KEY, payload BLOB NOT NULL)")
            for (mib in listOf(1, 2, 4, 8)) {
                val bytes = mib * 1024 * 1024
                db.execSQL("INSERT OR REPLACE INTO bodies VALUES ('body', zeroblob(?))", arrayOf(bytes))
                assertEquals(64, page(db, SEEK, arrayOf("s", "128", "00000128")).size)
                var offset = 0
                while (offset < bytes) {
                    db.rawQuery(
                        "SELECT substr(payload, ?, 65536) FROM bodies WHERE body_key=?",
                        arrayOf((offset + 1).toString(), "body"),
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        val chunk = cursor.getBlob(0)
                        assertEquals(minOf(65_536, bytes - offset), chunk.size)
                        offset += chunk.size
                        assertTrue(!cursor.moveToNext())
                    }
                }
                assertEquals(bytes, offset)
                db.rawQuery("SELECT length(payload) FROM bodies WHERE body_key='body'", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(bytes, cursor.getInt(0))
                }
            }
        }
    }

    private fun seed(db: SQLiteDatabase, size: Int) {
        db.execSQL("CREATE TABLE metadata(scope TEXT, order_key INTEGER, event_id TEXT, body_key TEXT)")
        db.execSQL("CREATE INDEX seek ON metadata(scope, order_key DESC, event_id DESC, body_key)")
        db.beginTransaction()
        try {
            db.compileStatement("INSERT INTO metadata VALUES ('s', ?, ?, 'body')").use { insert ->
                repeat(size) { index ->
                    insert.bindLong(1, index.toLong())
                    insert.bindString(2, index.toString().padStart(8, '0'))
                    insert.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun page(db: SQLiteDatabase, sql: String, args: Array<String>): List<String> =
        db.rawQuery(sql, args).use { cursor ->
            assertEquals(listOf("event_id", "body_key"), cursor.columnNames.toList())
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    companion object {
        private const val PROJECTION = "SELECT event_id, body_key FROM metadata WHERE scope=?"
        private const val ORDER = " ORDER BY order_key DESC, event_id DESC LIMIT 64"
        private const val SEEK = "$PROJECTION AND (order_key,event_id)<(?,?)$ORDER"
        private const val OFFSET = "$PROJECTION$ORDER OFFSET ?"
    }
}
