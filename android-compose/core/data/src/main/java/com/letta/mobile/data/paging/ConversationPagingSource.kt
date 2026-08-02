package com.letta.mobile.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation

internal typealias ConversationPageLoader = suspend (
    agentId: AgentId?,
    limit: Int,
    after: String?,
    archiveStatus: String?,
    summarySearch: String?,
    order: String?,
    orderBy: String?,
) -> List<Conversation>

class ConversationPagingSource(
    private val conversationApi: ConversationApi,
    private val agentId: AgentId? = null,
    private val archiveStatus: String? = null,
    private val summarySearch: String? = null,
    private val order: String? = null,
    private val orderBy: String? = null,
    internal val pageLoader: ConversationPageLoader? = null,
) : PagingSource<String, Conversation>() {

    override suspend fun load(params: LoadParams<String>): LoadResult<String, Conversation> {
        return try {
            val conversations = if (pageLoader != null) {
                pageLoader.invoke(
                    agentId,
                    params.loadSize,
                    params.key,
                    archiveStatus,
                    summarySearch,
                    order,
                    orderBy,
                )
            } else {
                conversationApi.listConversations(
                    agentId = agentId,
                    limit = params.loadSize,
                    after = params.key,
                    archiveStatus = archiveStatus,
                    summarySearch = summarySearch,
                    order = order,
                    orderBy = orderBy,
                )
            }
            LoadResult.Page(
                data = conversations,
                prevKey = null,
                nextKey = conversations.lastOrNull()?.id?.value?.takeIf { conversations.size >= params.loadSize },
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
