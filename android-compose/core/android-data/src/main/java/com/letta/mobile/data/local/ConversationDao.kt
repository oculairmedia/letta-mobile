package com.letta.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query(
        """
        SELECT * FROM conversations
        WHERE agentId = :agentId
        ORDER BY COALESCE(lastMessageAt, updatedAt, createdAt, id) DESC
        """,
    )
    fun observeForAgent(agentId: String): Flow<List<ConversationEntity>>

    @Query(
        """
        SELECT * FROM conversations
        WHERE agentId = :agentId
        ORDER BY COALESCE(lastMessageAt, updatedAt, createdAt, id) DESC
        """,
    )
    suspend fun getForAgentOnce(agentId: String): List<ConversationEntity>

    @Query(
        """
        SELECT * FROM conversations
        ORDER BY COALESCE(lastMessageAt, updatedAt, createdAt, id) DESC
        """,
    )
    suspend fun getAllOnce(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    suspend fun getByIdOnce(conversationId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(conversations: List<ConversationEntity>)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun delete(conversationId: String)

    @Query("DELETE FROM conversations WHERE agentId = :agentId")
    suspend fun deleteForAgent(agentId: String)

    @Query("DELETE FROM conversations WHERE agentId = :agentId AND id NOT IN (:keepIds)")
    suspend fun deleteForAgentExcept(agentId: String, keepIds: List<String>)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    @Query("DELETE FROM conversation_refresh_state")
    suspend fun deleteAllRefreshStates()

    @Query("SELECT * FROM conversation_refresh_state WHERE agentId = :agentId LIMIT 1")
    suspend fun getRefreshState(agentId: String): ConversationRefreshEntity?

    @Query("SELECT * FROM conversation_refresh_state")
    suspend fun getAllRefreshStatesOnce(): List<ConversationRefreshEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRefreshState(state: ConversationRefreshEntity)

    @Transaction
    suspend fun replaceForAgent(agentId: String, conversations: List<ConversationEntity>, refreshedAtMillis: Long) {
        if (conversations.isEmpty()) {
            deleteForAgent(agentId)
        } else {
            upsertAll(conversations)
            deleteForAgentExcept(agentId, conversations.map { it.id })
        }
        upsertRefreshState(ConversationRefreshEntity(agentId = agentId, lastRefreshAtMillis = refreshedAtMillis))
    }
}
