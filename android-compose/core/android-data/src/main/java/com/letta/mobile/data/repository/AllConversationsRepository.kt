package com.letta.mobile.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.letta.mobile.data.backend.noAppServerRoute
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
import javax.inject.Inject

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
    private val remote: ConversationRemoteSource = noAppServerRoute(),
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
    /** Hilt-friendly constructor — uses [defaultAllConversationsScope]. */
    @Inject
    constructor(
        conversationDao: Lazy<ConversationDao>?,
        localConversationSource: LocalRuntimeConversationSource,
        settingsRepository: ISettingsRepository,
    ) : this(
        conversationDao = conversationDao,
        repositoryScope = defaultAllConversationsScope(),
        localConversationSource = localConversationSource,
        settingsRepository = settingsRepository,
    )

    /** A repository over [remote] (the backend seam; tests and previews bind a fake). */
    constructor(
        remote: ConversationRemoteSource,
        conversationDao: Lazy<ConversationDao>? = null,
        localConversationSource: LocalRuntimeConversationSource? = null,
        settingsRepository: ISettingsRepository? = null,
    ) : this(
        remote = remote,
        localConversationSource = localConversationSource,
        settingsRepository = settingsRepository,
        conversationDao = conversationDao,
        repositoryScope = defaultAllConversationsScope(),
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
