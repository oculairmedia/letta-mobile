package com.letta.mobile.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase

/** Dormant, separately named database. Never register as a LettaDatabase 13 -> 14 migration. */
@Database(
    entities = [LedgerHead::class, LedgerRow::class, LedgerBlob::class, LedgerChunk::class, LedgerEvidence::class, LedgerMigrationState::class, LedgerMigrationRow::class, LedgerToolCall::class, LedgerToolSweep::class, LedgerValidationState::class],
    version = 3,
    exportSchema = true,
)
abstract class TimelineLedgerDatabase : RoomDatabase() {
    abstract fun ledger(): TimelineLedgerDao

    companion object {
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS ledger_validation (scope BLOB NOT NULL, payload BLOB NOT NULL, checksum TEXT NOT NULL, PRIMARY KEY(scope))")
            }
        }
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS ledger_tool_call (scope BLOB NOT NULL, callId BLOB NOT NULL, owner BLOB, returned INTEGER NOT NULL, unresolved INTEGER NOT NULL, PRIMARY KEY(scope, callId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_tool_call_scope_unresolved_callId ON ledger_tool_call(scope, unresolved, callId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ledger_tool_sweep (scope BLOB NOT NULL, generation INTEGER NOT NULL, PRIMARY KEY(scope))")
            }
        }
    }
}

/** Derived unresolved bit makes filtered enumeration an exact index range, not a history scan. */
@Entity(tableName = "ledger_tool_call", primaryKeys = ["scope", "callId"],
    indices = [Index(value = ["scope", "unresolved", "callId"])])
data class LedgerToolCall(val scope: ByteArray, val callId: ByteArray, val owner: ByteArray?, val returned: Boolean, val unresolved: Boolean)

@Entity(tableName = "ledger_tool_sweep", primaryKeys = ["scope"])
data class LedgerToolSweep(val scope: ByteArray, val generation: Long)

@Entity(tableName = "ledger_head", primaryKeys = ["scope"])
data class LedgerHead(val scope: ByteArray, val revision: Long, val checkpoint: ByteArray)

@Entity(
    tableName = "ledger_row",
    primaryKeys = ["scope", "identity"],
    indices = [Index(value = ["scope", "position", "identity"], orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC])],
)
data class LedgerRow(
    val scope: ByteArray,
    val identity: ByteArray,
    val position: Long,
    val pointer: String,
    val bytes: Long,
    val contentType: String,
    val revision: Long,
)

@Entity(tableName = "ledger_blob", primaryKeys = ["scope", "pointer"])
data class LedgerBlob(val scope: ByteArray, val pointer: String, val bytes: Long, val checksum: String)

@Entity(tableName = "ledger_chunk", primaryKeys = ["scope", "pointer", "ordinal"])
data class LedgerChunk(
    val scope: ByteArray,
    val pointer: String,
    val ordinal: Long,
    val payload: ByteArray,
    val checksum: String,
)

@Entity(tableName = "ledger_evidence", primaryKeys = ["scope", "identity"])
data class LedgerEvidence(val scope: ByteArray, val identity: ByteArray, val pointer: String, val bytes: Long)

@Entity(tableName = "ledger_migration", primaryKeys = ["scope"])
data class LedgerMigrationState(
    val scope: ByteArray,
    val sourceToken: String,
    val generation: String,
    val afterOrder: Long,
    val copiedRows: Long,
    val complete: Boolean,
    val pendingPointer: String? = null,
    val pendingOffset: Long = 0,
    val pendingDigest: String = "",
)

@Entity(tableName = "ledger_migration_row", primaryKeys = ["scope", "generation", "position"])
data class LedgerMigrationRow(
    val scope: ByteArray,
    val generation: String,
    val position: Long,
    val primaryIdentity: Long,
    val secondaryIdentity: Long,
    val pointer: String,
    val bytes: Long,
    val checksum: String,
)

@Entity(tableName = "ledger_validation", primaryKeys = ["scope"])
data class LedgerValidationState(val scope: ByteArray, val payload: ByteArray, val checksum: String)

@Dao
interface TimelineLedgerDao {
    @Query("""SELECT EXISTS(SELECT 1 FROM ledger_head WHERE scope = :scope)
        OR EXISTS(SELECT 1 FROM ledger_row WHERE scope = :scope)
        OR EXISTS(SELECT 1 FROM ledger_blob WHERE scope = :scope)
        OR EXISTS(SELECT 1 FROM ledger_chunk WHERE scope = :scope)
        OR EXISTS(SELECT 1 FROM ledger_evidence WHERE scope = :scope)
        OR EXISTS(SELECT 1 FROM ledger_tool_call WHERE scope = :scope)
        OR EXISTS(SELECT 1 FROM ledger_tool_sweep WHERE scope = :scope)
        OR EXISTS(SELECT 1 FROM ledger_migration WHERE scope = :scope)
        OR EXISTS(SELECT 1 FROM ledger_migration_row WHERE scope = :scope)
        OR EXISTS(SELECT 1 FROM ledger_validation WHERE scope = :scope)""")
    suspend fun hasScopeEvidence(scope: ByteArray): Boolean

    @Query("SELECT scope, substr(payload, 1, 16385) AS payload, checksum FROM ledger_validation WHERE scope = :scope")
    suspend fun validation(scope: ByteArray): LedgerValidationState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun validation(state: LedgerValidationState)

    @Query("SELECT substr(payload, max(0, :start) + 1, min(32768, max(0, :limit))) FROM ledger_chunk WHERE scope = :scope AND pointer = :pointer AND ordinal = :ordinal")
    suspend fun auditChunk(scope: ByteArray, pointer: String, ordinal: Long, start: Int, limit: Int): ByteArray?

    @Query("SELECT scope, identity, pointer, bytes FROM ledger_evidence WHERE scope = :scope AND identity != :excluded AND identity > :after ORDER BY identity LIMIT 1")
    suspend fun auditEvidence(scope: ByteArray, excluded: ByteArray, after: ByteArray): LedgerEvidence?

    @Query("SELECT scope, identity, pointer, bytes FROM ledger_evidence WHERE scope = :scope AND identity != :excluded ORDER BY identity LIMIT 1")
    suspend fun auditEvidenceFirst(scope: ByteArray, excluded: ByteArray): LedgerEvidence?

    @Query("SELECT scope, callId, owner, returned, unresolved FROM ledger_tool_call WHERE scope = :scope AND callId > :after ORDER BY callId LIMIT 1")
    suspend fun auditTool(scope: ByteArray, after: ByteArray): LedgerToolCall?

    @Query("SELECT scope, callId, owner, returned, unresolved FROM ledger_tool_call WHERE scope = :scope ORDER BY callId LIMIT 1")
    suspend fun auditToolFirst(scope: ByteArray): LedgerToolCall?

    @Query("SELECT scope, callId, owner, returned, unresolved FROM ledger_tool_call WHERE scope = :scope AND callId = :callId")
    suspend fun toolCall(scope: ByteArray, callId: ByteArray): LedgerToolCall?

    @Query("SELECT scope, callId, owner, returned, unresolved FROM ledger_tool_call WHERE scope = :scope AND unresolved = 1 AND owner IS NOT NULL AND returned = 0 ORDER BY callId LIMIT min(128, max(0, :limit))")
    suspend fun unresolvedTools(scope: ByteArray, limit: Int): List<LedgerToolCall>

    @Query("SELECT scope, callId, owner, returned, unresolved FROM ledger_tool_call WHERE scope = :scope AND unresolved = 1 AND callId > :after AND owner IS NOT NULL AND returned = 0 ORDER BY callId LIMIT min(128, max(0, :limit))")
    suspend fun unresolvedToolsAfter(scope: ByteArray, after: ByteArray, limit: Int): List<LedgerToolCall>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun toolCall(entry: LedgerToolCall)

    @Query("SELECT generation FROM ledger_tool_sweep WHERE scope = :scope")
    suspend fun toolSweepGeneration(scope: ByteArray): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun toolSweep(entry: LedgerToolSweep)

    @Query("SELECT scope, sourceToken, generation, afterOrder, copiedRows, complete, pendingPointer, pendingOffset, pendingDigest FROM ledger_migration WHERE scope = :scope")
    suspend fun migration(scope: ByteArray): LedgerMigrationState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun migration(state: LedgerMigrationState)

    @Insert
    suspend fun migrationRow(row: LedgerMigrationRow)

    @Query("SELECT scope, generation, position, primaryIdentity, secondaryIdentity, pointer, bytes, checksum FROM ledger_migration_row WHERE scope = :scope AND generation = :generation AND position > :after ORDER BY position LIMIT min(128, max(0, :limit))")
    suspend fun migrationRows(scope: ByteArray, generation: String, after: Long, limit: Int): List<LedgerMigrationRow>

    @Query("SELECT scope, revision, checkpoint FROM ledger_head WHERE scope = :scope")
    suspend fun head(scope: ByteArray): LedgerHead?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun head(head: LedgerHead)

    @Query("SELECT scope, identity, position, pointer, bytes, contentType, revision FROM ledger_row WHERE scope = :scope ORDER BY position DESC, identity DESC LIMIT min(128, max(0, :limit))")
    suspend fun tail(scope: ByteArray, limit: Int): List<LedgerRow>

    @Query("SELECT scope, identity, position, pointer, bytes, contentType, revision FROM ledger_row WHERE scope = :scope AND position < :position ORDER BY position DESC, identity DESC LIMIT min(128, max(0, :limit))")
    suspend fun beforeLowerPosition(scope: ByteArray, position: Long, limit: Int): List<LedgerRow>

    @Query("SELECT scope, identity, position, pointer, bytes, contentType, revision FROM ledger_row WHERE scope = :scope AND position = :position AND identity < :identity ORDER BY identity DESC LIMIT min(128, max(0, :limit))")
    suspend fun beforeSamePosition(scope: ByteArray, position: Long, identity: ByteArray, limit: Int): List<LedgerRow>

    @Query("SELECT scope, identity, position, pointer, bytes, contentType, revision FROM ledger_row WHERE scope = :scope AND position > :position ORDER BY position ASC, identity ASC LIMIT min(128, max(0, :limit))")
    suspend fun afterHigherPosition(scope: ByteArray, position: Long, limit: Int): List<LedgerRow>

    @Query("SELECT scope, identity, position, pointer, bytes, contentType, revision FROM ledger_row WHERE scope = :scope AND position = :position AND identity > :identity ORDER BY identity ASC LIMIT min(128, max(0, :limit))")
    suspend fun afterSamePosition(scope: ByteArray, position: Long, identity: ByteArray, limit: Int): List<LedgerRow>

    @Query("SELECT EXISTS(SELECT 1 FROM ledger_row WHERE scope = :scope AND position < :position LIMIT 1)")
    suspend fun hasLowerPosition(scope: ByteArray, position: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM ledger_row WHERE scope = :scope AND position = :position AND identity < :identity LIMIT 1)")
    suspend fun hasSamePositionBefore(scope: ByteArray, position: Long, identity: ByteArray): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM ledger_row WHERE scope = :scope AND position > :position LIMIT 1)")
    suspend fun hasHigherPosition(scope: ByteArray, position: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM ledger_row WHERE scope = :scope AND position = :position AND identity > :identity LIMIT 1)")
    suspend fun hasSamePositionAfter(scope: ByteArray, position: Long, identity: ByteArray): Boolean

    @Query("SELECT scope, identity, position, pointer, bytes, contentType, revision FROM ledger_row WHERE scope = :scope AND identity = :identity")
    suspend fun locate(scope: ByteArray, identity: ByteArray): LedgerRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun row(row: LedgerRow)

    @Query("UPDATE ledger_row SET revision = :revision WHERE scope = :scope AND identity = :identity AND revision = :staged")
    suspend fun stampIdentity(scope: ByteArray, identity: ByteArray, staged: Long, revision: Long)

    @Query("DELETE FROM ledger_row WHERE scope = :scope AND identity = :identity")
    suspend fun deleteRow(scope: ByteArray, identity: ByteArray)

    @Insert
    suspend fun blob(blob: LedgerBlob)

    @Insert
    suspend fun chunk(chunk: LedgerChunk)

    @Query("SELECT scope, pointer, bytes, checksum FROM ledger_blob WHERE scope = :scope AND pointer = :pointer")
    suspend fun blob(scope: ByteArray, pointer: String): LedgerBlob?

    @Query("SELECT scope, pointer, ordinal, payload, checksum FROM ledger_chunk WHERE scope = :scope AND pointer = :pointer AND ordinal = :ordinal")
    suspend fun chunk(scope: ByteArray, pointer: String, ordinal: Long): LedgerChunk?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun evidence(evidence: LedgerEvidence)

    @Query("SELECT scope, identity, pointer, bytes FROM ledger_evidence WHERE scope = :scope AND identity = :identity")
    suspend fun evidence(scope: ByteArray, identity: ByteArray): LedgerEvidence?

    @Query("SELECT scope, identity, pointer, bytes FROM ledger_evidence WHERE scope = :scope AND (:after IS NULL OR identity > :after) ORDER BY identity LIMIT 128")
    suspend fun evidencePage(scope: ByteArray, after: ByteArray?): List<LedgerEvidence>

    @Query("DELETE FROM ledger_evidence WHERE scope = :scope AND identity = :identity")
    suspend fun deleteEvidence(scope: ByteArray, identity: ByteArray)
}

internal suspend fun TimelineLedgerDao.before(scope: ByteArray, position: Long, identity: ByteArray, limit: Int): List<LedgerRow> {
    if (limit <= 0) return emptyList()
    val same = beforeSamePosition(scope, position, identity, limit)
    if (same.size >= limit) return same
    return same + beforeLowerPosition(scope, position, limit - same.size)
}

internal suspend fun TimelineLedgerDao.after(scope: ByteArray, position: Long, identity: ByteArray, limit: Int): List<LedgerRow> {
    if (limit <= 0) return emptyList()
    val same = afterSamePosition(scope, position, identity, limit)
    if (same.size >= limit) return same
    return same + afterHigherPosition(scope, position, limit - same.size)
}

internal suspend fun TimelineLedgerDao.hasBefore(scope: ByteArray, position: Long, identity: ByteArray): Boolean =
    hasLowerPosition(scope, position) || hasSamePositionBefore(scope, position, identity)

internal suspend fun TimelineLedgerDao.hasAfter(scope: ByteArray, position: Long, identity: ByteArray): Boolean =
    hasHigherPosition(scope, position) || hasSamePositionAfter(scope, position, identity)

internal fun ledgerScopeKey(scope: com.letta.mobile.data.timeline.snapshot.TimelineScope): ByteArray =
    ledgerKey(scope.storageKey + if (scope.agentId == null) "N" else "S")

/** Big endian code units, not UTF-8: SQLite BLOB order exactly matches Kotlin String.compareTo. */
internal fun ledgerKey(value: String): ByteArray = ByteArray(value.length * 2).also { bytes ->
    value.forEachIndexed { index, char ->
        bytes[index * 2] = (char.code ushr 8).toByte()
        bytes[index * 2 + 1] = char.code.toByte()
    }
}

internal fun ledgerString(value: ByteArray): String {
    require(value.size % 2 == 0) { "Corrupt UTF16 ledger key" }
    return CharArray(value.size / 2) { index ->
        (((value[index * 2].toInt() and 255) shl 8) or (value[index * 2 + 1].toInt() and 255)).toChar()
    }.concatToString()
}
