package com.letta.mobile.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.model.Conversation

class ConversationPagingSource(
    private val conversationApi: ConversationApi,
    private val agentId: String? = null,
    private val archiveStatus: String? = null,
    private val summarySearch: String? = null,
    private val order: String? = null,
    private val orderBy: String? = null,
) : PagingSource<String, Conversation>() {

    override suspend fun load(params: LoadParams<String>): LoadResult<String, Conversation> {
        return try {
            val conversations = conversationApi.listConversations(
                agentId = agentId,
                limit = params.loadSize,
                after = params.key,
                archiveStatus = archiveStatus,
                summarySearch = summarySearch,
                order = order,
                orderBy = orderBy,
            )
            LoadResult.Page(
                data = conversations,
                prevKey = null,
                nextKey = conversations.lastOrNull()?.id?.takeIf { conversations.size >= params.loadSize },
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<String, Conversation>): String? = null

    companion object {
        const val PAGE_SIZE = 50
    }
}
