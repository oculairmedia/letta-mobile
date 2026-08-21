package com.letta.mobile.data.local

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.repository.api.AllConversationsLocalCache

/** Room-backed [AllConversationsLocalCache] for [com.letta.mobile.data.repository.CachedAllConversationsRepository]. */
class RoomAllConversationsLocalCache(
    private val conversationDao: ConversationDao,
) : AllConversationsLocalCache {
    override suspend fun getAllOnce(): List<Conversation> =
        conversationDao.getAllOnce().map { it.toConversation() }

    override suspend fun upsert(conversation: Conversation) {
        conversationDao.upsert(ConversationEntity.fromConversation(conversation))
    }

    override suspend fun upsertAll(conversations: List<Conversation>) {
        conversationDao.upsertAll(conversations.map { ConversationEntity.fromConversation(it) })
    }

    override suspend fun delete(conversationId: String) {
        conversationDao.delete(conversationId)
    }

    override suspend fun deleteAll() {
        conversationDao.deleteAll()
    }

    override suspend fun deleteAllRefreshStates() {
        conversationDao.deleteAllRefreshStates()
    }
}
