package com.letta.mobile.data.local

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.repository.api.AllConversationsLocalCache

/**
 * Room-backed [AllConversationsLocalCache] for [com.letta.mobile.data.repository.CachedAllConversationsRepository].
 * Every access waits for the session's cache clear (letta-mobile-by4vm.94, [GatedDao]).
 */
class RoomAllConversationsLocalCache(
    private val conversationDao: GatedDao<ConversationDao>,
) : AllConversationsLocalCache {
    override suspend fun getAllOnce(): List<Conversation> =
        conversationDao.get().getAllOnce().map { it.toConversation() }

    override suspend fun upsert(conversation: Conversation) {
        conversationDao.get().upsert(ConversationEntity.fromConversation(conversation))
    }

    override suspend fun upsertAll(conversations: List<Conversation>) {
        conversationDao.get().upsertAll(conversations.map { ConversationEntity.fromConversation(it) })
    }

    override suspend fun delete(conversationId: String) {
        conversationDao.get().delete(conversationId)
    }

    override suspend fun deleteAll() {
        conversationDao.get().deleteAll()
    }

    override suspend fun deleteAllRefreshStates() {
        conversationDao.get().deleteAllRefreshStates()
    }
}
