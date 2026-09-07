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
    entities = [LedgerHead::class, LedgerRow::class, LedgerBlob::class, LedgerChunk::class, LedgerEvidence::class, LedgerMigrationState::class, LedgerMigrationRow::class],
    version = 1,
    exportSchema = true,
)
abstract class TimelineLedgerDatabase : RoomDatabase() {
    abstract fun ledger(): TimelineLedgerDao
}

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

@Dao
interface TimelineLedgerDao {
    @Query("SELECT * FROM ledger_migration WHERE scope = :scope")
    suspend fun migration(scope: ByteArray): LedgerMigrationState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun migration(state: LedgerMigrationState)

    @Insert
    suspend fun migrationRow(row: LedgerMigrationRow)

    @Query("SELECT * FROM ledger_migration_row WHERE scope = :scope AND generation = :generation AND position > :after ORDER BY position LIMIT min(128, max(0, :limit))")
    suspend fun migrationRows(scope: ByteArray, generation: String, after: Long, limit: Int): List<LedgerMigrationRow>

    @Query("SELECT * FROM ledger_head WHERE scope = :scope")
    suspend fun head(scope: ByteArray): LedgerHead?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun head(head: LedgerHead)

    @Query("SELECT * FROM ledger_row WHERE scope = :scope ORDER BY position DESC, identity DESC LIMIT min(128, max(0, :limit))")
    suspend fun tail(scope: ByteArray, limit: Int): List<LedgerRow>

    @Query("SELECT * FROM ledger_row WHERE scope = :scope AND (position < :position OR (position = :position AND identity < :identity)) ORDER BY position DESC, identity DESC LIMIT min(128, max(0, :limit))")
    suspend fun before(scope: ByteArray, position: Long, identity: ByteArray, limit: Int): List<LedgerRow>

    @Query("SELECT * FROM ledger_row WHERE scope = :scope AND (position > :position OR (position = :position AND identity > :identity)) ORDER BY position ASC, identity ASC LIMIT min(128, max(0, :limit))")
    suspend fun after(scope: ByteArray, position: Long, identity: ByteArray, limit: Int): List<LedgerRow>

    @Query("SELECT EXISTS(SELECT 1 FROM ledger_row WHERE scope = :scope AND (position < :position OR (position = :position AND identity < :identity)))")
    suspend fun hasBefore(scope: ByteArray, position: Long, identity: ByteArray): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM ledger_row WHERE scope = :scope AND (position > :position OR (position = :position AND identity > :identity)))")
    suspend fun hasAfter(scope: ByteArray, position: Long, identity: ByteArray): Boolean

    @Query("SELECT * FROM ledger_row WHERE scope = :scope AND identity = :identity")
    suspend fun locate(scope: ByteArray, identity: ByteArray): LedgerRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun row(row: LedgerRow)

    @Query("UPDATE ledger_row SET revision = :revision WHERE scope = :scope AND revision = :staged")
    suspend fun stamp(scope: ByteArray, staged: Long, revision: Long)

    @Query("DELETE FROM ledger_row WHERE scope = :scope AND identity = :identity")
    suspend fun deleteRow(scope: ByteArray, identity: ByteArray)

    @Insert
    suspend fun blob(blob: LedgerBlob)

    @Insert
    suspend fun chunk(chunk: LedgerChunk)

    @Query("SELECT * FROM ledger_blob WHERE scope = :scope AND pointer = :pointer")
    suspend fun blob(scope: ByteArray, pointer: String): LedgerBlob?

    @Query("SELECT * FROM ledger_chunk WHERE scope = :scope AND pointer = :pointer AND ordinal = :ordinal")
    suspend fun chunk(scope: ByteArray, pointer: String, ordinal: Long): LedgerChunk?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun evidence(evidence: LedgerEvidence)

    @Query("SELECT * FROM ledger_evidence WHERE scope = :scope AND identity = :identity")
    suspend fun evidence(scope: ByteArray, identity: ByteArray): LedgerEvidence?

    @Query("DELETE FROM ledger_evidence WHERE scope = :scope AND identity = :identity")
    suspend fun deleteEvidence(scope: ByteArray, identity: ByteArray)
}

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
