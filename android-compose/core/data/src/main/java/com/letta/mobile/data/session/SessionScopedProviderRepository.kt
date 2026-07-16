package com.letta.mobile.data.session

import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderCheckParams
import com.letta.mobile.data.model.ProviderCreateParams
import com.letta.mobile.data.model.ProviderId
import com.letta.mobile.data.model.ProviderUpdateParams
import com.letta.mobile.data.repository.api.IProviderRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal fun defaultSessionScopedProviderRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedProviderRepository internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
) : IProviderRepository {
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedProviderRepositoryScope(),
    )

    private val _providers = MutableStateFlow(sessionManager.current.providerRepository.providers.value)
    override val providers: StateFlow<List<Provider>> = _providers

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.providerRepository.providers }
            .onEach { _providers.value = it }
            .launchIn(proxyScope)
    }

    private val current: IProviderRepository
        get() = sessionManager.current.providerRepository

    override suspend fun refreshProviders(name: String?, providerType: String?) =
        sessionManager.withCurrentSession { it.providerRepository.refreshProviders(name, providerType) }

    override suspend fun getProvider(providerId: ProviderId): Provider = sessionManager.withCurrentSession { it.providerRepository.getProvider(providerId) }

    override suspend fun createProvider(params: ProviderCreateParams): Provider = sessionManager.withCurrentSession { it.providerRepository.createProvider(params) }

    override suspend fun updateProvider(providerId: ProviderId, params: ProviderUpdateParams): Provider =
        sessionManager.withCurrentSession { it.providerRepository.updateProvider(providerId, params) }

    override suspend fun checkProvider(params: ProviderCheckParams) = sessionManager.withCurrentSession { it.providerRepository.checkProvider(params) }

    override suspend fun checkExistingProvider(providerId: ProviderId) = sessionManager.withCurrentSession { it.providerRepository.checkExistingProvider(providerId) }

    override suspend fun deleteProvider(providerId: ProviderId) = sessionManager.withCurrentSession { it.providerRepository.deleteProvider(providerId) }

    fun close() { proxyScope.cancel() }
}
