package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.RemoteRoute
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.RoomAllConversationsLocalCache
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.paging.ConversationPagingSource
import com.letta.mobile.data.repository.api.ConversationRemoteSource
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.LocalRuntimeConversationSource
import com.letta.mobile.data.transport.api.IChannelTransport
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow

/** The scope of a repository built outside a session graph (tests, previews); graphs pass their own. */
@Suppress("NoDetachedCoroutineLifecycle")
internal fun defaultAllConversationsScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Android binding for [CachedAllConversationsRepository]: Room cache + Iroh / local-runtime sources.
 *
 * letta-mobile-ckmub.4: [remote] is the seam for a non-App-Server backend; production binds no
 * route (the Letta REST backend was removed).
 *
 * Phase 5b.2 — refresh/cache/hasMore/cursor logic lives in sharedLogic;
 * [getConversationsPaged] stays here because [ConversationPagingSource] is Android-bound.
 */
open class AllConversationsRepository(
    private val remote: RemoteRoute<ConversationRemoteSource> = RemoteRoute.Absent(SessionRoute.CONVERSATIONS),
    conversationDao: Lazy<ConversationDao>? = null,
    repositoryScope: CoroutineScope,
    localConversationSource: LocalRuntimeConversationSource? = null,
    settingsRepository: ISettingsRepository? = null,
    irohConversationListSource: IrohAdminRpcConversationListSource? = null,
    transport: IChannelTransport? = null,
) : CachedAllConversationsRepository(
    remote = remote,
    localCache = conversationDao?.let { dao -> { RoomAllConversationsLocalCache(dao.get()) } },
    repositoryScope = repositoryScope,
    localConversationSource = localConversationSource,
    settingsRepository = settingsRepository,
    irohConversationListSource = irohConversationListSource,
    transport = transport,
) {
    /** A repository over [remote] (the backend seam; tests and previews bind a fake). */
    constructor(
        remote: ConversationRemoteSource,
        conversationDao: Lazy<ConversationDao>? = null,
        repositoryScope: CoroutineScope = defaultAllConversationsScope(),
        localConversationSource: LocalRuntimeConversationSource? = null,
        settingsRepository: ISettingsRepository? = null,
        irohConversationListSource: IrohAdminRpcConversationListSource? = null,
        transport: IChannelTransport? = null,
    ) : this(
        remote = remote.bound(),
        conversationDao = conversationDao,
        repositoryScope = repositoryScope,
        localConversationSource = localConversationSource,
        settingsRepository = settingsRepository,
        irohConversationListSource = irohConversationListSource,
        transport = transport,
    )

    internal fun createConversationsPagingSource(
        agentId: AgentId?,
        archiveStatus: String?,
        summarySearch: String?,
    ): ConversationPagingSource = ConversationPagingSource(
        remote = remote,
        agentId = agentId,
        archiveStatus = archiveStatus,
        summarySearch = summarySearch,
        order = "desc",
        orderBy = "last_message_at",
        pageLoader = irohPageLoaderOrNull()?.let { loader ->
            { pageAgentId, limit, after, pageArchiveStatus, pageSummarySearch, order, orderBy ->
                loader(
                    pageAgentId,
                    limit,
                    after,
                    pageArchiveStatus,
                    pageSummarySearch,
                    order,
                    orderBy,
                )
            }
        },
    )

    override fun getConversationsPaged(
        agentId: AgentId?,
        archiveStatus: String?,
        summarySearch: String?,
    ): Flow<PagingData<Conversation>> {
        return Pager(
            config = PagingConfig(
                pageSize = ConversationPagingSource.PAGE_SIZE,
                enablePlaceholders = false,
                initialLoadSize = ConversationPagingSource.PAGE_SIZE,
            ),
            pagingSourceFactory = {
                createConversationsPagingSource(agentId, archiveStatus, summarySearch)
            },
        ).flow
    }
}
