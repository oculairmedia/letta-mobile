package com.letta.mobile.data.repository

import com.letta.mobile.data.mapper.toAppMessages
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.MessageIrohTimelineSource
import com.letta.mobile.data.repository.api.MessageRemoteSource
import com.letta.mobile.data.repository.api.OlderMessagesPage
import com.letta.mobile.util.Telemetry

internal data class MessageFetchParams(
    val remote: MessageRemoteSource,
    val agentId: AgentId,
    val conversationId: ConversationId,
    val targetMessageId: String?,
    val defaultFetchLimit: Int,
    val targetedFetchLimit: Int,
    val maxTargetedFetchPages: Int,
)

internal data class TargetedMessageFetchParams(
    val remote: MessageRemoteSource,
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
                        remote = params.remote,
                        agentId = params.agentId,
                        conversationId = params.conversationId,
                        targetMessageId = params.targetMessageId,
                        targetedFetchLimit = params.targetedFetchLimit,
                        maxTargetedFetchPages = params.maxTargetedFetchPages,
                    )
                )
            }
        } catch (e: Exception) {
            Telemetry.event(
                "MessageRepository",
                "fetchMessages failed",
                "error" to (e.message ?: e.toString()),
                level = Telemetry.Level.WARN,
            )
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
        remote: MessageRemoteSource,
        irohTimelineSource: MessageIrohTimelineSource?,
        agentId: AgentId,
        conversationId: ConversationId,
        beforeMessageId: String,
        olderMessagesPageSize: Int,
    ): List<AppMessage> {
        if (beforeMessageId.isBlank()) return emptyList()

        if (irohTimelineSource?.shouldUseIroh() == true) {
            return irohTimelineSource.listOlderConversationMessages(
                conversationId = conversationId.value,
                beforeMessageId = beforeMessageId,
                limit = olderMessagesPageSize,
            ).toAppMessages()
        }

        return remote.fetchRecentMessages(
            conversationId = conversationId,
            messageLimit = olderMessagesPageSize,
            beforeMessageId = beforeMessageId,
        ).toAppMessages()
    }

    suspend fun fetchOlderMessagesPage(
        remote: MessageRemoteSource,
        irohTimelineSource: MessageIrohTimelineSource?,
        agentId: AgentId,
        conversationId: ConversationId,
        beforeMessageId: String,
        olderMessagesPageSize: Int,
    ): OlderMessagesPage {
        if (beforeMessageId.isBlank()) return OlderMessagesPage(emptyList(), hasMore = null)

        if (irohTimelineSource?.shouldUseIroh() == true) {
            val page = irohTimelineSource.listOlderConversationMessagesPage(
                conversationId = conversationId.value,
                beforeMessageId = beforeMessageId,
                limit = olderMessagesPageSize,
            )
            return OlderMessagesPage(
                messages = page.messages.toAppMessages(),
                hasMore = page.hasMore,
            )
        }

        val rawMessages = remote.fetchRecentMessages(
            conversationId = conversationId,
            messageLimit = olderMessagesPageSize,
            beforeMessageId = beforeMessageId,
        )
        return OlderMessagesPage(
            messages = rawMessages.toAppMessages(),
            hasMore = null,
        )
    }

    private suspend fun fetchRecentMessages(params: MessageFetchParams): List<AppMessage> =
        params.remote.fetchRecentMessages(
            conversationId = params.conversationId,
            messageLimit = params.defaultFetchLimit,
            beforeMessageId = null,
        ).toAppMessages()

    private suspend fun fetchTargetedPage(
        params: TargetedMessageFetchParams,
        after: String?,
    ): List<AppMessage> =
        params.remote.listMessages(
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
