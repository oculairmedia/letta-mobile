package com.letta.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Every [ConversationEntity] column, so a schema change cannot silently widen a mapping. */
private const val CONVERSATION_COLUMNS =
    "id, agentId, summary, createdAt, updatedAt, lastMessageAt, archived, archivedAt, " +
        "inContextMessageIdsJson, isolatedBlockIdsJson, cachedAtEpochMs, model, contextWindowLimit, modelEffort, " +
        "lastMessageAtEpochMs, updatedAtEpochMs, createdAtEpochMs"

private const val REFRESH_STATE_COLUMNS = "agentId, lastRefreshAtMillis"

/**
 * letta-mobile-by4vm.19: newest activity first, on epoch millis rather than the ISO strings. A
 * conversation with no parseable timestamp sorts last (not by id); `id` only breaks exact ties so
 * the order is stable.
 */
private const val CONVERSATION_RECENCY_ORDER =
    "COALESCE(lastMessageAtEpochMs, updatedAtEpochMs, createdAtEpochMs, 0) DESC, id"

@Dao
interface ConversationDao {
    @Query(
        """
        SELECT $CONVERSATION_COLUMNS FROM conversations
        WHERE agentId = :agentId
        ORDER BY $CONVERSATION_RECENCY_ORDER
        """,
    )
    fun observeForAgent(agentId: String): Flow<List<ConversationEntity>>

    @Query(
        """
        SELECT $CONVERSATION_COLUMNS FROM conversations
        WHERE agentId = :agentId
        ORDER BY $CONVERSATION_RECENCY_ORDER
        """,
    )
    suspend fun getForAgentOnce(agentId: String): List<ConversationEntity>

    @Query(
        """
        SELECT $CONVERSATION_COLUMNS FROM conversations
        ORDER BY $CONVERSATION_RECENCY_ORDER
        """,
    )
    suspend fun getAllOnce(): List<ConversationEntity>

    @Query("SELECT $CONVERSATION_COLUMNS FROM conversations WHERE id = :conversationId LIMIT 1")
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

    @Query("SELECT $REFRESH_STATE_COLUMNS FROM conversation_refresh_state WHERE agentId = :agentId LIMIT 1")
    suspend fun getRefreshState(agentId: String): ConversationRefreshEntity?

    @Query("SELECT $REFRESH_STATE_COLUMNS FROM conversation_refresh_state")
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
