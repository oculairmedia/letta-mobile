package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.RemoteRoute
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.paging.MessagePagingSource
import com.letta.mobile.data.repository.api.MessageRemoteSource
import com.letta.mobile.data.timeline.IrohAdminRpcTimelineTransport
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android binding for [CachedMessageRepository]: messages over an Iroh host's `admin_rpc`.
 *
 * letta-mobile-ckmub.4: [remote] is the seam for a non-App-Server backend; production binds no
 * route (the Letta REST backend was removed).
 *
 * Phase 5p — fetch / approval / inspector / batch orchestration live in sharedLogic;
 * [getMessagesPaged] stays here because [MessagePagingSource] is Android-bound.
 */
@Singleton
class MessageRepository(
    private val remote: RemoteRoute<MessageRemoteSource>,
    irohApprovalSource: IrohAdminRpcApprovalSource? = null,
    irohTimelineTransport: IrohAdminRpcTimelineTransport? = null,
) : CachedMessageRepository(
    remote = remote,
    irohTimelineSource = irohTimelineTransport?.let(::IrohAdminRpcMessageTimelineSource),
    irohApprovalSource = irohApprovalSource,
) {
    @Inject
    constructor(
        irohApprovalSource: IrohAdminRpcApprovalSource?,
        irohTimelineTransport: IrohAdminRpcTimelineTransport?,
    ) : this(RemoteRoute.Absent(SessionRoute.CONVERSATIONS), irohApprovalSource, irohTimelineTransport)

    /** A repository over a bound [remote] (tests bind a fake). */
    constructor(
        remote: MessageRemoteSource,
        irohApprovalSource: IrohAdminRpcApprovalSource? = null,
        irohTimelineTransport: IrohAdminRpcTimelineTransport? = null,
    ) : this(remote.bound(), irohApprovalSource, irohTimelineTransport)

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
            pagingSourceFactory = { MessagePagingSource(remote, agentId, conversationId) },
        ).flow
    }
}
