package com.letta.mobile.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.RoomAllConversationsLocalCache
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.paging.ConversationPagingSource
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.LocalRuntimeConversationSource
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

internal fun defaultAllConversationsScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Android binding for [CachedAllConversationsRepository]: Room cache + HTTP [ConversationApi]
 * + optional Iroh / local-runtime sources.
 *
 * Phase 5b.2 — refresh/cache/hasMore/cursor logic lives in sharedLogic;
 * [getConversationsPaged] stays here because [ConversationPagingSource] is Android-bound.
 */
open class AllConversationsRepository(
    private val conversationApi: ConversationApi,
    conversationDao: Lazy<ConversationDao>? = null,
    repositoryScope: CoroutineScope,
    localConversationSource: LocalRuntimeConversationSource? = null,
    settingsRepository: ISettingsRepository? = null,
    irohConversationListSource: IrohAdminRpcConversationListSource? = null,
) : CachedAllConversationsRepository(
    remote = conversationApi,
    localCache = conversationDao?.let { dao -> { RoomAllConversationsLocalCache(dao.get()) } },
    repositoryScope = repositoryScope,
    localConversationSource = localConversationSource,
    settingsRepository = settingsRepository,
    irohConversationListSource = irohConversationListSource,
) {
    /** Hilt-friendly constructor — uses [defaultAllConversationsScope]. */
    @Inject
    constructor(
        conversationApi: ConversationApi,
        conversationDao: Lazy<ConversationDao>?,
        localConversationSource: LocalRuntimeConversationSource,
        settingsRepository: ISettingsRepository,
    ) : this(
        conversationApi = conversationApi,
        conversationDao = conversationDao,
        repositoryScope = defaultAllConversationsScope(),
        localConversationSource = localConversationSource,
        settingsRepository = settingsRepository,
    )

    /** Remote-only convenience constructor (tests, previews). */
    constructor(
        conversationApi: ConversationApi,
        conversationDao: Lazy<ConversationDao>? = null,
    ) : this(
        conversationApi = conversationApi,
        conversationDao = conversationDao,
        repositoryScope = defaultAllConversationsScope(),
    )

    internal fun createConversationsPagingSource(
        agentId: AgentId?,
        archiveStatus: String?,
        summarySearch: String?,
    ): ConversationPagingSource = ConversationPagingSource(
        conversationApi = conversationApi,
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
