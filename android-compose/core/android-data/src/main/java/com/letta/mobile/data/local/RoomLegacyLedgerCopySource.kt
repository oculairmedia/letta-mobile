package com.letta.mobile.data.local

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.letta.mobile.data.timeline.snapshot.TimelineScope

/** Reads existing v13 tables without changing their schema or full-envelope validation path. */
class RoomLegacyLedgerCopySource(private val legacy: LettaDatabase) : LegacyLedgerCopySource {
    override suspend fun <T> snapshot(scope: TimelineScope, block: suspend LegacyLedgerCopyReader.() -> T): T =
        legacy.withTransaction {
            var open = true
            val reader = object : LegacyLedgerCopyReader {
                override suspend fun head(): LegacyLedgerCopyHead {
                    check(open)
                    return legacy.openHelper.readableDatabase.query(SimpleSQLiteQuery(
                        "SELECT agent_id, storage_layout_version, revision, envelope_schema_version, live_cursor, backfill_cursor, released_older_count, row_count, root_digest, row_digest, generation FROM normalized_timeline_snapshot_heads WHERE backend_id = ? AND conversation_id = ?",
                        arrayOf(scope.backendId, scope.conversationId),
                    )).use { cursor ->
                        check(cursor.moveToFirst()) { "No normalized source; retain legacy manifest fallback" }
                        val fields = (0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }
                        val token = fields.joinToString("") { if (it == null) "-1:" else "${it.length}:$it" }
                        LegacyLedgerCopyHead(token, cursor.getLong(7), fields[0] == scope.agentId && cursor.getInt(1) == NORMALIZED_LAYOUT_VERSION && cursor.getInt(3) in 1..com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope.CURRENT_SCHEMA_VERSION)
                    }
                }

                override suspend fun metadata(afterOrder: Long, maxRows: Int): List<LegacyLedgerCopyRow> {
                    check(open)
                    require(maxRows in 1..128)
                    return legacy.openHelper.readableDatabase.query(SimpleSQLiteQuery(
                        "SELECT event_order, identity_primary, identity_secondary, length(payload), checksum FROM normalized_timeline_snapshot_rows WHERE backend_id = ? AND conversation_id = ? AND event_order > ? ORDER BY event_order LIMIT min(128, max(0, CAST(? AS INTEGER)))",
                        arrayOf(scope.backendId, scope.conversationId, afterOrder.toString(), maxRows.toString()),
                    )).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) add(LegacyLedgerCopyRow(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getLong(3), cursor.getString(4)))
                        }
                    }
                }

                override suspend fun chunk(row: LegacyLedgerCopyRow, offset: Long, maxBytes: Int): ByteArray {
                    check(open)
                    require(offset >= 0 && offset < Long.MAX_VALUE && maxBytes in 0..65536)
                    return legacy.openHelper.readableDatabase.query(SimpleSQLiteQuery(
                        "SELECT substr(payload, ? + 1, min(65536, max(0, CAST(? AS INTEGER)))) FROM normalized_timeline_snapshot_rows WHERE backend_id = ? AND conversation_id = ? AND identity_primary = ? AND identity_secondary = ?",
                        arrayOf(offset.toString(), maxBytes.toString(), scope.backendId, scope.conversationId, row.primary.toString(), row.secondary.toString()),
                    )).use { cursor ->
                        check(cursor.moveToFirst()) { "Missing legacy row" }
                        cursor.getBlob(0)
                    }
                }
            }
            try { reader.block() } finally { open = false }
        }
}
