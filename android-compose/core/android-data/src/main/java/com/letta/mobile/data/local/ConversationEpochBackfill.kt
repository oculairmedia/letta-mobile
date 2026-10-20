package com.letta.mobile.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import com.letta.mobile.data.model.ConversationRecency

/**
 * letta-mobile-by4vm.19 (v21 -> v22): fills the epoch columns of rows cached before v22 with the
 * same parser new writes use. SQLite's `strftime` is not used: its fraction and offset handling
 * varies with the SQLite version a device ships, and a mismatch with write-time parsing would
 * make migrated and fresh rows interleave wrongly. The conversation cache is small (one row per
 * conversation), so a single read then per-row updates is bounded.
 */
internal object ConversationEpochBackfill {
    private data class Row(val id: String, val lastMessageAt: String?, val updatedAt: String?, val createdAt: String?)

    fun run(db: SupportSQLiteDatabase) {
        db.compileStatement(UPDATE_EPOCHS_SQL).use { update ->
            readRows(db).forEach { row -> writeEpochs(update, row) }
        }
    }

    private fun readRows(db: SupportSQLiteDatabase): List<Row> =
        db.query("SELECT `id`, `lastMessageAt`, `updatedAt`, `createdAt` FROM `conversations`").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Row(
                            id = cursor.getString(0),
                            lastMessageAt = cursor.stringOrNull(1),
                            updatedAt = cursor.stringOrNull(2),
                            createdAt = cursor.stringOrNull(3),
                        ),
                    )
                }
            }
        }

    /** Typed binds (no `Array<Any?>` bind list): a null epoch binds SQL NULL. */
    private fun writeEpochs(update: SupportSQLiteStatement, row: Row) {
        update.clearBindings()
        update.bindEpochOrNull(1, ConversationRecency.epochMillisOrNull(row.lastMessageAt))
        update.bindEpochOrNull(2, ConversationRecency.epochMillisOrNull(row.updatedAt))
        update.bindEpochOrNull(3, ConversationRecency.epochMillisOrNull(row.createdAt))
        update.bindString(4, row.id)
        update.executeUpdateDelete()
    }

    private fun SupportSQLiteStatement.bindEpochOrNull(index: Int, epochMs: Long?) {
        if (epochMs == null) bindNull(index) else bindLong(index, epochMs)
    }

    private const val UPDATE_EPOCHS_SQL =
        "UPDATE `conversations` SET `lastMessageAtEpochMs` = ?, `updatedAtEpochMs` = ?, `createdAtEpochMs` = ? " +
            "WHERE `id` = ?"

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
}
