package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.repository.StreamState
import kotlinx.coroutines.flow.Flow

interface IMessageRepository {
    suspend fun fetchMessages(agentId: String, conversationId: String? = null): List<AppMessage>
    fun getMessages(agentId: String, conversationId: String? = null): Flow<List<AppMessage>>
    fun sendMessage(agentId: String, text: String, conversationId: String? = null): Flow<StreamState>
    suspend fun resetMessages(agentId: String)
}
