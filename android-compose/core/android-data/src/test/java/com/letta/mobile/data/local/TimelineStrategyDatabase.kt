package com.letta.mobile.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(
    tableName = "strategy_metadata",
    primaryKeys = ["scope", "eventId"],
    indices = [Index(value = ["scope", "orderKey", "eventId"], orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC])],
)
data class StrategyMetadata(val scope: String, val eventId: String, val orderKey: Long, val bodyKey: String)

@Dao
interface StrategyMetadataDao {
    @Insert
    suspend fun insert(rows: List<StrategyMetadata>)

    @Query("SELECT scope,eventId,orderKey,bodyKey FROM strategy_metadata WHERE scope=:scope ORDER BY orderKey DESC,eventId DESC")
    fun generatedOffsetPages(scope: String): androidx.paging.PagingSource<Int, StrategyMetadata>

    @Query("SELECT scope,eventId,orderKey,bodyKey FROM strategy_metadata WHERE scope=:scope ORDER BY orderKey DESC,eventId DESC LIMIT :limit OFFSET :offset")
    suspend fun offsetPage(scope: String, limit: Int, offset: Int): List<StrategyMetadata>

    @Query("SELECT scope,eventId,orderKey,bodyKey FROM strategy_metadata WHERE scope=:scope AND (orderKey,eventId)<(:orderKey,:eventId) ORDER BY orderKey DESC,eventId DESC LIMIT :limit")
    suspend fun seekPage(scope: String, orderKey: Long, eventId: String, limit: Int): List<StrategyMetadata>
}

@Database(entities = [StrategyMetadata::class], version = 1, exportSchema = false)
abstract class TimelineStrategyDatabase : RoomDatabase() {
    abstract fun metadata(): StrategyMetadataDao
}
