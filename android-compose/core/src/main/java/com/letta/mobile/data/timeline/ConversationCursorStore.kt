package com.letta.mobile.data.timeline

interface ConversationCursorStore {
    suspend fun recordFrame(conversationId: String, seq: Long)
    suspend fun getCursor(conversationId: String): Long?
}

object NoOpConversationCursorStore : ConversationCursorStore {
    override suspend fun recordFrame(conversationId: String, seq: Long) = Unit
    override suspend fun getCursor(conversationId: String): Long? = null
}
