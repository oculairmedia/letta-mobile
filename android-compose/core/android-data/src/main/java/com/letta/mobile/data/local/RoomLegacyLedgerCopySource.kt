package com.letta.mobile.data.local

import androidx.room.withTransaction
import kotlinx.coroutines.ensureActive
import androidx.sqlite.db.SimpleSQLiteQuery
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope

/** Reads existing v13 tables without changing their schema or full-envelope validation path. */
class RoomLegacyLedgerCopySource(
    private val legacy: LettaDatabase,
    private val mapping: TimelineOwnershipAuthority.Mapping? = null,
) : LegacyLedgerCopySource {
    private fun sourceScope(target: TimelineScope): TimelineScope {
        val captured = mapping ?: return target
        check(target == captured.target) { "Captured migration target mismatch" }
        return captured.source
    }

    private fun bindToken(token: String): String = mapping?.let {
        val fields = listOf(it.source.backendId, it.source.conversationId, it.source.agentId,
            it.sourceEpoch.toString(), it.target.backendId, it.target.conversationId,
            it.target.agentId, it.targetEpoch.toString(), token)
        checksum(fields.joinToString("") { value -> if (value == null) "-1:" else "${value.length}:$value" }.encodeToByteArray())
    } ?: token
    /** Independent metadata verification, not canonical conversion or permission to activate.
     * Runs only post-open; bounded SQL pages avoid loading the legacy payload or row list.
     */
    suspend fun validateRoot(scope: TimelineScope, expectedToken: String): Boolean = snapshot(scope) {
        val initial = head()
        if (!initial.supported || initial.token != expectedToken) return@snapshot false
        val captured = sourceScope(scope)
        val stored = legacy.confirmedTimelineSnapshotDao().getNormalizedHead(captured.backendId, captured.conversationId)
        if (stored == null) {
            // A missing normalized head is empty only when no v13 snapshot exists.
            return@snapshot initial.rowCount == 0L && initial.supported && !hasLegacyManifestHistory(captured)
        }
        val flat = java.security.MessageDigest.getInstance("SHA-256")
        var chain = normalizedRowDigest(emptyList())
        var count = 0L
        var after = Long.MIN_VALUE
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val page = metadata(after, 128)
            if (page.isEmpty()) break
            for (row in page) {
                if (row.order != count || row.order > Int.MAX_VALUE) return@snapshot false
                val fields = object : NormalizedTimelineRowDigestFields {
                    override val identityPrimary = row.primary
                    override val identitySecondary = row.secondary
                    override val eventOrder = row.order.toInt()
                    override val checksum = row.checksum
                }
                if (stored.rowDigest.startsWith(CHAIN_ROW_DIGEST_PREFIX)) {
                    chain = incrementalNormalizedRowDigest(chain, listOf(fields)).removePrefix(CHAIN_ROW_DIGEST_PREFIX)
                } else {
                    val encoded = listOf(row.primary.toString(), row.secondary.toString(), row.order.toString(), row.checksum)
                        .joinToString("") { "${it.length}:$it;" }
                    flat.update(encoded.toByteArray(Charsets.UTF_8))
                }
                count++
                after = row.order
            }
        }
        val actual = if (stored.rowDigest.startsWith(CHAIN_ROW_DIGEST_PREFIX)) chain
            else flat.digest().joinToString("") { "%02x".format(it) }
        count == initial.rowCount && actual == stored.rowDigest.removePrefix(CHAIN_ROW_DIGEST_PREFIX).lowercase()
    }

    override suspend fun <T> snapshot(scope: TimelineScope, block: suspend LegacyLedgerCopyReader.() -> T): T =
        snapshotSource(sourceScope(scope), block)

    private suspend fun <T> snapshotSource(scope: TimelineScope, block: suspend LegacyLedgerCopyReader.() -> T): T =
        legacy.withTransaction {
            var open = true
            val reader = object : LegacyLedgerCopyReader {
                override suspend fun head(): LegacyLedgerCopyHead {
                    check(open)
                    val normalized = legacy.openHelper.readableDatabase.query(SimpleSQLiteQuery(
                        "SELECT agent_id, storage_layout_version, revision, envelope_schema_version, live_cursor, backfill_cursor, released_older_count, row_count, root_digest, row_digest, generation, written_at_millis FROM normalized_timeline_snapshot_heads WHERE backend_id = ? AND conversation_id = ?",
                        arrayOf(scope.backendId, scope.conversationId),
                    )).use { cursor ->
                        if (!cursor.moveToFirst()) return@use null
                        val fields = (0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }
                        val token = fields.joinToString("") { if (it == null) "-1:" else "${it.length}:$it" }
                        val envelope = StoredTimelineEnvelope(
                            schemaVersion = cursor.getInt(3), scope = scope, revision = cursor.getLong(2),
                            liveCursor = fields[4], backfillCursor = fields[5],
                            releasedOlderCount = cursor.getInt(6), writtenAtMillis = cursor.getLong(11),
                        )
                        val supported = fields[0] == scope.agentId && cursor.getInt(1) == NORMALIZED_LAYOUT_VERSION &&
                            envelope.schemaVersion in 1..StoredTimelineEnvelope.CURRENT_SCHEMA_VERSION &&
                            envelope.revision >= 0 && cursor.getLong(10) >= 0 && cursor.getLong(7) >= 0
                        if (supported) {
                            check(normalizedRootDigest(envelope, requireNotNull(fields[9])) == fields[8]?.lowercase()) {
                                "Legacy root checksum mismatch"
                            }
                        }
                        LegacyLedgerCopyHead(bindToken(token), cursor.getLong(7), supported)
                    }
                    if (normalized != null) return normalized
                    return if (hasLegacyManifestHistory(scope)) {
                        // History still lives in v13 manifests. Never certify that as empty.
                        LegacyLedgerCopyHead(bindToken("legacy-manifest"), 0, false)
                    } else {
                        LegacyLedgerCopyHead(bindToken("empty-normalized"), 0, true)
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

    private suspend fun hasLegacyManifestHistory(scope: TimelineScope): Boolean {
        val dao = legacy.confirmedTimelineSnapshotDao()
        return dao.getHeadMetadata(scope.backendId, scope.conversationId) != null ||
            dao.countManifests(scope.backendId, scope.conversationId) > 0
    }
}
