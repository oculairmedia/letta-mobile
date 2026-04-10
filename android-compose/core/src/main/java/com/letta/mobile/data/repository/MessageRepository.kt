package com.letta.mobile.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.BatchMessagesResponse
import com.letta.mobile.data.model.CreateBatchMessagesRequest
import com.letta.mobile.data.model.EventMessage
import com.letta.mobile.data.model.HiddenReasoningMessage
import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreate
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.MessageSearchResult
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.model.PingMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UnknownMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.paging.MessagePagingSource
import com.letta.mobile.domain.MessageProcessor
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@androidx.compose.runtime.Immutable
data class ConversationInspectorMessage(
    val id: String,
    val messageType: String,
    val date: String?,
    val runId: String?,
    val stepId: String?,
    val otid: String?,
    val summary: String,
    val detailLines: List<Pair<String, String>> = emptyList(),
)

@Singleton
open class MessageRepository @Inject constructor(
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
                messageApi.listConversationMessages(conversationId, limit = 100, order = "asc")
            } else {
                messageApi.listMessages(agentId, limit = 100, order = "asc")
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

    suspend fun cancelMessage(agentId: String, runIds: List<String>? = null): Map<String, String> {
        return messageApi.cancelMessage(agentId = agentId, runIds = runIds)
    }

    suspend fun searchMessages(request: MessageSearchRequest): List<MessageSearchResult> {
        return messageApi.searchMessages(request)
    }

    suspend fun createBatch(request: CreateBatchMessagesRequest): Job {
        return messageApi.createBatch(request)
    }

    suspend fun retrieveBatch(batchId: String): Job {
        return messageApi.retrieveBatch(batchId)
    }

    suspend fun listBatches(): List<Job> {
        return messageApi.listBatches(limit = 1000)
    }

    suspend fun listBatchMessages(batchId: String, agentId: String? = null): BatchMessagesResponse {
        return messageApi.listBatchMessages(batchId = batchId, limit = 1000, agentId = agentId)
    }

    suspend fun cancelBatch(batchId: String) {
        messageApi.cancelBatch(batchId)
    }

    open suspend fun fetchConversationInspectorMessages(conversationId: String): List<ConversationInspectorMessage> {
        return messageApi.listConversationMessages(conversationId, limit = 200, order = "asc")
            .map { it.toInspectorMessage() }
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
                content = effectiveToolCalls.firstOrNull()?.arguments.orEmpty(),
                toolName = effectiveToolCalls.firstOrNull()?.name,
                toolCallId = effectiveToolCalls.firstOrNull()?.effectiveId
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

    private fun LettaMessage.toInspectorMessage(): ConversationInspectorMessage {
        val baseDetails = buildList {
            date?.let { add("Date" to it) }
            runId?.let { add("Run ID" to it) }
            stepId?.let { add("Step ID" to it) }
            otid?.let { add("OTID" to it) }
        }
        return when (this) {
            is UserMessage -> ConversationInspectorMessage(
                id = id,
                messageType = messageType,
                date = date,
                runId = runId,
                stepId = stepId,
                otid = otid,
                summary = content.ifBlank { "User message" },
                detailLines = baseDetails + listOfNotNull(senderId?.let { "Sender ID" to it }),
            )
            is AssistantMessage -> ConversationInspectorMessage(
                id = id,
                messageType = messageType,
                date = date,
                runId = runId,
                stepId = stepId,
                otid = otid,
                summary = content.ifBlank { "Assistant message" },
                detailLines = baseDetails + listOfNotNull(senderId?.let { "Sender ID" to it }),
            )
            is ReasoningMessage -> ConversationInspectorMessage(
                id = id,
                messageType = messageType,
                date = date,
                runId = runId,
                stepId = stepId,
                otid = otid,
                summary = reasoning,
                detailLines = baseDetails + listOfNotNull(senderId?.let { "Sender ID" to it }),
            )
            is ToolCallMessage -> ConversationInspectorMessage(
                id = id,
                messageType = messageType,
                date = date,
                runId = runId,
                stepId = stepId,
                otid = otid,
                summary = effectiveToolCalls.firstOrNull()?.name ?: "Tool call",
                detailLines = baseDetails + listOf(
                    "Tool Call ID" to (effectiveToolCalls.firstOrNull()?.effectiveId ?: ""),
                    "Arguments" to (effectiveToolCalls.firstOrNull()?.arguments ?: ""),
                ) + listOfNotNull(senderId?.let { "Sender ID" to it }),
            )
            is ToolReturnMessage -> ConversationInspectorMessage(
                id = id,
                messageType = messageType,
                date = date,
                runId = runId,
                stepId = stepId,
                otid = otid,
                summary = toolReturn.funcResponse ?: toolReturn.status,
                detailLines = baseDetails + buildList {
                    add("Tool Call ID" to toolReturn.toolCallId)
                    add("Status" to toolReturn.status)
                    toolReturn.funcResponse?.let { add("Function Response" to it) }
                    toolReturn.stdout?.takeIf { it.isNotEmpty() }?.let { add("Stdout" to it.joinToString("\n")) }
                    toolReturn.stderr?.takeIf { it.isNotEmpty() }?.let { add("Stderr" to it.joinToString("\n")) }
                    senderId?.let { add("Sender ID" to it) }
                },
            )
            is ApprovalRequestMessage -> ConversationInspectorMessage(
                id = id,
                messageType = messageType,
                date = date,
                runId = runId,
                stepId = stepId,
                otid = otid,
                summary = "Approval request",
                detailLines = baseDetails + buildList {
                    add("Tool Call Count" to effectiveToolCalls.size.toString())
                    effectiveToolCalls.forEachIndexed { index, toolCall ->
                        add("Tool ${index + 1}" to "${toolCall.name}: ${toolCall.arguments}")
                    }
                    senderId?.let { add("Sender ID" to it) }
                },
            )
            is ApprovalResponseMessage -> ConversationInspectorMessage(
                id = id,
                messageType = messageType,
                date = date,
                runId = runId,
                stepId = stepId,
                otid = otid,
                summary = "Approval response",
                detailLines = baseDetails + buildList {
                    add("Approval Count" to (approvals?.size ?: 0).toString())
                    approve?.let { add("Approved" to it.toString()) }
                    approvalRequestId?.let { add("Approval Request ID" to it) }
                    reason?.let { add("Reason" to it) }
                    approvals?.forEachIndexed { index, approval ->
                        add(
                            "Approval ${index + 1}" to listOfNotNull(
                                approval.status,
                                approval.type,
                                approval.toolCallId,
                                approval.toolReturn,
                                approval.approve?.toString(),
                                approval.reason,
                            ).joinToString(" • ")
                        )
                    }
                    senderId?.let { add("Sender ID" to it) }
                },
            )
            is HiddenReasoningMessage -> ConversationInspectorMessage(
                id = id,
                messageType = messageType,
                date = date,
                runId = runId,
                stepId = stepId,
                otid = otid,
                summary = hiddenReasoning ?: state,
                detailLines = baseDetails + buildList {
                    add("State" to state)
                    hiddenReasoning?.let { add("Hidden Reasoning" to it) }
                    senderId?.let { add("Sender ID" to it) }
                },
            )
            is EventMessage -> ConversationInspectorMessage(
                id = id,
                messageType = messageType,
                date = date,
                runId = runId,
                stepId = stepId,
                otid = otid,
                summary = eventType,
                detailLines = baseDetails + buildList {
                    add("Event Type" to eventType)
                    eventData?.forEach { (key, value) -> add(key to value.toString()) }
                    senderId?.let { add("Sender ID" to it) }
                },
            )
            is SystemMessage -> ConversationInspectorMessage(
                id = id,
                messageType = messageType,
                date = date,
                runId = runId,
                stepId = stepId,
                otid = otid,
                summary = content.ifBlank { "System message" },
                detailLines = baseDetails + listOfNotNull(senderId?.let { "Sender ID" to it }),
            )
            is PingMessage -> ConversationInspectorMessage(
                id = id,
                messageType = messageType,
                date = date,
                runId = runId,
                stepId = stepId,
                otid = otid,
                summary = "Ping",
                detailLines = baseDetails,
            )
            is UnknownMessage -> ConversationInspectorMessage(
                id = id,
                messageType = messageType,
                date = date,
                runId = runId,
                stepId = stepId,
                otid = otid,
                summary = "Unknown message type",
                detailLines = baseDetails,
            )
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
                    json.encodeToJsonElement(
                        MessageCreate.serializer(),
                        MessageCreate(
                            role = "user",
                            content = JsonPrimitive(text)
                        )
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

            mergeMessagesIntoCache(agentId, conversationId, messages)

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

    private fun mergeMessagesIntoCache(agentId: String, conversationId: String?, messages: List<AppMessage>) {
        if (conversationId != null) {
            _messagesByConversation.update { current -> current.toMutableMap().apply {
                        val existing = get(conversationId) ?: emptyList()
                        put(conversationId, mergeMessageLists(existing, messages))
                    } }
        } else {
            _messagesByAgent.update { current -> current.toMutableMap().apply {
                        val existing = get(agentId) ?: emptyList()
                        put(agentId, mergeMessageLists(existing, messages))
                    } }
        }
    }

    private fun mergeMessageLists(existing: List<AppMessage>, incoming: List<AppMessage>): List<AppMessage> {
        val merged = LinkedHashMap<String, AppMessage>()
        existing.forEach { merged[it.id] = it }
        incoming.forEach { merged[it.id] = it }
        return merged.values.toList()
    }
}
