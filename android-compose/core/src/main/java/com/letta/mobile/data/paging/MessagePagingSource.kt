package com.letta.mobile.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.mapper.toAppMessages
import com.letta.mobile.data.model.AppMessage

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
            // Always use the agent messages endpoint — it returns tool_call_message
            // types needed for resolving tool names. Use conversation_id to filter.
            val lettaMessages = if (agentId != null) {
                messageApi.listMessages(
                    agentId = agentId,
                    limit = params.loadSize,
                    after = afterCursor,
                    conversationId = conversationId,
                )
            } else {
                emptyList()
            }

            val appMessages = lettaMessages.toAppMessages()

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
}
