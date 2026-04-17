package com.letta.mobile.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.mapper.toAppMessages
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.ApprovalCreate
import com.letta.mobile.data.model.ApprovalResult
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
import com.letta.mobile.data.stream.Utf8LineReader
import com.letta.mobile.domain.MessageProcessor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Instant
import java.util.UUID
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
    companion object {
        /** Number of messages to display on initial chat load */
        const val INITIAL_FETCH_LIMIT = 30
        /** Number of messages to load when scrolling up for history */
        const val OLDER_MESSAGES_PAGE_SIZE = 20
        const val DEFAULT_FETCH_LIMIT = INITIAL_FETCH_LIMIT
        const val TARGETED_FETCH_LIMIT = 100
        const val MAX_TARGETED_FETCH_PAGES = 20
    }

    private val json = Json { ignoreUnknownKeys = true }

    // ========== SINGLE SOURCE OF TRUTH ARCHITECTURE ==========
    // Server state: immutable snapshots from API (replace entirely on fetch)
    private val _serverMessages = MutableStateFlow<Map<String, List<AppMessage>>>(emptyMap())
    
    // Pending state: local optimistic messages waiting for server confirmation
    private val _pendingMessages = MutableStateFlow<Map<String, List<AppMessage>>>(emptyMap())
    
    // Streaming state: messages being received via SSE (cleared when stream completes)
    private val _streamingMessages = MutableStateFlow<Map<String, List<AppMessage>>>(emptyMap())

    /**
     * Get display messages for a conversation.
     * Merges server + pending + streaming states and sorts chronologically.
     * This is the ONLY place where messages are combined and sorted.
     */
    fun getDisplayMessages(conversationId: String): List<AppMessage> {
        val server = _serverMessages.value[conversationId] ?: emptyList()
        val pending = _pendingMessages.value[conversationId] ?: emptyList()
        val streaming = _streamingMessages.value[conversationId] ?: emptyList()
        
        // Server messages are already in correct chronological order from API
        // (sorted by date with otid as tiebreaker for same-date messages within a run)
        
        val serverIds = server.mapTo(mutableSetOf()) { it.id }
        val serverHashes = server.mapTo(mutableSetOf()) { it.contentHash() }
        
        // Pending messages that haven't been confirmed by server (by content match)
        val unconfirmedPending = pending.filter { pendingMsg ->
            pendingMsg.contentHash() !in serverHashes
        }
        
        // Streaming messages that aren't already in server state
        val newStreaming = streaming.filter { it.id !in serverIds }
        
        // Merge: server (in order) + pending (newest user actions) + streaming (incoming)
        return server + unconfirmedPending + newStreaming
    }

    // Legacy accessor - redirects to new architecture
    @Deprecated("Use getDisplayMessages() instead", ReplaceWith("getDisplayMessages(conversationId)"))
    private val _messagesByConversation: MutableStateFlow<Map<String, List<AppMessage>>>
        get() = _serverMessages  // Backwards compatibility during migration

    // Track last synced message ID per conversation for incremental fetches
    private val _lastSyncedMessageId = MutableStateFlow<Map<String, String>>(emptyMap())

    fun getLastSyncedMessageId(conversationId: String): String? =
        _lastSyncedMessageId.value[conversationId]

    private fun setLastSyncedMessageId(conversationId: String, messageId: String) {
        _lastSyncedMessageId.update { it + (conversationId to messageId) }
    }

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

    suspend fun fetchMessages(
        agentId: String,
        conversationId: String,
        targetMessageId: String? = null,
    ): List<AppMessage> {
        // Use the SDK's fetchRecentMessages which handles the Letta API's run-based
        // limit semantics and provides actual message-count limiting.
        return try {
            val appMessages = if (targetMessageId.isNullOrBlank()) {
                // Fetch recent messages using the SDK method that handles over-fetching
                messageApi.fetchRecentMessages(
                    conversationId = conversationId,
                    messageLimit = DEFAULT_FETCH_LIMIT,
                    beforeMessageId = null,
                ).toAppMessages()
            } else {
                fetchMessagesUntilTarget(
                    agentId = agentId,
                    conversationId = conversationId,
                    targetMessageId = targetMessageId,
                )
            }

            // Replace server state entirely (immutable snapshot)
            _serverMessages.update { current ->
                current.toMutableMap().apply {
                    put(conversationId, appMessages)
                }
            }
            
            // Clear pending messages that are now confirmed by server
            clearConfirmedPendingMessages(conversationId, appMessages)
            
            // Clear streaming state (fetch means stream is done)
            _streamingMessages.update { current ->
                current.toMutableMap().apply { remove(conversationId) }
            }

            // Track last message ID for incremental sync
            appMessages.lastOrNull()?.id?.let { lastId ->
                android.util.Log.d("MessageRepository", "fetchMessages: setting lastSyncedMessageId=$lastId for conv=$conversationId (${appMessages.size} messages)")
                setLastSyncedMessageId(conversationId, lastId)
            }

            getDisplayMessages(conversationId)
        } catch (e: Exception) {
            // Return display messages on error
            getDisplayMessages(conversationId)
        }
    }
    
    /**
     * Clear pending messages that have been confirmed by server (matched by content hash).
     */
    private fun clearConfirmedPendingMessages(conversationId: String, serverMessages: List<AppMessage>) {
        val serverHashes = serverMessages.mapTo(mutableSetOf()) { it.contentHash() }
        _pendingMessages.update { current ->
            current.toMutableMap().apply {
                val pending = get(conversationId) ?: return@apply
                val stillPending = pending.filter { it.contentHash() !in serverHashes }
                if (stillPending.isEmpty()) {
                    remove(conversationId)
                } else {
                    put(conversationId, stillPending)
                }
            }
        }
    }

    fun getMessages(agentId: String, conversationId: String): Flow<List<AppMessage>> = flow {
        val messages = fetchMessages(agentId, conversationId)
        emit(messages)
    }

    /**
     * Check for new messages from server that we don't have locally.
     * Uses cursor-based pagination to only fetch messages after our last known message.
     * Returns the new messages found (empty if none or on error).
     */
    suspend fun checkForNewMessages(
        agentId: String,
        conversationId: String,
    ): List<AppMessage> {
        val lastKnownId = getLastSyncedMessageId(conversationId)
        android.util.Log.d("MessageRepository", "checkForNewMessages: conv=$conversationId, lastKnownId=$lastKnownId")

        return try {
            // Use SDK method that handles run-based limit semantics
            val newMessages = messageApi.fetchMessagesAfter(
                conversationId = conversationId,
                afterMessageId = lastKnownId,
                messageLimit = 50,
            ).toAppMessages()

            android.util.Log.d("MessageRepository", "checkForNewMessages: fetched ${newMessages.size} from API")

            if (newMessages.isNotEmpty()) {
                // Append to server state (no sorting needed - getDisplayMessages handles it)
                _serverMessages.update { current ->
                    current.toMutableMap().apply {
                        val existing = get(conversationId) ?: emptyList()
                        val existingIds = existing.mapTo(mutableSetOf()) { it.id }
                        val actuallyNew = newMessages.filterNot { it.id in existingIds }
                        android.util.Log.d("MessageRepository", "checkForNewMessages: existing=${existing.size}, actuallyNew=${actuallyNew.size}")
                        put(conversationId, existing + actuallyNew)
                    }
                }
                
                // Clear any pending messages now confirmed
                clearConfirmedPendingMessages(conversationId, newMessages)
                
                // Update sync marker
                newMessages.lastOrNull()?.id?.let { lastId ->
                    android.util.Log.d("MessageRepository", "checkForNewMessages: updating lastSyncedMessageId to $lastId")
                    setLastSyncedMessageId(conversationId, lastId)
                }
            }

            newMessages
        } catch (e: Exception) {
            android.util.Log.e("MessageRepository", "checkForNewMessages failed", e)
            emptyList()
        }
    }

    private suspend fun fetchMessagesUntilTarget(
        agentId: String,
        conversationId: String,
        targetMessageId: String,
    ): List<AppMessage> {
        var after: String? = null
        var pagesFetched = 0
        var mergedMessages: List<AppMessage> = emptyList()

        while (pagesFetched < MAX_TARGETED_FETCH_PAGES) {
            val page = messageApi.listMessages(
                agentId = agentId,
                limit = TARGETED_FETCH_LIMIT,
                before = null,
                after = after,
                order = "asc",
                conversationId = conversationId,
            )

            if (page.isEmpty()) break

            // Simple append - pages come in asc order
            mergedMessages = mergedMessages + page.toAppMessages()
            if (mergedMessages.any { it.id == targetMessageId }) {
                return mergedMessages
            }

            if (page.size < TARGETED_FETCH_LIMIT) break

            after = page.lastOrNull()?.id ?: break
            pagesFetched++
        }

        return mergedMessages
    }

    suspend fun fetchOlderMessages(
        agentId: String,
        conversationId: String,
        beforeMessageId: String,
    ): List<AppMessage> {
        if (beforeMessageId.isBlank()) return emptyList()

        // Use SDK method that handles run-based limit semantics
        val olderMessages = messageApi.fetchRecentMessages(
            conversationId = conversationId,
            messageLimit = OLDER_MESSAGES_PAGE_SIZE,
            beforeMessageId = beforeMessageId,
        ).toAppMessages()

        if (olderMessages.isNotEmpty()) {
            // PREPEND older messages to server state (they come before existing)
            _serverMessages.update { current ->
                current.toMutableMap().apply {
                    val existing = get(conversationId) ?: emptyList()
                    val existingIds = existing.mapTo(mutableSetOf()) { it.id }
                    val newOlder = olderMessages.filterNot { it.id in existingIds }
                    put(conversationId, newOlder + existing)  // Older messages go FIRST
                }
            }
        }

        return olderMessages
    }

    /**
     * Get display messages (merged server + pending + streaming, sorted).
     * This is the primary accessor for UI consumption.
     */
    fun getCachedMessages(conversationId: String): List<AppMessage> {
        return getDisplayMessages(conversationId)
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
                    eventData?.forEach { (key, value) ->
                        val rendered = (value as? JsonPrimitive)?.content ?: value.toString()
                        add(key to rendered)
                    }
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
        conversationId: String,
    ): Flow<StreamState> = flow {
        emit(StreamState.Sending)

        val localId = UUID.randomUUID().toString()
        val optimisticMessage = AppMessage(
            id = "pending-$localId",
            date = Instant.now(),
            messageType = MessageType.USER,
            content = text,
            isPending = true,
            localId = localId,
        )

        // Add to PENDING state (not server state)
        addPendingMessage(conversationId, optimisticMessage)

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

            val streamChannel = messageApi.sendConversationMessage(conversationId, request)
            val lineReader = Utf8LineReader(streamChannel)

            val messages = mutableListOf<AppMessage>()
            val messageFlow = flow {
                while (true) {
                    val line = lineReader.readLine() ?: break
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

            // Add streamed messages to streaming state BEFORE emitting Complete
            // so consumers reading getCachedMessages() will see them immediately.
            _streamingMessages.update { current ->
                current.toMutableMap().apply {
                    put(conversationId, messages)
                }
            }

            emit(StreamState.Complete(messages))

            // Background fetch happens later - don't block stream completion.
            // The pending user message + streaming response remain in the merged display
            // until the next poll cycle picks up the server-confirmed versions.
            // This avoids race conditions where the server fetch returns stale data
            // and overwrites the pending+streaming we just emitted.
            try {
                val serverMessages = messageApi.fetchRecentMessages(
                    conversationId = conversationId,
                    messageLimit = DEFAULT_FETCH_LIMIT,
                    beforeMessageId = null,
                ).toAppMessages()
                
                // Only update server state if the fetch actually contains our streamed message IDs
                // (i.e., the server has actually persisted them). Otherwise keep streaming state.
                val streamedIds = messages.mapTo(mutableSetOf()) { it.id }
                val serverIds = serverMessages.mapTo(mutableSetOf()) { it.id }
                val serverHasStreamedMessages = streamedIds.isEmpty() || streamedIds.any { it in serverIds }
                
                if (serverHasStreamedMessages) {
                    // Server has the messages - safe to update server state and clear streaming/pending
                    _serverMessages.update { current ->
                        current.toMutableMap().apply {
                            put(conversationId, serverMessages)
                        }
                    }
                    
                    _pendingMessages.update { current ->
                        current.toMutableMap().apply { remove(conversationId) }
                    }
                    
                    _streamingMessages.update { current ->
                        current.toMutableMap().apply { remove(conversationId) }
                    }
                    
                    serverMessages.lastOrNull()?.id?.let { setLastSyncedMessageId(conversationId, it) }
                } else {
                    android.util.Log.d("MessageRepository", "Server doesn't have streamed messages yet, keeping streaming state")
                }
            } catch (e: Exception) {
                android.util.Log.w("MessageRepository", "Background sync after stream failed", e)
            }

        } catch (e: Exception) {
            emit(StreamState.Error(e.message ?: "Unknown error"))
        }
    }

    suspend fun submitApproval(
        conversationId: String,
        approvalRequestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String? = null,
    ) {
        val request = MessageCreateRequest(
            messages = listOf(
                json.encodeToJsonElement(
                    ApprovalCreate.serializer(),
                    ApprovalCreate(
                        approvals = toolCallIds.map { toolCallId ->
                            ApprovalResult(
                                toolCallId = toolCallId,
                                approve = approve,
                                reason = reason?.takeIf { it.isNotBlank() },
                                status = if (approve) "approved" else "rejected",
                            )
                        },
                        approve = approve,
                        approvalRequestId = approvalRequestId,
                        reason = reason?.takeIf { it.isNotBlank() },
                    )
                )
            ),
            streaming = false,
        )

        messageApi.sendConversationMessage(conversationId, request)
    }

    suspend fun resetMessages(agentId: String) {
        messageApi.resetMessages(agentId)
        // Clear all states
        _serverMessages.update { emptyMap() }
        _pendingMessages.update { emptyMap() }
        _streamingMessages.update { emptyMap() }
    }

    suspend fun resetMessages(agentId: String, conversationId: String) {
        messageApi.resetMessages(agentId)
        // Clear all states for this conversation
        _serverMessages.update { current ->
            current.toMutableMap().apply { remove(conversationId) }
        }
        _pendingMessages.update { current ->
            current.toMutableMap().apply { remove(conversationId) }
        }
        _streamingMessages.update { current ->
            current.toMutableMap().apply { remove(conversationId) }
        }
    }

    /**
     * Add a message to the PENDING state (optimistic local message).
     */
    private fun addPendingMessage(conversationId: String, message: AppMessage) {
        _pendingMessages.update { current ->
            current.toMutableMap().apply {
                val existing = get(conversationId) ?: emptyList()
                put(conversationId, existing + message)
            }
        }
    }

    // ========== LEGACY METHODS (kept for backwards compat, will be removed) ==========
    
    @Deprecated("Use getDisplayMessages() - server state is now immutable")
    private fun addMessageToCache(conversationId: String, message: AppMessage) {
        // Redirect to pending state
        addPendingMessage(conversationId, message)
    }

    @Deprecated("Use streaming state instead")
    private fun mergeMessagesIntoCache(conversationId: String, messages: List<AppMessage>) {
        // No-op - streaming messages are now handled via _streamingMessages
    }

    @Deprecated("Server state is now append-only via fetchOlderMessages")
    private fun prependMessagesIntoCache(conversationId: String, messages: List<AppMessage>) {
        // No-op - older messages are added directly to _serverMessages in fetchOlderMessages
    }

}
