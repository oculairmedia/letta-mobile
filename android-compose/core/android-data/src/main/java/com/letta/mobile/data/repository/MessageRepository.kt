package com.letta.mobile.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.paging.MessagePagingSource
import com.letta.mobile.data.timeline.IrohAdminRpcTimelineTransport
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android binding for [CachedMessageRepository]: HTTP [MessageApi] + optional Iroh seams.
 *
 * Phase 5p — fetch / approval / inspector / batch orchestration live in sharedLogic;
 * [getMessagesPaged] stays here because [MessagePagingSource] is Android-bound.
 */
@Singleton
class MessageRepository @Inject constructor(
    private val messageApi: MessageApi,
    irohApprovalSource: IrohAdminRpcApprovalSource? = null,
    irohTimelineTransport: IrohAdminRpcTimelineTransport? = null,
) : CachedMessageRepository(
    remote = messageApi,
    irohTimelineSource = irohTimelineTransport?.let(::IrohAdminRpcMessageTimelineSource),
    irohApprovalSource = irohApprovalSource,
) {
    companion object {
        const val INITIAL_FETCH_LIMIT: Int = CachedMessageRepository.INITIAL_FETCH_LIMIT
        const val OLDER_MESSAGES_PAGE_SIZE: Int = CachedMessageRepository.OLDER_MESSAGES_PAGE_SIZE
        const val DEFAULT_FETCH_LIMIT: Int = CachedMessageRepository.DEFAULT_FETCH_LIMIT
        const val TARGETED_FETCH_LIMIT: Int = CachedMessageRepository.TARGETED_FETCH_LIMIT
        const val MAX_TARGETED_FETCH_PAGES: Int = CachedMessageRepository.MAX_TARGETED_FETCH_PAGES
    }

    override fun getMessagesPaged(agentId: AgentId?, conversationId: ConversationId?): Flow<PagingData<AppMessage>> {
        return Pager(
            config = PagingConfig(
                pageSize = MessagePagingSource.PAGE_SIZE,
                enablePlaceholders = false,
                initialLoadSize = MessagePagingSource.PAGE_SIZE,
            ),
            pagingSourceFactory = { MessagePagingSource(messageApi, agentId, conversationId) },
        ).flow
    }
}
