package com.letta.mobile.data.local

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

private const val REFRESH_STATE_COLUMNS = "agentId, lastRefreshAtMillis"

/** Per-agent cache freshness operations inherited by [ConversationDao] and its transactions. */
interface ConversationRefreshDao {
    @Query("DELETE FROM conversation_refresh_state")
    suspend fun deleteAllRefreshStates()

    @Query("SELECT $REFRESH_STATE_COLUMNS FROM conversation_refresh_state WHERE agentId = :agentId LIMIT 1")
    suspend fun getRefreshState(agentId: String): ConversationRefreshEntity?

    @Query("SELECT $REFRESH_STATE_COLUMNS FROM conversation_refresh_state")
    suspend fun getAllRefreshStatesOnce(): List<ConversationRefreshEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRefreshState(state: ConversationRefreshEntity)
}
