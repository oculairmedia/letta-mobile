package com.letta.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * letta-mobile-by4vm.17: every read is scoped by (backendId, agentId, conversationId).
 * A NULL on the row side is a legacy/unknown owner and matches any requested value; a
 * NULL requested agent (a loop that does not know its agent) matches every agent.
 */
@Dao
interface PendingLocalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PendingLocalEntity)

    @Query(
        "SELECT otid, conversationId, content, attachmentsJson, sentAtEpochMs, deliveryState, backendId, agentId " +
            "FROM pending_local_messages WHERE conversationId = :conversationId " +
            "AND (backendId IS NULL OR :backendId IS NULL OR backendId = :backendId) " +
            "AND (agentId IS NULL OR :agentId IS NULL OR agentId = :agentId) " +
            "ORDER BY sentAtEpochMs ASC",
    )
    suspend fun listForScope(backendId: String?, agentId: String?, conversationId: String): List<PendingLocalEntity>

    @Query("DELETE FROM pending_local_messages WHERE otid = :otid")
    suspend fun deleteByOtid(otid: String)

    @Query("UPDATE pending_local_messages SET deliveryState = 'FAILED' WHERE otid = :otid")
    suspend fun markFailed(otid: String)

    @Query(
        "DELETE FROM pending_local_messages WHERE conversationId = :conversationId " +
            "AND backendId IS :backendId AND agentId IS :agentId " +
            "AND deliveryState = 'FAILED' AND otid != :keepOtid",
    )
    suspend fun deleteOtherFailures(backendId: String?, agentId: String?, conversationId: String, keepOtid: String)

    @Query(
        "SELECT otid, conversationId, content, attachmentsJson, sentAtEpochMs, deliveryState, backendId, agentId " +
            "FROM pending_local_messages WHERE otid = :otid LIMIT 1",
    )
    suspend fun findByOtid(otid: String): PendingLocalEntity?

    @androidx.room.Transaction
    suspend fun markFailedAndKeepNewest(otid: String) {
        val row = findByOtid(otid) ?: return
        markFailed(otid)
        deleteOtherFailures(row.backendId, row.agentId, row.conversationId, otid)
    }

    /** Backend switch: drop the outgoing backend's rows and every legacy (unknown-backend) row. */
    @Query("DELETE FROM pending_local_messages WHERE backendId IS NULL OR backendId = :backendId")
    suspend fun deleteForBackend(backendId: String)
}
