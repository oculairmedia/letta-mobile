package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.RoomConversationLocalCache
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.LocalRuntimeConversationSource
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Android binding for [CachedConversationRepository]: Room cache + HTTP
 * [ConversationApi] + optional Iroh source.
 *
 * Phase 5b — caching / refresh / transport routing live in sharedLogic; this
 * type keeps the historical constructor for Hilt and existing unit tests.
 */
open class ConversationRepository(
    conversationApi: ConversationApi,
    agentRepository: IAgentRepository,
    conversationDao: Lazy<ConversationDao>,
    repositoryScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    localConversationSource: LocalRuntimeConversationSource? = null,
    settingsRepository: ISettingsRepository? = null,
    irohConversationListSource: IrohAdminRpcConversationListSource? = null,
) : CachedConversationRepository(
    remote = conversationApi,
    agentRepository = agentRepository,
    localCache = { RoomConversationLocalCache(conversationDao.get()) },
    repositoryScope = repositoryScope,
    localConversationSource = localConversationSource,
    settingsRepository = settingsRepository,
    irohConversationSource = irohConversationListSource,
)
