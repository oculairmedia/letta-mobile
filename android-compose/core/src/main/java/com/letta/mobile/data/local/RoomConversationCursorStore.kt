package com.letta.mobile.data.local

import com.letta.mobile.data.timeline.ConversationCursorStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomConversationCursorStore @Inject constructor(
    private val dao: ConversationCursorDao,
) : ConversationCursorStore {
    override suspend fun recordFrame(conversationId: String, seq: Long) {
        dao.upsertCursor(
            conversationId = conversationId,
            highestSeenSeq = seq,
            updatedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun getCursor(conversationId: String): Long? =
        dao.getCursor(conversationId)?.highestSeenSeq

    override suspend fun getAllCursors(): Map<String, Long> =
        dao.listCursors().associate { it.conversationId to it.highestSeenSeq }
}
