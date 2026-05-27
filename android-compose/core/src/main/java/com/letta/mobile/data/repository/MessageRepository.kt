package com.letta.mobile.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.mapper.toAppMessages
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.ApprovalCreate
import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.ApprovalSubmission
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.BatchMessagesResponse
import com.letta.mobile.data.model.CreateBatchMessagesRequest
import com.letta.mobile.data.model.EventMessage
import com.letta.mobile.data.model.HiddenReasoningMessage
import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.MessageSearchResult
import com.letta.mobile.data.model.PingMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.StopReason
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UnknownMessage
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.paging.MessagePagingSource
import com.letta.mobile.data.repository.api.IConversationInspectorMessageRepository
import com.letta.mobile.data.repository.api.IMessageRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stateless HTTP helper for non-streaming message endpoints.
 *
 * `MessageRepository` is deliberately **not** the chat timeline source of
 * truth. Streaming sends, live sync, optimistic/local writes, reconciliation,
 * and visible conversation state are owned by
 * [com.letta.mobile.data.timeline.TimelineRepository]. Keep chat screens wired
 * to the timeline first; use this repository only for bounded HTTP operations
 * that augment or administer that state:
 *
 * - older-message pagination that is merged back through the timeline observer
 * - message search
 * - approvals and cancellations
 * - batch-message administration
 * - agent-level reset
 * - conversation inspector/debug reads
 *
 * Do not reintroduce an in-memory message cache, streaming send entry point,
 * or conversation-state Flow here. Phase 5 of the Timeline migration removed
 * the old `_pendingMessages`, `_streamingMessages`, `_serverMessages`, and
 * legacy `sendMessage()` responsibilities so there is only one live timeline
 * owner.
 */
@Singleton
open class MessageRepository @Inject constructor(
    private val messageApi: MessageApi,
) : IMessageRepository, IConversationInspectorMessageRepository {
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

    override fun getMessagesPaged(agentId: String?, conversationId: String?): Flow<PagingData<AppMessage>> {
        return Pager(
            config = PagingConfig(
                pageSize = MessagePagingSource.PAGE_SIZE,
                enablePlaceholders = false,
                initialLoadSize = MessagePagingSource.PAGE_SIZE
            ),
            pagingSourceFactory = { MessagePagingSource(messageApi, agentId, conversationId) }
        ).flow
    }

    /**
     * Fetch a page of recent messages from the server.
     *
     * Stateless — returns the API response and does not cache. For live sync,
     * use [com.letta.mobile.data.timeline.TimelineRepository] instead.
     */
    override suspend fun fetchMessages(
        agentId: String,
        conversationId: String,
        targetMessageId: String?,
    ): List<AppMessage> {
        return try {
            if (targetMessageId.isNullOrBlank()) {
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
        } catch (e: Exception) {
            android.util.Log.w("MessageRepository", "fetchMessages failed", e)
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

    override suspend fun fetchOlderMessages(
        agentId: String,
        conversationId: String,
        beforeMessageId: String,
    ): List<AppMessage> {
        if (beforeMessageId.isBlank()) return emptyList()

        return messageApi.fetchRecentMessages(
            conversationId = conversationId,
            messageLimit = OLDER_MESSAGES_PAGE_SIZE,
            beforeMessageId = beforeMessageId,
        ).toAppMessages()
    }

    override suspend fun cancelMessage(agentId: String, runIds: List<String>?): Map<String, String> {
        return messageApi.cancelMessage(agentId = agentId, runIds = runIds)
    }

    override suspend fun searchMessages(request: MessageSearchRequest): List<MessageSearchResult> {
        return messageApi.searchMessages(request)
    }

    override suspend fun createBatch(request: CreateBatchMessagesRequest): Job {
        return messageApi.createBatch(request)
    }

    override suspend fun retrieveBatch(batchId: String): Job {
        return messageApi.retrieveBatch(batchId)
    }

    override suspend fun listBatches(): List<Job> {
        return messageApi.listBatches(limit = 1000)
    }

    override suspend fun listBatchMessages(batchId: String, agentId: String?): BatchMessagesResponse {
        return messageApi.listBatchMessages(batchId = batchId, limit = 1000, agentId = agentId)
    }

    override suspend fun cancelBatch(batchId: String) {
        messageApi.cancelBatch(batchId)
    }

    override suspend fun fetchConversationInspectorMessages(conversationId: String): List<ConversationInspectorMessage> {
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
            is ErrorMessage -> {
                val extra = buildList<Pair<String, String>> {
                    add("Error Text" to text)
                    code?.let { add("Code" to it) }
                }
                ConversationInspectorMessage(
                    id = id,
                    messageType = messageType,
                    date = date,
                    runId = runId,
                    stepId = stepId,
                    otid = otid,
                    summary = "Error: ${text.take(120)}",
                    detailLines = baseDetails + extra,
                )
            }
            is StopReason -> {
                val stopReason = this as StopReason
                ConversationInspectorMessage(
                    id = id,
                    messageType = messageType,
                    date = date,
                    runId = runId,
                    stepId = stepId,
                    otid = otid,
                    summary = "Stop: ${stopReason.reason}",
                    detailLines = baseDetails + listOf("Reason" to stopReason.reason),
                )
            }
            is UsageStatistics -> {
                val usage = this as UsageStatistics
                ConversationInspectorMessage(
                    id = id,
                    messageType = messageType,
                    date = date,
                    runId = runId,
                    stepId = stepId,
                    otid = otid,
                    summary = "Usage: ${usage.totalTokens ?: 0} tokens",
                    detailLines = baseDetails + buildList {
                        usage.promptTokens?.let { add("Prompt Tokens" to it.toString()) }
                        usage.completionTokens?.let { add("Completion Tokens" to it.toString()) }
                        usage.totalTokens?.let { add("Total Tokens" to it.toString()) }
                        usage.stepCount?.let { add("Step Count" to it.toString()) }
                    },
                )
            }
        }
    }

    override suspend fun submitApproval(
        agentId: String,
        approvalRequestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String?,
    ) {
        val trimmedReason = reason?.takeIf { it.isNotBlank() }
        val request = MessageCreateRequest(
            messages = listOf(
                json.encodeToJsonElement(
                    ApprovalCreate.serializer(),
                    ApprovalCreate(
                        approvals = toolCallIds.map { toolCallId ->
                            ApprovalSubmission(
                                toolCallId = toolCallId,
                                approve = approve,
                                reason = trimmedReason,
                            )
                        },
                        approve = approve,
                        approvalRequestId = approvalRequestId,
                        reason = trimmedReason,
                    )
                )
            ),
            streaming = false,
        )

        messageApi.sendMessage(agentId, request)
    }

    override suspend fun resetMessages(agentId: String) {
        messageApi.resetMessages(agentId)
    }
}
