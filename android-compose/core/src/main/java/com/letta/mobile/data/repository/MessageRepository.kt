package com.letta.mobile.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreate
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.paging.MessagePagingSource
import com.letta.mobile.domain.MessageProcessor
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
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

    fun getMessagesPaged(agentId: String?, conversationId: String?): Flow<PagingData<AppMessage>> {
        return Pager(
            config = PagingConfig(
                pageSize = MessagePagingSource.PAGE_SIZE,
                enablePlaceholders = false,
                initialLoadSize = MessagePagingSource.PAGE_SIZE
            ),
            pagingSourceFactory = { MessagePagingSource(messageApi, agentId, conversationId) }
        ).flow
    }

    suspend fun fetchMessages(agentId: String, conversationId: String? = null): List<AppMessage> {
        // Try to fetch from API
        return try {
            val lettaMessages = if (conversationId != null) {
                messageApi.listConversationMessages(conversationId, limit = 100)
            } else {
                messageApi.listMessages(agentId, limit = 100)
            }

            val appMessages = lettaMessages.mapNotNull { it.toAppMessage() }

            // Update cache
            if (conversationId != null) {
                _messagesByConversation.update { current -> current.toMutableMap().apply {
                            put(conversationId, appMessages)
                        } }
            } else {
                _messagesByAgent.update { current -> current.toMutableMap().apply {
                            put(agentId, appMessages)
                        } }
            }

            appMessages
        } catch (e: Exception) {
            // Return cached or empty list on error
            if (conversationId != null) {
                _messagesByConversation.value[conversationId] ?: emptyList()
            } else {
                _messagesByAgent.value[agentId] ?: emptyList()
            }
        }
    }

    fun getMessages(agentId: String, conversationId: String? = null): Flow<List<AppMessage>> = flow {
        val messages = fetchMessages(agentId, conversationId)
        emit(messages)
    }

    private fun LettaMessage.toAppMessage(): AppMessage? {
        return when (this) {
            is UserMessage -> AppMessage(
                id = id,
                date = date?.let { parseDate(it) } ?: Instant.now(),
                messageType = MessageType.USER,
                content = content
            )
            is AssistantMessage -> AppMessage(
                id = id,
                date = date?.let { parseDate(it) } ?: Instant.now(),
                messageType = MessageType.ASSISTANT,
                content = content
            )
            is ReasoningMessage -> AppMessage(
                id = id,
                date = date?.let { parseDate(it) } ?: Instant.now(),
                messageType = MessageType.REASONING,
                content = reasoning
            )
            is ToolCallMessage -> AppMessage(
                id = id,
                date = date?.let { parseDate(it) } ?: Instant.now(),
                messageType = MessageType.TOOL_CALL,
                content = toolCall.arguments,
                toolName = toolCall.name,
                toolCallId = toolCall.effectiveId
            )
            is ToolReturnMessage -> AppMessage(
                id = id,
                date = date?.let { parseDate(it) } ?: Instant.now(),
                messageType = MessageType.TOOL_RETURN,
                content = toolReturn.funcResponse ?: "",
                toolCallId = toolReturn.toolCallId
            )
            else -> null // Skip other message types like HiddenReasoningMessage, EventMessage, etc.
        }
    }

    private fun parseDate(dateString: String): Instant {
        return try {
            Instant.parse(dateString)
        } catch (e: Exception) {
            Instant.now()
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
                emit(StreamState.Error("Please select or create a conversation first"))
                return@flow
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
                        android.util.Log.w("MessageRepository", "Failed to parse SSE message: ${cleaned.take(100)}", e)
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
        _messagesByAgent.update { current -> current.toMutableMap().apply {
                    remove(agentId)
                } }
    }

    private fun addMessageToCache(agentId: String, conversationId: String?, message: AppMessage) {
        if (conversationId != null) {
            _messagesByConversation.update { current -> current.toMutableMap().apply {
                        val existing = get(conversationId) ?: emptyList()
                        put(conversationId, existing + message)
                    } }
        } else {
            _messagesByAgent.update { current -> current.toMutableMap().apply {
                        val existing = get(agentId) ?: emptyList()
                        put(agentId, existing + message)
                    } }
        }
    }

    private fun updateMessagesInCache(agentId: String, conversationId: String?, messages: List<AppMessage>) {
        if (conversationId != null) {
            _messagesByConversation.update { current -> current.toMutableMap().apply {
                        put(conversationId, messages)
                    } }
        } else {
            _messagesByAgent.update { current -> current.toMutableMap().apply {
                        put(agentId, messages)
                    } }
        }
    }
}
