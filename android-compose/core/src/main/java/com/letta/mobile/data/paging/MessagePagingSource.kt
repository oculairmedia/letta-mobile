package com.letta.mobile.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import java.time.Instant

class MessagePagingSource(
    private val messageApi: MessageApi,
    private val agentId: String?,
    private val conversationId: String?
) : PagingSource<String, AppMessage>() {

    companion object {
        const val PAGE_SIZE = 50
    }

    override suspend fun load(params: LoadParams<String>): LoadResult<String, AppMessage> {
        val afterCursor = params.key

        return try {
            val lettaMessages = if (conversationId != null) {
                messageApi.listConversationMessages(
                    conversationId = conversationId,
                    limit = params.loadSize,
                    after = afterCursor
                )
            } else if (agentId != null) {
                messageApi.listMessages(
                    agentId = agentId,
                    limit = params.loadSize,
                    after = afterCursor
                )
            } else {
                emptyList()
            }

            val appMessages = lettaMessages.mapNotNull { it.toAppMessage() }

            // Use last message ID as cursor for next page
            val nextKey = if (lettaMessages.size < params.loadSize) {
                null // No more pages
            } else {
                lettaMessages.lastOrNull()?.id
            }

            LoadResult.Page(
                data = appMessages,
                prevKey = null, // Only support forward pagination for messages
                nextKey = nextKey
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<String, AppMessage>): String? {
        return null // Always refresh from the beginning
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
            else -> null
        }
    }

    private fun parseDate(dateString: String): Instant {
        return try {
            Instant.parse(dateString)
        } catch (e: Exception) {
            Instant.now()
        }
    }
}
