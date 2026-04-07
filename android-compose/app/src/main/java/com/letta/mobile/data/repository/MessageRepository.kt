package com.letta.mobile.data.repository

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreate
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.domain.MessageProcessor
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val messageApi: MessageApi,
    private val messageProcessor: MessageProcessor,
) {
    private val json = Json { ignoreUnknownKeys = true }
    
    private val _messagesByAgent = MutableStateFlow<Map<String, List<AppMessage>>>(emptyMap())
    private val _messagesByConversation = MutableStateFlow<Map<String, List<AppMessage>>>(emptyMap())

    fun getMessages(agentId: String, conversationId: String? = null): Flow<List<AppMessage>> {
        return if (conversationId != null) {
            flow {
                val cached = _messagesByConversation.value[conversationId]
                emit(cached ?: emptyList())
            }
        } else {
            flow {
                val cached = _messagesByAgent.value[agentId]
                emit(cached ?: emptyList())
            }
        }
    }

    fun sendMessage(
        agentId: String,
        text: String,
        conversationId: String? = null
    ): Flow<StreamState> = flow {
        emit(StreamState.Sending)

        val optimisticMessage = AppMessage(
            id = "temp-${System.currentTimeMillis()}",
            date = Instant.now(),
            messageType = MessageType.USER,
            content = text
        )

        addMessageToCache(agentId, conversationId, optimisticMessage)

        try {
            val request = MessageCreateRequest(
                messages = listOf(
                    MessageCreate(
                        role = "user",
                        content = text
                    )
                ),
                streaming = true
            )

            if (conversationId == null) {
                throw NotImplementedError("Agent-level streaming not yet implemented in MessageApi")
            }

            val streamChannel = messageApi.sendConversationMessage(conversationId, request)

            val messages = mutableListOf<AppMessage>()
            val messageFlow = flow {
                while (!streamChannel.isClosedForRead) {
                    val line = streamChannel.readUTF8Line() ?: break
                    if (line.isBlank()) continue
                    
                    val cleaned = if (line.startsWith("data: ")) {
                        line.removePrefix("data: ")
                    } else {
                        line
                    }

                    if (cleaned == "[DONE]") break

                    try {
                        val message = json.decodeFromString<LettaMessage>(cleaned)
                        emit(message)
                    } catch (e: Exception) {
                    }
                }
            }

            messageProcessor.processStream(messageFlow, agentId, conversationId, messageApi)
                .collect { appMessage ->
                    messages.add(appMessage)
                    
                    if (appMessage.messageType == MessageType.TOOL_CALL) {
                        emit(StreamState.ToolExecution(appMessage.toolName ?: "unknown"))
                    } else {
                        emit(StreamState.Streaming(messages))
                    }
                }

            emit(StreamState.Complete(messages))
            
            updateMessagesInCache(agentId, conversationId, messages)

        } catch (e: Exception) {
            emit(StreamState.Error(e.message ?: "Unknown error"))
        }
    }

    suspend fun resetMessages(agentId: String) {
        messageApi.resetMessages(agentId)
        _messagesByAgent.value = _messagesByAgent.value.toMutableMap().apply {
            remove(agentId)
        }
    }

    private fun addMessageToCache(agentId: String, conversationId: String?, message: AppMessage) {
        if (conversationId != null) {
            _messagesByConversation.value = _messagesByConversation.value.toMutableMap().apply {
                val existing = get(conversationId) ?: emptyList()
                put(conversationId, existing + message)
            }
        } else {
            _messagesByAgent.value = _messagesByAgent.value.toMutableMap().apply {
                val existing = get(agentId) ?: emptyList()
                put(agentId, existing + message)
            }
        }
    }

    private fun updateMessagesInCache(agentId: String, conversationId: String?, messages: List<AppMessage>) {
        if (conversationId != null) {
            _messagesByConversation.value = _messagesByConversation.value.toMutableMap().apply {
                put(conversationId, messages)
            }
        } else {
            _messagesByAgent.value = _messagesByAgent.value.toMutableMap().apply {
                put(agentId, messages)
            }
        }
    }
}
