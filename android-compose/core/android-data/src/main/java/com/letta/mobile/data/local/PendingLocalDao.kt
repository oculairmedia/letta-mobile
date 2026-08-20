package com.letta.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingLocalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PendingLocalEntity)

    @Query("SELECT * FROM pending_local_messages WHERE conversationId = :conversationId ORDER BY sentAtEpochMs ASC")
    suspend fun listForConversation(conversationId: String): List<PendingLocalEntity>

    @Query("DELETE FROM pending_local_messages WHERE otid = :otid")
    suspend fun deleteByOtid(otid: String)

    @Query("DELETE FROM pending_local_messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)
}
