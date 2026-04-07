package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.model.Conversation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AllConversationsRepository @Inject constructor(
    private val conversationApi: ConversationApi,
) {
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private var currentCursor: String? = null

    suspend fun loadNextPage() {
        if (!_hasMore.value) return

        val newConversations = conversationApi.listConversations(
            limit = PAGE_SIZE,
            after = currentCursor
        )

        if (newConversations.isEmpty() || newConversations.size < PAGE_SIZE) {
            _hasMore.value = false
        }

        if (newConversations.isNotEmpty()) {
            val existingIds = _conversations.value.map { it.id }.toSet()
            val deduped = newConversations.filter { it.id !in existingIds }
            _conversations.value = _conversations.value + deduped
            currentCursor = newConversations.last().id
        }
    }

    suspend fun refresh() {
        currentCursor = null
        _conversations.value = emptyList()
        _hasMore.value = true
        loadNextPage()
    }

    fun handleOptimisticUpdate(conversation: Conversation) {
        val updatedList = _conversations.value.toMutableList()
        val index = updatedList.indexOfFirst { it.id == conversation.id }
        if (index >= 0) {
            updatedList[index] = conversation
        } else {
            updatedList.add(0, conversation)
        }
        _conversations.value = updatedList
    }

    fun handleOptimisticDelete(conversationId: String) {
        _conversations.value = _conversations.value.filter { it.id != conversationId }
    }

    companion object {
        private const val PAGE_SIZE = 50
    }
}
