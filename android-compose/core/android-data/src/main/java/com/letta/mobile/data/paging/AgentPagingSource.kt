package com.letta.mobile.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.letta.mobile.data.api.AgentApi
import com.letta.mobile.data.model.Agent

class AgentPagingSource(
    private val agentApi: AgentApi,
    private val tags: List<String>? = null
) : PagingSource<Int, Agent>() {

    companion object {
        const val PAGE_SIZE = 20
        const val INITIAL_OFFSET = 0
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Agent> {
        val offset = params.key ?: INITIAL_OFFSET

        return try {
            val agents = agentApi.listAgents(
                limit = params.loadSize,
                offset = offset,
                tags = tags
            )

            val nextOffset = if (agents.size < params.loadSize) {
                null // No more pages
            } else {
                offset + agents.size
            }

            val prevOffset = if (offset == INITIAL_OFFSET) {
                null
            } else {
                maxOf(INITIAL_OFFSET, offset - params.loadSize)
            }

            LoadResult.Page(
                data = agents,
                prevKey = prevOffset,
                nextKey = nextOffset
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Agent>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(PAGE_SIZE) ?: anchorPage?.nextKey?.minus(PAGE_SIZE)
        }
    }
}
