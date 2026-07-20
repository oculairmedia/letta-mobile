package com.letta.mobile.data.repository

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.mapper.toAppMessages
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.ConversationId

internal object MessageRepositoryFetch {
    suspend fun fetchMessages(
        messageApi: MessageApi,
        agentId: AgentId,
        conversationId: ConversationId,
        targetMessageId: String?,
        defaultFetchLimit: Int,
        targetedFetchLimit: Int,
        maxTargetedFetchPages: Int,
    ): List<AppMessage> {
        return try {
            if (targetMessageId.isNullOrBlank()) {
                messageApi.fetchRecentMessages(
                    conversationId = conversationId,
                    messageLimit = defaultFetchLimit,
                    beforeMessageId = null,
                ).toAppMessages()
            } else {
                fetchMessagesUntilTarget(
                    messageApi = messageApi,
                    agentId = agentId,
                    conversationId = conversationId,
                    targetMessageId = targetMessageId,
                    targetedFetchLimit = targetedFetchLimit,
                    maxTargetedFetchPages = maxTargetedFetchPages,
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("MessageRepository", "fetchMessages failed", e)
            emptyList()
        }
    }

    suspend fun fetchMessagesUntilTarget(
        messageApi: MessageApi,
        agentId: AgentId,
        conversationId: ConversationId,
        targetMessageId: String,
        targetedFetchLimit: Int,
        maxTargetedFetchPages: Int,
    ): List<AppMessage> {
        var after: String? = null
        var pagesFetched = 0
        var mergedMessages: List<AppMessage> = emptyList()

        while (pagesFetched < maxTargetedFetchPages) {
            val page = messageApi.listMessages(
                agentId = agentId,
                limit = targetedFetchLimit,
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

            if (page.size < targetedFetchLimit) break

            after = page.lastOrNull()?.id ?: break
            pagesFetched++
        }

        return mergedMessages
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

        val iroh = irohTimelineTransport
        if (iroh?.shouldUseIroh() == true) {
            return iroh.listOlderConversationMessages(
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
}
