package com.letta.mobile.data.repository

import com.letta.mobile.data.api.AgentApi
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.RoomAgentLocalCache
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.LocalRuntimeAgentSource
import com.letta.mobile.data.transport.api.IChannelTransport
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Android binding for [CachedAgentRepository]: Room cache + HTTP [AgentApi] + optional Iroh source.
 *
 * Phase 5a — caching / refresh / transport routing live in sharedLogic; this type keeps the
 * historical constructor for Hilt and existing unit tests.
 */
open class AgentRepository(
    agentApi: AgentApi,
    agentDao: Lazy<AgentDao>,
    repositoryScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    localAgentSource: LocalRuntimeAgentSource? = null,
    settingsRepository: ISettingsRepository? = null,
    transport: IChannelTransport? = null,
    // letta-mobile-71orq: Iroh admin_rpc agent reads. When the active backend
    // is iroh://, getAgent MUST route over the control channel — the raw HTTP
    // AgentApi hard-fails at the purity choke-point.
    irohAgentSource: IrohAdminRpcAgentSource? =
        if (transport != null && settingsRepository != null) {
            IrohAdminRpcAgentSource(transport, settingsRepository)
        } else {
            null
        },
) : CachedAgentRepository(
    remote = agentApi,
    localCache = { RoomAgentLocalCache(agentDao.get()) },
    repositoryScope = repositoryScope,
    localAgentSource = localAgentSource,
    settingsRepository = settingsRepository,
    transport = transport,
    irohAgentSource = irohAgentSource,
)
