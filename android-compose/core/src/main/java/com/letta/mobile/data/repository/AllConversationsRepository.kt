package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.model.Conversation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
            _hasMore.update { false }
        }

        if (newConversations.isNotEmpty()) {
            _conversations.update { current ->
                val existingIds = current.map { it.id }.toSet()
                val deduped = newConversations.filter { it.id !in existingIds }
                current + deduped
            }
            currentCursor = newConversations.last().id
        }
    }

    suspend fun refresh() {
        currentCursor = null
        _conversations.update { emptyList() }
        _hasMore.update { true }
        loadNextPage()
    }

    fun handleOptimisticUpdate(conversation: Conversation) {
        _conversations.update { current ->
            val index = current.indexOfFirst { it.id == conversation.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = conversation }
            } else {
                listOf(conversation) + current
            }
        }
    }

    fun handleOptimisticDelete(conversationId: String) {
        _conversations.update { current -> current.filter { it.id != conversationId } }
    }

    companion object {
        private const val PAGE_SIZE = 50
    }
}
