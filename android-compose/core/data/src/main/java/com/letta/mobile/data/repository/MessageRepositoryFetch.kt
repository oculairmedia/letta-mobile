package com.letta.mobile.data.repository

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.mapper.toAppMessages
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.ConversationId

internal data class MessageFetchParams(
    val messageApi: MessageApi,
    val agentId: AgentId,
    val conversationId: ConversationId,
    val targetMessageId: String?,
    val defaultFetchLimit: Int,
    val targetedFetchLimit: Int,
    val maxTargetedFetchPages: Int,
)

internal data class TargetedMessageFetchParams(
    val messageApi: MessageApi,
    val agentId: AgentId,
    val conversationId: ConversationId,
    val targetMessageId: String,
    val targetedFetchLimit: Int,
    val maxTargetedFetchPages: Int,
)

internal object MessageRepositoryFetch {
    suspend fun fetchMessages(params: MessageFetchParams): List<AppMessage> {
        return try {
            if (params.targetMessageId.isNullOrBlank()) {
                fetchRecentMessages(params)
            } else {
                fetchMessagesUntilTarget(
                    TargetedMessageFetchParams(
                        messageApi = params.messageApi,
                        agentId = params.agentId,
                        conversationId = params.conversationId,
                        targetMessageId = params.targetMessageId,
                        targetedFetchLimit = params.targetedFetchLimit,
                        maxTargetedFetchPages = params.maxTargetedFetchPages,
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("MessageRepository", "fetchMessages failed", e)
            emptyList()
        }
    }

    suspend fun fetchMessagesUntilTarget(params: TargetedMessageFetchParams): List<AppMessage> {
        val accumulator = TargetedMessageFetchAccumulator()
        while (accumulator.shouldFetchNextPage(params.maxTargetedFetchPages)) {
            val page = fetchTargetedPage(params, accumulator.afterCursor)
            if (!accumulator.appendPage(page, params)) break
        }
        return accumulator.mergedMessages
    }

    private class TargetedMessageFetchAccumulator {
        var afterCursor: String? = null
            private set
        var mergedMessages: List<AppMessage> = emptyList()
            private set
        private var pagesFetched = 0

        fun shouldFetchNextPage(maxPages: Int): Boolean = pagesFetched < maxPages

        fun appendPage(page: List<AppMessage>, params: TargetedMessageFetchParams): Boolean {
            if (page.isEmpty()) return false
            mergedMessages = mergedMessages + page
            if (containsTargetMessage(mergedMessages, params.targetMessageId)) return false
            if (page.size < params.targetedFetchLimit) return false
            afterCursor = page.lastOrNull()?.id ?: return false
            pagesFetched++
            return true
        }
    }

    suspend fun fetchOlderMessages(
        messageApi: MessageApi,
        irohTimelineTransport: com.letta.mobile.data.timeline.IrohAdminRpcTimelineTransport?,
        agentId: AgentId,
        conversationId: ConversationId,
        beforeMessageId: String,
        olderMessagesPageSize: Int,
    ): List<AppMessage> {
        if (beforeMessageId.isBlank()) return emptyList()

        if (irohTimelineTransport?.shouldUseIroh() == true) {
            return irohTimelineTransport.listOlderConversationMessages(
                conversationId = conversationId.value,
                beforeMessageId = beforeMessageId,
                limit = olderMessagesPageSize,
            ).toAppMessages()
        }

        return messageApi.fetchRecentMessages(
            conversationId = conversationId,
            messageLimit = olderMessagesPageSize,
            beforeMessageId = beforeMessageId,
        ).toAppMessages()
    }

    private suspend fun fetchRecentMessages(params: MessageFetchParams): List<AppMessage> =
        params.messageApi.fetchRecentMessages(
            conversationId = params.conversationId,
            messageLimit = params.defaultFetchLimit,
            beforeMessageId = null,
        ).toAppMessages()

    private suspend fun fetchTargetedPage(
        params: TargetedMessageFetchParams,
        after: String?,
    ): List<AppMessage> =
        params.messageApi.listMessages(
            agentId = params.agentId,
            limit = params.targetedFetchLimit,
            before = null,
            after = after,
            order = "asc",
            conversationId = params.conversationId,
        ).toAppMessages()

    private fun containsTargetMessage(messages: List<AppMessage>, targetMessageId: String): Boolean =
        messages.any { it.id == targetMessageId }
}
