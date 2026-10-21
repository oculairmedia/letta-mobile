package com.letta.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

private const val AGENT_COLUMNS =
    "id, name, description, model, embedding, agentType, enableSleeptime, createdAt, updatedAt, " +
        "tagsJson, metadataJson, toolCount, blockCount"

@Dao
interface AgentDao {
    @Query("SELECT $AGENT_COLUMNS FROM agents ORDER BY name ASC")
    fun getAll(): Flow<List<AgentEntity>>

    @Query("SELECT $AGENT_COLUMNS FROM agents ORDER BY name ASC")
    suspend fun getAllOnce(): List<AgentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(agents: List<AgentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(agent: AgentEntity)

    @Query("DELETE FROM agents WHERE id NOT IN (:keepIds)")
    suspend fun deleteExcept(keepIds: List<String>)

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM agents")
    suspend fun deleteAll()
}
