package com.letta.mobile.chat.client

import android.util.Log
import com.letta.mobile.chat.model.ChatConversation
import com.letta.mobile.chat.model.ChatMessage
import com.letta.mobile.chat.model.ChatToolCall
import com.letta.mobile.chat.model.MessageRole
import com.letta.mobile.chat.model.MessageStatus
import com.letta.mobile.chat.model.StreamEvent
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.StreamState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class LettaChatClient(
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val agentId: String,
    initialConversationId: String? = null,
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _conversation = MutableStateFlow<ChatConversation?>(null)
    val conversation: StateFlow<ChatConversation?> = _conversation.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val pendingTools = ConcurrentHashMap<String, ChatToolCall>()
    private val seenMessageIds = ConcurrentHashMap.newKeySet<String>()
    private var activeConversationId: String? = initialConversationId
    private var hasSummary = false

    suspend fun connect() {
        _isLoading.update { true }
        try {
            if (activeConversationId == null) {
                activeConversationId = resolveConversation()
            }
            loadMessages()
        } catch (e: Exception) {
            Log.w(TAG, "Connect failed", e)
            _error.update { e.message }
        } finally {
            _isLoading.update { false }
        }
    }

    suspend fun sendMessage(text: String): Flow<StreamEvent> = flow {
        val userMessage = ChatMessage(
            id = "pending-${System.currentTimeMillis()}",
            role = MessageRole.User,
            content = text,
            timestamp = Instant.now().toString(),
            status = MessageStatus.Sent,
        )
        addMessage(userMessage)
        _isStreaming.update { true }
        _error.update { null }

        emit(StreamEvent.Sending)

        try {
            autoSetSummary(text)

            val convId = activeConversationId
            if (convId == null) {
                emit(StreamEvent.Error("No conversation selected"))
                _isStreaming.update { false }
                return@flow
            }

            messageRepository.sendMessage(agentId, text, convId).collect { state ->
                when (state) {
                    is StreamState.Sending -> emit(StreamEvent.Sending)
                    is StreamState.Streaming -> {
                        val newMessages = state.messages.map { it.toChatMessage() }
                        appendStreamMessages(newMessages)
                        emit(StreamEvent.Streaming(newMessages))
                    }
                    is StreamState.ToolExecution -> {
                        val tool = ChatToolCall(id = state.toolName, name = state.toolName, isPending = true)
                        pendingTools[state.toolName] = tool
                        emit(StreamEvent.ToolExecution(state.toolName))
                    }
                    is StreamState.Complete -> {
                        pendingTools.clear()
                        val newMessages = state.messages.map { it.toChatMessage() }
                        appendStreamMessages(newMessages)
                        _isStreaming.update { false }
                        emit(StreamEvent.Complete(newMessages))
                        reloadFromServer()
                    }
                    is StreamState.Error -> {
                        _isStreaming.update { false }
                        _error.update { state.message }
                        emit(StreamEvent.Error(state.message))
                    }
                }
            }
        } catch (e: Exception) {
            _isStreaming.update { false }
            _error.update { e.message }
            emit(StreamEvent.Error(e.message ?: "Send failed"))
        }
    }

    fun clearError() {
        _error.update { null }
    }

    private suspend fun resolveConversation(): String? {
        try {
            conversationRepository.refreshConversations(agentId)
            val conversations = conversationRepository.getConversations(agentId).first()
            val mostRecent = conversations
                .sortedByDescending { it.lastMessageAt ?: it.createdAt ?: "" }
                .firstOrNull()
            if (mostRecent != null) {
                _conversation.update {
                    ChatConversation(
                        id = mostRecent.id,
                        agentId = mostRecent.agentId,
                        summary = mostRecent.summary,
                        lastMessageAt = mostRecent.lastMessageAt,
                        createdAt = mostRecent.createdAt,
                    )
                }
                Log.d(TAG, "Resolved to conversation: ${mostRecent.id}")
                return mostRecent.id
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve conversation", e)
        }
        return null
    }

    private suspend fun loadMessages() {
        try {
            val appMessages = messageRepository.fetchMessages(agentId, activeConversationId)
            val chatMessages = appMessages.map { it.toChatMessage() }
            _messages.update { chatMessages }
            seenMessageIds.clear()
            chatMessages.forEach { seenMessageIds.add(it.id) }
            hasSummary = chatMessages.isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load messages", e)
        }
    }

    private fun addMessage(message: ChatMessage) {
        if (seenMessageIds.add(message.id)) {
            _messages.update { current -> current + message }
        }
    }

    private fun appendStreamMessages(newMessages: List<ChatMessage>) {
        _messages.update { current ->
            val existingIds = current.map { it.id }.toSet()
            val deduped = newMessages.filter { it.id !in existingIds }
            current + deduped
        }
        newMessages.forEach { seenMessageIds.add(it.id) }
    }

    private suspend fun autoSetSummary(text: String) {
        if (!hasSummary && activeConversationId != null) {
            try {
                val summary = text.take(80).let { if (text.length > 80) "$it\u2026" else it }
                conversationRepository.updateConversation(activeConversationId!!, agentId, summary)
                hasSummary = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set summary", e)
            }
        }
    }

    private suspend fun reloadFromServer() {
        try {
            val appMessages = messageRepository.fetchMessages(agentId, activeConversationId)
            val chatMessages = appMessages.map { it.toChatMessage() }
            if (chatMessages.isNotEmpty()) {
                _messages.update { chatMessages }
                seenMessageIds.clear()
                chatMessages.forEach { seenMessageIds.add(it.id) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Silent reload failed", e)
        }
    }

    private fun AppMessage.toChatMessage(): ChatMessage {
        val role = when (messageType) {
            MessageType.USER -> MessageRole.User
            MessageType.ASSISTANT -> MessageRole.Assistant
            MessageType.REASONING -> MessageRole.Assistant
            MessageType.TOOL_CALL -> MessageRole.Tool
            MessageType.TOOL_RETURN -> MessageRole.Tool
        }
        val name = toolName
        val toolCalls = if (messageType == MessageType.TOOL_CALL && name != null) {
            listOf(ChatToolCall(name = name, arguments = content, result = null))
        } else null

        return ChatMessage(
            id = id,
            role = role,
            content = content,
            timestamp = date.toString(),
            isReasoning = messageType == MessageType.REASONING,
            toolCalls = toolCalls,
        )
    }

    companion object {
        private const val TAG = "LettaChatClient"
    }
}
