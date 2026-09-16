package com.letta.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingLocalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PendingLocalEntity)

    @Query(
        "SELECT otid, conversationId, content, attachmentsJson, sentAtEpochMs, deliveryState " +
            "FROM pending_local_messages WHERE conversationId = :conversationId ORDER BY sentAtEpochMs ASC",
    )
    suspend fun listForConversation(conversationId: String): List<PendingLocalEntity>

    @Query("DELETE FROM pending_local_messages WHERE otid = :otid")
    suspend fun deleteByOtid(otid: String)

    @Query("UPDATE pending_local_messages SET deliveryState = 'FAILED' WHERE otid = :otid")
    suspend fun markFailed(otid: String)

    @Query("DELETE FROM pending_local_messages WHERE conversationId = :conversationId AND deliveryState = 'FAILED' AND otid != :keepOtid")
    suspend fun deleteOtherFailures(conversationId: String, keepOtid: String)

    @Query("SELECT conversationId FROM pending_local_messages WHERE otid = :otid LIMIT 1")
    suspend fun conversationIdForOtid(otid: String): String?

    @androidx.room.Transaction
    suspend fun markFailedAndKeepNewest(otid: String) {
        val conversationId = conversationIdForOtid(otid) ?: return
        markFailed(otid)
        deleteOtherFailures(conversationId, otid)
    }

    @Query("DELETE FROM pending_local_messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)
}
