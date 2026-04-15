package com.letta.mobile.chat.client

import android.util.Log
import com.letta.mobile.chat.model.ChatConversation
import com.letta.mobile.chat.model.ChatMessage
import com.letta.mobile.chat.model.ChatToolCall
import com.letta.mobile.chat.model.MessageRole
import com.letta.mobile.chat.model.MessageStatus
import com.letta.mobile.chat.model.StreamEvent
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.repository.ConversationManager
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
    private val conversationManager: ConversationManager,
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
    private val activeConversationId: String?
        get() = conversationManager.getActiveConversationId(agentId)
    private var hasSummary = false

    init {
        initialConversationId
            ?.takeIf { it.isNotBlank() }
            ?.let { conversationManager.setActiveConversation(agentId, it) }
    }

    suspend fun connect() {
        _isLoading.update { true }
        try {
            if (activeConversationId == null) {
                conversationManager.resolveAndSetActiveConversation(agentId)
            }
            syncConversation()
            loadMessages()
        } catch (e: Exception) {
            logWarning("Connect failed", e)
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

    private suspend fun loadMessages() {
        try {
            val appMessages = messageRepository.fetchMessages(agentId, activeConversationId)
            val chatMessages = appMessages.map { it.toChatMessage() }
            _messages.update { chatMessages }
            seenMessageIds.clear()
            chatMessages.forEach { seenMessageIds.add(it.id) }
            hasSummary = chatMessages.isNotEmpty()
        } catch (e: Exception) {
            logWarning("Failed to load messages", e)
        }
    }

    private suspend fun syncConversation() {
        val conversationId = activeConversationId
        if (conversationId == null) {
            _conversation.update { null }
            return
        }

        val conversation = conversationRepository.getCachedConversations(agentId)
            .firstOrNull { it.id == conversationId }
            ?: runCatching { conversationRepository.getConversation(conversationId) }.getOrNull()

        _conversation.update { conversation?.toChatConversation() }
        if (conversation != null) {
            logDebug("Resolved to conversation: ${conversation.id}")
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
                logWarning("Failed to set summary", e)
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
            logWarning("Silent reload failed", e)
        }
    }

    private fun logDebug(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun logWarning(message: String, throwable: Throwable) {
        runCatching { Log.w(TAG, message, throwable) }
    }

    private fun AppMessage.toChatMessage(): ChatMessage {
        val role = when (messageType) {
            MessageType.USER -> MessageRole.User
            MessageType.ASSISTANT -> MessageRole.Assistant
            MessageType.REASONING -> MessageRole.Assistant
            MessageType.TOOL_CALL -> MessageRole.Tool
            MessageType.TOOL_RETURN -> MessageRole.Tool
            MessageType.APPROVAL_REQUEST -> MessageRole.Assistant
            MessageType.APPROVAL_RESPONSE -> MessageRole.User
        }
        val name = toolName
        val toolCalls = when {
            messageType == MessageType.TOOL_CALL && name != null -> {
                listOf(ChatToolCall(name = name, arguments = content, result = null))
            }
            messageType == MessageType.TOOL_RETURN && name != null -> {
                listOf(ChatToolCall(name = name, arguments = "", result = content))
            }
            else -> null
        }
        val displayContent = when {
            messageType == MessageType.TOOL_CALL && toolCalls != null -> ""
            messageType == MessageType.TOOL_RETURN && toolCalls != null -> ""
            else -> content
        }

        return ChatMessage(
            id = id,
            role = role,
            content = displayContent,
            timestamp = date.toString(),
            isReasoning = messageType == MessageType.REASONING,
            toolCalls = toolCalls,
        )
    }

    private fun Conversation.toChatConversation(): ChatConversation {
        return ChatConversation(
            id = id,
            agentId = agentId,
            summary = summary,
            lastMessageAt = lastMessageAt,
            createdAt = createdAt,
        )
    }

    companion object {
        private const val TAG = "LettaChatClient"
    }
}
