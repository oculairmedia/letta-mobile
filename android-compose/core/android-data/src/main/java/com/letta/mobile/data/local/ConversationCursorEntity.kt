package com.letta.mobile.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "conversation_cursors")
data class ConversationCursorEntity(
    @PrimaryKey
    @ColumnInfo(name = "conv_id")
    val conversationId: String,
    @ColumnInfo(name = "highest_seen_seq")
    val highestSeenSeq: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Dao
interface ConversationCursorDao {
    @Query(
        """
        INSERT INTO conversation_cursors (conv_id, highest_seen_seq, updated_at)
        VALUES (:conversationId, :highestSeenSeq, :updatedAt)
        ON CONFLICT(conv_id) DO UPDATE SET
            highest_seen_seq = MAX(conversation_cursors.highest_seen_seq, excluded.highest_seen_seq),
            updated_at = CASE
                WHEN excluded.highest_seen_seq >= conversation_cursors.highest_seen_seq
                THEN excluded.updated_at
                ELSE conversation_cursors.updated_at
            END
        """,
    )
    suspend fun upsertCursor(
        conversationId: String,
        highestSeenSeq: Long,
        updatedAt: Long,
    )

    @Query("SELECT * FROM conversation_cursors WHERE conv_id = :conversationId")
    suspend fun getCursor(conversationId: String): ConversationCursorEntity?

    @Query("SELECT * FROM conversation_cursors ORDER BY conv_id")
    suspend fun listCursors(): List<ConversationCursorEntity>

    @Query("DELETE FROM conversation_cursors WHERE conv_id = :conversationId")
    suspend fun deleteCursor(conversationId: String)
}
