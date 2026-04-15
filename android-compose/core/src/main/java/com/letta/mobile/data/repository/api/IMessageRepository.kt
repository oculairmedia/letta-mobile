package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.MessageSearchResult
import com.letta.mobile.data.repository.StreamState
import kotlinx.coroutines.flow.Flow

interface IMessageRepository {
    suspend fun fetchMessages(agentId: String, conversationId: String): List<AppMessage>
    fun getMessages(agentId: String, conversationId: String): Flow<List<AppMessage>>
    fun sendMessage(agentId: String, text: String, conversationId: String): Flow<StreamState>
    suspend fun resetMessages(agentId: String)
    suspend fun cancelMessage(agentId: String, runIds: List<String>? = null): Map<String, String>
    suspend fun searchMessages(request: MessageSearchRequest): List<MessageSearchResult>
}
